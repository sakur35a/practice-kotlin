package com.example.diary.diary

import java.util.UUID

data class DiaryResponse(
    val id: UUID,
    val title: String,
    val content: String,
) {
    constructor(diary: Diary) : this(
        id = diary.id,
        title = diary.title,
        content = diary.content,
    )
}
