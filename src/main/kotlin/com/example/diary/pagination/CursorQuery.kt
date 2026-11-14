package com.example.diary.pagination

import java.util.UUID

data class CursorQuery(
    val cursorId: UUID? = null,
    val size: Int = DEFAULT_SIZE,
) {
    // 컨트롤러가 요청 파라미터에 같은 범위를 Bean Validation(@Min/@Max)으로 먼저 검사한다. 여기는 도메인에서 한 번 더 지키는 곳이다
    init {
        require(size in MIN_SIZE..MAX_SIZE) {
            "size must be between $MIN_SIZE and $MAX_SIZE"
        }
    }

    companion object {
        const val DEFAULT_SIZE = 20
        const val MIN_SIZE = 1
        const val MAX_SIZE = 100
    }
}
