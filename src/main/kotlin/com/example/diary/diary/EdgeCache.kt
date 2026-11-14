package com.example.diary.diary

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Cloudflare 엣지에 캐시된 목록을 글이 써지면 비운다(태그 purge). 앱 안에는 따로 캐시를 두지 않는다.
// purge는 가상 스레드에서 비동기로 실행해 POST 응답을 막지 않는다. 그래서 글을 쓴 직후 목록을 읽으면
// purge가 끝나기 전의 옛 목록이 엣지에서 나올 수 있다(보통 1초 안쪽). purge가 실패해도 EDGE_LIST_TTL 뒤에는 맞아진다.
@Component
class EdgeCache internal constructor(
    private val cloudflare: DiaryProperties.Cloudflare,
    private val http: RestClient,
) {
    @Autowired
    constructor(properties: DiaryProperties) : this(
        properties.cloudflare,
        RestClient
            .builder()
            .requestFactory(
                JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build())
                    .apply { setReadTimeout(Duration.ofSeconds(8)) },
            ).build(),
    )

    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    // 글이 커밋된 뒤에 부른다. 커밋 전에 비우면 그사이 엣지가 옛 목록을 다시 저장한다.
    fun purgeList() {
        if (cloudflare.zoneId.isBlank() || cloudflare.apiToken.isBlank()) return
        executor.execute {
            runCatching {
                http
                    .post()
                    .uri("https://api.cloudflare.com/client/v4/zones/{zone}/purge_cache", cloudflare.zoneId)
                    .header("Authorization", "Bearer ${cloudflare.apiToken}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"tags":["$LIST_TAG"]}""")
                    .retrieve()
                    .toBodilessEntity()
            }.onFailure { log.warn("layer=cache action=edge_purge_failed error={}", it.toString()) }
        }
    }

    // 종료나 재배포 때 진행 중인 purge를 잠깐 기다린다
    @PreDestroy
    fun close() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        private val log = LoggerFactory.getLogger(EdgeCache::class.java)

        const val LIST_TAG = "diary-list"
        val LIST_TTL: Duration = Duration.ofMinutes(5)
        val DIARY_TTL: Duration = Duration.ofDays(1)
    }
}
