package com.example.diary.diary

import com.example.diary.pagination.CursorQuery
import com.example.diary.pagination.CursorSlice
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DiaryService(
    private val diaryRepository: DiaryRepository,
) {
    @Transactional(readOnly = true)
    fun findAll(): List<Diary> = diaryRepository.findAll()

    @Transactional(readOnly = true)
    fun findDiarySlice(cursorQuery: CursorQuery): CursorSlice<Diary> = diaryRepository.findDiarySlice(cursorQuery)

    @Transactional
    fun createDiary(diary: Diary): Diary {
        diaryRepository.createDiary(diary)
        return diary
    }
}
