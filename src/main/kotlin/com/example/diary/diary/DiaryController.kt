package com.example.diary.diary

import com.example.diary.pagination.CursorQuery
import com.example.diary.pagination.CursorSlice
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("/diary")
@CrossOrigin
class DiaryController(
    private val diaryService: DiaryService,
) {
    @GetMapping
    fun getDiaryPreviews(cursorQuery: CursorQuery): CursorSlice<DiaryPreviewResponse> {
        val startedAt = System.nanoTime()
        val response =
            diaryService
                .findDiarySlice(cursorQuery)
                .mapItems(::DiaryPreviewResponse)

        logger.info {
            "layer=controller operation=getDiaryPreviews elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000} items=${response.items.size}"
        }
        return response
    }

    @PostMapping
    fun createDiaries(
        @RequestBody diary: Diary,
    ): ResponseEntity<Diary> {
        val startedAt = System.nanoTime()
        val createdDiary = diaryService.createDiary(diary)

        logger.info {
            "layer=controller operation=createDiary elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000} id=${createdDiary.id}"
        }
        return ResponseEntity.created(URI.create("/diary/${createdDiary.id}")).body(createdDiary)
    }
}
