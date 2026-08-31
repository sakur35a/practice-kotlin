package com.example.diary.diary

import com.example.diary.pagination.CursorQuery
import com.example.diary.pagination.CursorSlice
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class DiaryService(
    private val diaryRepository: DiaryRepository,
) {
    fun findAll(): List<Diary> = diaryRepository.findAll()

    fun findDiarySlice(cursorQuery: CursorQuery): CursorSlice<Diary> = diaryRepository.findDiarySlice(cursorQuery)

    fun findById(id: UUID): Diary? = diaryRepository.findById(id)

    @Transactional
    fun createDiary(diary: Diary): Diary {
        diaryRepository.createDiary(diary)
        return diary
    }
}
