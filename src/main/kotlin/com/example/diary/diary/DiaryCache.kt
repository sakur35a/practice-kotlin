package com.example.diary.diary

import com.example.diary.pagination.CursorQuery
import com.example.diary.pagination.CursorSlice
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

// 서버마다 따로 두는 읽기 캐시. 글이 써지면 자기 캐시, 상대 서버 캐시, Cloudflare 엣지 캐시를 차례로 비운다.
@Component
class DiaryCache(
    @Value("\${diary.cache.peer-url:}") private val peerUrl: String,
    @Value("\${diary.cloudflare.zone-id:}") private val zoneId: String,
    @Value("\${diary.cloudflare.api-token:}") private val apiToken: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 일기는 수정/삭제가 없어 한 건 조회 결과가 바뀌지 않는다. 글자 수로만 크기를 제한한다
    private val diaries: Cache<UUID, DiaryResponse> =
        Caffeine
            .newBuilder()
            .maximumWeight(MAX_CACHED_CHARS)
            .weigher<UUID, DiaryResponse> { _, diary -> diary.title.length + diary.content.length + 1 }
            .build()

    // 목록은 새 글이 써지면 바뀐다. 상대 서버 비우기를 놓쳐도 TTL 뒤에는 맞아진다
    private val slices: Cache<SliceKey, CursorSlice<DiaryPreviewResponse>> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(LIST_TTL)
            .maximumSize(200)
            .build()

    // 비울 때 세대를 올린다. 비우기 전에 DB를 읽기 시작한 요청이 옛 목록을 넣어도 새 세대 키로는 보이지 않는다
    private val generation = AtomicLong()

    private data class SliceKey(
        val generation: Long,
        val query: CursorQuery,
    )

    private val http =
        RestClient
            .builder()
            .requestFactory(
                JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build())
                    .apply { setReadTimeout(Duration.ofSeconds(3)) },
            ).build()

    fun diary(
        id: UUID,
        load: (UUID) -> DiaryResponse?,
    ): DiaryResponse? = diaries.getIfPresent(id) ?: load(id)?.also { diaries.put(id, it) } // 없는 id는 캐시하지 않는다

    fun slice(
        query: CursorQuery,
        load: () -> CursorSlice<DiaryPreviewResponse>,
    ): CursorSlice<DiaryPreviewResponse> = slices.get(SliceKey(generation.get(), query)) { load() }

    fun evictLocal() {
        generation.incrementAndGet()
        slices.invalidateAll()
    }

    // 글이 커밋된 뒤에 부른다. 커밋 전에 비우면 그사이 읽은 옛 목록이 다시 캐시된다.
    // 응답 전에 끝내서, 글을 쓴 사람이 바로 목록을 다시 읽으면 새 글이 보이게 한다. 실패해도 글쓰기는 성공으로 둔다.
    fun afterWrite() {
        evictLocal()
        if (peerUrl.isNotBlank()) {
            runCatching { http.post().uri("$peerUrl/internal/cache/evict").retrieve().toBodilessEntity() }
                .onFailure { log.warn("layer=cache action=peer_evict_failed peer={} error={}", peerUrl, it.toString()) }
        }
        // ponytail: 엣지가 purge 직전에 원본에서 받은 옛 목록을 purge 직후 저장하는 아주 짧은 경합이 있다. 그 경우도 EDGE_LIST_TTL 뒤에 맞아진다
        if (zoneId.isNotBlank() && apiToken.isNotBlank()) {
            runCatching {
                http
                    .post()
                    .uri("https://api.cloudflare.com/client/v4/zones/{zone}/purge_cache", zoneId)
                    .header("Authorization", "Bearer $apiToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"tags":["$EDGE_LIST_TAG"]}""")
                    .retrieve()
                    .toBodilessEntity()
            }.onFailure { log.warn("layer=cache action=edge_purge_failed error={}", it.toString()) }
        }
    }

    companion object {
        const val EDGE_LIST_TAG = "diary-list"
        val LIST_TTL: Duration = Duration.ofMinutes(5)
        val EDGE_LIST_TTL: Duration = Duration.ofMinutes(5)
        val EDGE_DIARY_TTL: Duration = Duration.ofDays(1)
        private const val MAX_CACHED_CHARS = 5_000_000L
    }
}

// 상대 서버가 사설망으로 부른다. 공개 경로에서는 haproxy가 막는다(deploy/haproxy.cfg). 자기 캐시만 비우고 다시 퍼뜨리지 않는다.
@RestController
class InternalCacheController(
    private val diaryCache: DiaryCache,
) {
    @PostMapping("/internal/cache/evict")
    fun evict(): ResponseEntity<Void> {
        diaryCache.evictLocal()
        return ResponseEntity.noContent().build()
    }
}
