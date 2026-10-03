package com.example.diary.diary

import com.example.diary.pagination.CursorQuery
import com.example.diary.pagination.CursorSlice
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Duration
import java.util.UUID

@RestController
@RequestMapping("/diary")
@CrossOrigin
class DiaryController(
    private val diaryService: DiaryService,
    private val edgeCache: EdgeCache,
) {
    // 첫 페이지는 새 글이 맨 위에 붙어서 바뀌므로 브라우저가 매번 확인한다. 커서로 가는 이전 페이지는 만든 뒤 바뀌지 않는다.
    @GetMapping
    fun getDiaryPreviews(cursorQuery: CursorQuery): ResponseEntity<CursorSlice<DiaryPreviewResponse>> =
        cacheable(EdgeCache.LIST_TTL, if (cursorQuery.cursorId == null) CacheControl.noCache() else IMMUTABLE)
            .header("Cache-Tag", EdgeCache.LIST_TAG)
            .body(diaryService.findDiarySlice(cursorQuery).mapItems(::DiaryPreviewResponse))

    @GetMapping("/{id}")
    fun getDiary(
        @PathVariable id: UUID,
    ): ResponseEntity<DiaryResponse> =
        diaryService.findById(id)?.let { cacheable(EdgeCache.DIARY_TTL, IMMUTABLE).body(DiaryResponse(it)) }
            ?: ResponseEntity.notFound().build()

    @PostMapping
    fun createDiaries(
        @RequestBody request: DiaryCreateRequest,
    ): ResponseEntity<DiaryResponse> {
        val createdDiary = diaryService.createDiary(request.id, request.title, request.content)
        edgeCache.purgeList()

        return ResponseEntity.created(URI.create("/diary/${createdDiary.id}")).body(DiaryResponse(createdDiary))
    }

    // 브라우저는 browser 정책을 따른다(기본은 매번 ETag로 확인하고 바뀌지 않았으면 304). Cloudflare 엣지는 edgeTtl 동안 들고 있는다.
    // 오류 응답에는 붙이지 않는다. 엣지 캐시 규칙이 캐시 헤더 없는 응답은 캐시하지 않게 되어 있다(deploy/README.md)
    private fun cacheable(
        edgeTtl: Duration,
        browser: CacheControl = CacheControl.noCache(),
    ) = ResponseEntity
        .ok()
        .cacheControl(browser)
        .header("Cloudflare-CDN-Cache-Control", "max-age=${edgeTtl.seconds}")

    private companion object {
        // 일기는 만들기만 하고 고치거나 지우는 API가 없다. 한 번 받은 상세와 이전 페이지는 브라우저가 서버에 다시 묻지 않는다.
        // 한국에서 Cloudflare 입구가 멀어 재확인 한 번이 0.5초 이상이라 이 왕복을 없앤다.
        val IMMUTABLE: CacheControl = CacheControl.maxAge(Duration.ofDays(1)).immutable()
    }
}
