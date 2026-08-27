package com.example.diary.diary

import com.example.diary.pagination.CursorQuery
import com.example.diary.pagination.CursorSlice
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/diary")
@CrossOrigin
class DiaryController(
    private val diaryService: DiaryService,
) {
    @GetMapping
    fun getDiaryPreviews(cursorQuery: CursorQuery): CursorSlice<DiaryPreviewResponse> =
        diaryService
            .findDiarySlice(cursorQuery)
            .mapItems(::DiaryPreviewResponse)

    @PostMapping
    fun createDiaries(
        @RequestBody diary: Diary,
    ): ResponseEntity<Diary> {
        val createdDiary = diaryService.createDiary(diary)

        return ResponseEntity
            .created(URI.create("/diary/${createdDiary.id}"))
            .body(createdDiary)
    }
}
