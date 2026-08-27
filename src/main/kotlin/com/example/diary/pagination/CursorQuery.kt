package com.example.diary.pagination

import java.util.UUID

data class CursorQuery(
    val cursorId: UUID? = null,
    val size: Int = 20,
) {
    init {
        require(size in 1..100) {
            "size must be between 1 and 100"
        }
    }
}
