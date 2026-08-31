package com.example.diary.diary

import java.util.UUID

data class DiaryPreviewResponse(
    val id: UUID,
    val title: String,
    val content: String,
) {
    constructor(diary: Diary) : this(
        id = diary.id,
        title = "${diary.title} ${diary.content}",
        content = previewContent(diary.content),
    )

    // 미리보기 길이와 변환 규칙은 Diary 자체가 아니라
    // DiaryPreviewResponse의 표현 정책이므로 이 타입에 동반시킨다.
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
