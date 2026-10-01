package com.example.diary.diary

import com.example.diary.pagination.CursorQuery
import com.example.diary.pagination.CursorSlice
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.data.domain.Limit
import org.springframework.resilience.annotation.Retryable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
// DB 재시작(점검) 중에는 연결 실패가 잠깐 이어진다. 다시 붙을 시간을 준다.
@Retryable(
    includes = [DataAccessResourceFailureException::class, TransientDataAccessException::class],
    maxRetries = 3,
    delay = 500,
    multiplier = 2.0,
)
class DiaryService(
    private val diaryRepository: DiaryRepository,
) {
    @Transactional(readOnly = true)
    fun findAll(): List<Diary> = diaryRepository.findAll()

    @Transactional(readOnly = true)
    fun findDiarySlice(cursorQuery: CursorQuery): CursorSlice<Diary> {
        // 한 건 더 읽어서 다음 페이지가 있는지 알아낸다
        val limit = Limit.of(cursorQuery.size + 1)
        val rows =
            cursorQuery.cursorId
                ?.let { diaryRepository.findByIdLessThanOrderByIdDesc(it, limit) }
                ?: diaryRepository.findAllByOrderByIdDesc(limit)

        val hasNext = rows.size > cursorQuery.size
        val items = if (hasNext) rows.dropLast(1) else rows

        return CursorSlice(
            items = items,
            hasNext = hasNext,
            nextCursorId = if (hasNext) items.last().id else null,
        )
    }

    @Transactional(readOnly = true)
    fun findById(id: UUID): Diary? = diaryRepository.findById(id).orElse(null)

    // 같은 id로 다시 저장하면 덮어쓰지 않고 실패한다 (Diary가 새 엔티티로 표시되어 INSERT만 실행)
    @Transactional
    fun createDiary(
        id: UUID?,
        title: String,
        content: String,
    ): Diary {
        val diary = if (id == null) Diary(title = title, content = content) else Diary(id, title, content)

        return diaryRepository.saveAndFlush(diary)
    }
}
