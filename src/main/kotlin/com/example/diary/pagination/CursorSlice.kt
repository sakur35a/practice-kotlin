package com.example.diary.pagination

import java.util.UUID

data class CursorSlice<T>(
    val items: List<T>,
    val hasNext: Boolean,
    val nextCursorId: UUID?,
) {
    init {
        require(hasNext == (nextCursorId != null)) {
            "nextCursorId must be present if and only if hasNext is true"
        }
    }

    fun <R> mapItems(transform: (T) -> R): CursorSlice<R> =
        CursorSlice(
            items = items.map(transform),
            hasNext = hasNext,
            nextCursorId = nextCursorId,
        )
}
