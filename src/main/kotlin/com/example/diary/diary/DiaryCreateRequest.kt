package com.example.diary.diary

import java.util.UUID

// id는 선택이다. 없으면 서버가 UUID v7을 만든다.
data class DiaryCreateRequest(
    val id: UUID? = null,
    val title: String,
    val content: String,
)
