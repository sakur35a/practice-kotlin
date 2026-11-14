package com.example.diary

import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.TransientDataAccessException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionSystemException
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 예외가 어떻게 감싸이든 원인 사슬의 표준 SQLState로 데이터베이스 상황을 알아보는지 확인한다
class DatabaseErrorsTest {
    @Test
    fun `기본 키 위반(23505)은 감싸여 있어도 찾는다`() {
        val wrapped =
            DataIntegrityViolationException(
                "저장 실패",
                RuntimeException("Hibernate", SQLException("duplicate key", "23505")),
            )

        assertTrue(DatabaseErrors.isUniqueViolation(wrapped))
    }

    @Test
    fun `다른 무결성 위반은 유일성 위반이 아니다`() {
        assertFalse(
            DatabaseErrors.isUniqueViolation(
                DataIntegrityViolationException("너무 김", SQLException("too long", "22001")),
            ),
        )
        assertFalse(DatabaseErrors.isUniqueViolation(DataIntegrityViolationException("원인 없음")))
    }

    @Test
    fun `연결 문제는 모두 데이터베이스를 쓸 수 없는 상황이다`() {
        listOf(
            DataAccessResourceFailureException("x"),
            CannotCreateTransactionException("x"),
            SQLTransientConnectionException("풀 시간 초과"),
            RuntimeException("래퍼", SQLException("연결 끊김", "08006")),
            // 롤백 실패가 원래 예외를 덮은 모양
            TransactionSystemException("롤백 실패", SQLException("I/O error", "08006")),
            RuntimeException("서버 종료 중", SQLException("admin shutdown", "57P01")),
        ).forEach { assertTrue(DatabaseErrors.isUnavailable(it), it.toString()) }
    }

    @Test
    fun `일시적 데이터 접근 오류도 쓸 수 없는 상황으로 본다`() {
        assertTrue(DatabaseErrors.isUnavailable(object : TransientDataAccessException("잠금 시간 초과") {}))
    }

    @Test
    fun `그 밖의 오류는 데이터베이스 장애가 아니다`() {
        assertFalse(DatabaseErrors.isUnavailable(IllegalStateException("버그")))
        assertFalse(
            DatabaseErrors.isUnavailable(DataIntegrityViolationException("x", SQLException("too long", "22001"))),
        )
    }

    @Test
    fun `원인 사슬이 순환해도 끝난다`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)

        assertFalse(DatabaseErrors.isUnavailable(a))
    }
}
