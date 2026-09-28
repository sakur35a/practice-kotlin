package com.example.diary.diary

import com.example.diary.pagination.CursorQuery
import com.example.diary.pagination.CursorSlice
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.resilience.annotation.Retryable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
// primary 전환 중에는 연결 실패가 잠깐 이어진다. 새 primary로 다시 붙을 시간을 준다.
@Retryable(
    includes = [DataAccessResourceFailureException::class, TransientDataAccessException::class],
    maxRetries = 3,
    delay = 500,
    multiplier = 2.0,
)
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
