package com.example.diary

import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.transaction.CannotCreateTransactionException
import java.sql.SQLException

// 예외 원인 사슬에서 데이터베이스 상황을 알아낸다. 드라이버나 JPA 구현이 예외를 어떻게 감싸든(롤백 실패가 원래 예외를 덮는 경우 포함)
// 표준 SQLState로 판단하므로 특정 드라이버 클래스에 의존하지 않는다.
object DatabaseErrors {
    private const val UNIQUE_VIOLATION = "23505"

    fun isUniqueViolation(ex: Throwable): Boolean =
        causes(ex).any {
            it is SQLException &&
                it.sqlState == UNIQUE_VIOLATION
        }

    // 연결을 못 얻음/끊김(08xxx), 서버 종료나 재시작 중(57Pxx), 연결 풀 시간 초과(SQLTransientConnectionException), 트랜잭션 시작 실패
    fun isUnavailable(ex: Throwable): Boolean =
        causes(ex).any {
            it is DataAccessResourceFailureException ||
                it is TransientDataAccessException ||
                it is CannotCreateTransactionException ||
                it is java.sql.SQLTransientConnectionException ||
                it is java.sql.SQLNonTransientConnectionException ||
                (
                    it is SQLException &&
                        (
                            it.sqlState?.startsWith(
                                "08",
                            ) == true ||
                                it.sqlState?.startsWith("57P") == true
                        )
                )
        }

    private fun causes(ex: Throwable): Sequence<Throwable> =
        generateSequence(ex) {
            if (it.cause ===
                it
            ) {
                null
            } else {
                it.cause
            }
        }.take(MAX_DEPTH)

    private const val MAX_DEPTH = 20
}
