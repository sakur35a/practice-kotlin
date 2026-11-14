package com.example.diary.diary

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import java.util.UUID
import kotlin.reflect.KClass

// 요청 DTO에 쓰는 제약. 도메인(Diary)이 이미 지키는 규칙과 정확히 같은 규칙이다 (더 느슨하지도 더 엄격하지도 않다).
// 판정은 Diary의 함수를 그대로 쓴다. 메시지는 사람이 읽는 영어 고정 문구라서 값(일기 내용)을 되돌려 보내지 않고,
// 요청의 Accept-Language와 상관없이 같다 (응답 언어는 영어로 통일: deploy/README.md 오류 계약).

// id가 UUID 버전 7이어야 한다. null은 "서버가 만든다"는 뜻이라 통과시킨다 (필수 여부는 @NotNull이 정한다)
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [UuidV7Validator::class])
annotation class UuidV7(
    val message: String = "must be a UUID version 7",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class UuidV7Validator : ConstraintValidator<UuidV7, UUID?> {
    override fun isValid(
        value: UUID?,
        context: ConstraintValidatorContext,
    ): Boolean = value == null || Diary.isUuidV7(value)
}

// 코드 포인트 기준 최대 길이. @Size는 UTF-16 글자 수(String.length)로 세서, 이모지가 든 제목을 DB(varchar는 코드 포인트로 센다)가
// 받아 주는데도 거부하게 된다.
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [MaxCodePointsValidator::class])
annotation class MaxCodePoints(
    val value: Int,
    val message: String = "must be at most {value} characters",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class MaxCodePointsValidator : ConstraintValidator<MaxCodePoints, String?> {
    private var max = Int.MAX_VALUE

    override fun initialize(constraint: MaxCodePoints) {
        max = constraint.value
    }

    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext,
    ): Boolean = value == null || Diary.codePointLength(value) <= max
}

// NUL 문자(U+0000)가 없어야 한다. JSON으로는 보낼 수 있지만 PostgreSQL이 저장하지 못한다.
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [NoNulValidator::class])
annotation class NoNul(
    val message: String = "must not contain the NUL character (U+0000)",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class NoNulValidator : ConstraintValidator<NoNul, String?> {
    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext,
    ): Boolean = value == null || !Diary.containsNul(value)
}
