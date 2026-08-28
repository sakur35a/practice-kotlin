package com.example.diary.diary

import java.util.UUID

data class DiaryPreviewResponse(
    val id: UUID,
    val title: String,
    val content: String,
) {
    constructor(diary: Diary) : this(
        id = diary.id,
        title = diary.title + " " + diary.content,
        content = previewContent(diary.content),
    )

    private companion object {
        const val CONTENT_PREVIEW_LENGTH = 10

        fun previewContent(content: String): String =
            if (content.length > CONTENT_PREVIEW_LENGTH) {
                "${content.take(CONTENT_PREVIEW_LENGTH)}..."
            } else {
                content
            }
    }
}
