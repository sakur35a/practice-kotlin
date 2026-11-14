package com.example.diary.diary

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
import java.util.UUID

// 같은 id로 만들려 했다. 데이터베이스 기본 키가 진짜 막는 곳이고, 서비스가 그 위반을 이 예외로 옮긴다 (GlobalExceptionHandler가 409로 바꾼다).
class DuplicateDiaryIdException(
    val id: UUID,
    cause: Throwable,
) : RuntimeException("Diary id already exists: $id", cause)

// 없는 일기. 본문 없는 404 대신 RFC 9457 오류 본문을 준다 (type은 about:blank, title은 상태 문구).
class DiaryNotFoundException :
    ErrorResponseException(
        HttpStatus.NOT_FOUND,
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No diary exists with this id."),
        null,
    )
