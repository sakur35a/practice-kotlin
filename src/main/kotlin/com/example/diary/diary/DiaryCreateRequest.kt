package com.example.diary.diary

import jakarta.validation.constraints.NotNull
import java.util.UUID

// id는 선택이다. 없으면 서버가 UUID v7을 만든다.
//
// 제약은 모두 @field:로 붙인다. 이 빌드는 -Xannotation-default-target=param-property라서 접두사 없는 어노테이션은
// "생성자 파라미터(와 속성)"에 붙는데, Bean Validation이 객체를 검사할 때 읽는 곳은 필드(또는 getter)다.
// 그러면 제약이 에러 없이 아무 일도 하지 않는다. DiaryConstraintsTest가 제약마다 실제로 걸리는지 확인한다.
//
// title, content를 null 허용 타입으로 둔 이유: non-null 타입이면 필드가 없거나 null일 때 Jackson이 먼저 "해석 불가"(400)로
// 거부해서 필드별 오류(422, errors[].pointer)를 줄 수 없다. 검사를 통과한 뒤에는 null일 수 없다 (DiaryController).
data class DiaryCreateRequest(
    @field:UuidV7
    val id: UUID? = null,
    @field:NotNull(message = "is required")
    @field:MaxCodePoints(Diary.TITLE_MAX_LENGTH)
    @field:NoNul
    val title: String? = null,
    @field:NotNull(message = "is required")
    @field:NoNul
    val content: String? = null,
)
