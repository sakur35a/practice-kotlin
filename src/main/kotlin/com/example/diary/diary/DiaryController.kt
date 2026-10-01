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
    private val diaryCache: DiaryCache,
) {
    @GetMapping
    fun getDiaryPreviews(cursorQuery: CursorQuery): ResponseEntity<CursorSlice<DiaryPreviewResponse>> =
        cacheable(DiaryCache.EDGE_LIST_TTL)
            .header("Cache-Tag", DiaryCache.EDGE_LIST_TAG)
            .body(diaryCache.slice(cursorQuery) { diaryService.findDiarySlice(cursorQuery).mapItems(::DiaryPreviewResponse) })

    @GetMapping("/{id}")
    fun getDiary(
        @PathVariable id: UUID,
    ): ResponseEntity<DiaryResponse> =
        diaryCache.diary(id) { diaryService.findById(it)?.let(::DiaryResponse) }
            ?.let { cacheable(DiaryCache.EDGE_DIARY_TTL).body(it) }
            ?: ResponseEntity.notFound().build()

    @PostMapping
    fun createDiaries(
        @RequestBody request: DiaryCreateRequest,
    ): ResponseEntity<DiaryResponse> {
        val createdDiary = diaryService.createDiary(request.id, request.title, request.content)
        diaryCache.afterWrite()

        return ResponseEntity.created(URI.create("/diary/${createdDiary.id}")).body(DiaryResponse(createdDiary))
    }

    // 브라우저는 매번 ETag로 확인하고(바뀌지 않았으면 304), Cloudflare 엣지는 edgeTtl 동안 들고 있는다.
    // 오류 응답에는 붙이지 않는다. 엣지 캐시 규칙이 캐시 헤더 없는 응답은 캐시하지 않게 되어 있다(deploy/README.md)
    private fun cacheable(edgeTtl: Duration) =
        ResponseEntity
            .ok()
            .cacheControl(CacheControl.noCache())
            .header("Cloudflare-CDN-Cache-Control", "max-age=${edgeTtl.seconds}")
}
