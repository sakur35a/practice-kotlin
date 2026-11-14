package com.example.diary.diary

import com.example.diary.DatabaseErrors
import com.example.diary.pagination.CursorQuery
import com.example.diary.pagination.CursorSlice
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.TransientDataAccessException
import org.springframework.data.domain.Limit
import org.springframework.resilience.annotation.Retryable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
// PostgreSQL 재시작(패키지 업데이트) 중에는 연결 실패가 잠깐 이어진다. 다시 붙을 시간을 준다.
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

    // 같은 id로 다시 저장하면 덮어쓰지 않고 실패한다 (Diary가 새 엔티티로 표시되어 INSERT만 실행).
    // 미리 존재 여부를 조회하지 않는다: 조회와 저장 사이에 같은 id가 끼어들 수 있어서 경쟁 상태가 생긴다.
    // 진짜 막는 곳은 데이터베이스 기본 키이고, 그 위반(SQLState 23505)을 도메인 예외로 옮겨 HTTP 409로 나가게 한다.
    @Transactional
    fun createDiary(
        id: UUID?,
        title: String,
        content: String,
    ): Diary {
        val diary = if (id == null) Diary(title = title, content = content) else Diary(id, title, content)

        return try {
            diaryRepository.saveAndFlush(diary)
        } catch (ex: DataIntegrityViolationException) {
            if (DatabaseErrors.isUniqueViolation(ex)) throw DuplicateDiaryIdException(diary.id, ex)
            throw ex
        }
    }
}
