package com.example.diary

import com.example.diary.diary.Diary
import com.example.diary.diary.DiaryCreateRequest
import com.example.diary.diary.UuidV7
import jakarta.validation.Validation
import jakarta.validation.constraints.NotNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 접두사 없는 / @field: / @get: 어노테이션이 이 빌드(-Xannotation-default-target=param-property)에서 어디에 붙는지 비교하는 시료
private data class Unprefixed(
    @NotNull val value: String?,
)

private data class FieldPrefixed(
    @field:NotNull val value: String?,
)

private data class GetterPrefixed(
    @get:NotNull val value: String?,
)

private data class UnprefixedCustom(
    @UuidV7 val id: UUID?,
)

// Bean Validation 제약이 "실제로" 걸리는지 확인한다. 어노테이션이 엉뚱한 대상에 붙으면 에러 없이 아무 일도 하지 않으므로,
// 어노테이션이 있는지가 아니라 위반이 나오는지를 본다. DiaryControllerTest는 Validator를 거쳐 HTTP까지의 연결을 확인하고,
// 여기서는 규칙 자체(경계값 포함)와 대상(target) 함정을 확인한다.
class DiaryConstraintsTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    private fun violations(request: DiaryCreateRequest) =
        validator
            .validate(request)
            .map {
                it.propertyPath.toString() to
                    it.constraintDescriptor.annotation.annotationClass.simpleName
            }.toSet()

    private fun valid(
        id: UUID? = null,
        title: String? = "제목",
        content: String? = "내용",
    ) = DiaryCreateRequest(id, title, content)

    @Nested
    inner class `제약이 실제로 걸린다` {
        @Test
        fun `정상 요청은 위반이 없다`() {
            assertEquals(emptySet(), violations(valid()))
            assertEquals(emptySet(), violations(valid(id = Diary(title = "a", content = "b").id)))
        }

        @Test
        fun `title과 content는 필수다`() {
            assertEquals(setOf("title" to "NotNull"), violations(valid(title = null)))
            assertEquals(setOf("content" to "NotNull"), violations(valid(content = null)))
        }

        @Test
        fun `빈 문자열은 필수 위반이 아니다 (도메인과 DB가 받는다)`() {
            assertEquals(emptySet(), violations(valid(title = "", content = "")))
        }

        @Test
        fun `title은 코드 포인트로 255자까지다`() {
            assertEquals(emptySet(), violations(valid(title = "가".repeat(255))))
            assertEquals(setOf("title" to "MaxCodePoints"), violations(valid(title = "가".repeat(256))))
            // 이모지는 UTF-16으로 두 칸이다. @Size(max = 255)였다면 128자부터 거부했을 것이다
            assertEquals(emptySet(), violations(valid(title = "😀".repeat(255))))
            assertEquals(setOf("title" to "MaxCodePoints"), violations(valid(title = "😀".repeat(256))))
        }

        @Test
        fun `NUL 문자는 title과 content 모두에서 거부한다`() {
            assertEquals(setOf("title" to "NoNul"), violations(valid(title = "a\u0000b")))
            assertEquals(setOf("content" to "NoNul"), violations(valid(content = "a\u0000b")))
        }

        @Test
        fun `id는 UUID 버전 7만 받고 null은 받는다`() {
            assertEquals(setOf("id" to "UuidV7"), violations(valid(id = UUID.randomUUID())))
            assertEquals(emptySet(), violations(valid(id = null)))
        }

        @Test
        fun `도메인이 거부하는 값은 가장자리도 거부하고, 받는 값은 받는다`() {
            // 가장자리(Bean Validation)와 도메인(Diary.init)이 같은 규칙인지 값 몇 개로 맞춰 본다
            val samples =
                listOf(
                    Triple(null, "제목", "내용"),
                    Triple(UUID.randomUUID(), "제목", "내용"),
                    Triple(null, "가".repeat(256), "내용"),
                    Triple(null, "😀".repeat(255), "내용"),
                    Triple(null, "a\u0000", "내용"),
                    Triple(null, "제목", "a\u0000"),
                    Triple(null, "", ""),
                )
            samples.forEach { (id, title, content) ->
                val edgeAccepts = violations(valid(id, title, content)).isEmpty()
                val domainAccepts =
                    runCatching {
                        if (id ==
                            null
                        ) {
                            Diary(title = title, content = content)
                        } else {
                            Diary(id, title, content)
                        }
                    }.isSuccess
                assertEquals(
                    domainAccepts,
                    edgeAccepts,
                    "id=$id title=${title.take(5)}(${title.length}) content=${content.take(5)}",
                )
            }
        }
    }

    @Nested
    inner class `어노테이션 대상(target) 함정` {
        // 이 프로젝트는 -Xannotation-default-target=param-property다. 접두사 없는 어노테이션이 어디에 붙는지는
        // 어노테이션의 @Target에 따라 다르다. 파일 위쪽의 세 시료 클래스로 실제 결과를 확인한다.
        private fun violated(target: Any) = validator.validate(target).isNotEmpty()

        @Test
        fun `field 접두사와 get 접두사는 객체 검증에서 걸린다`() {
            assertTrue(violated(FieldPrefixed(null)))
            assertTrue(violated(GetterPrefixed(null)))
        }

        @Test
        fun `이 빌드 설정에서는 접두사 없는 어노테이션도 필드에 붙어 걸린다`() {
            // -Xannotation-default-target=param-property: 파라미터에 붙이고, (Java 제약은 PROPERTY 대상이 아니므로) 필드에도 붙인다.
            // 이 플래그를 빼면 Kotlin 기본값(파라미터가 첫 후보)으로 돌아가 접두사 없는 제약은 파라미터에만 붙고 조용히 무력해진다.
            // 그래서 DiaryCreateRequest는 플래그에 기대지 않고 @field:를 쓴다. 이 시험은 플래그가 지금 어떻게 동작하는지를 고정한다.
            assertTrue(violated(Unprefixed(null)))
            assertTrue(violated(UnprefixedCustom(UUID.randomUUID())))
        }

        @Test
        fun `DiaryCreateRequest의 제약은 모두 필드에 붙어 있다`() {
            val fields = DiaryCreateRequest::class.java.declaredFields.associateBy { it.name }

            assertTrue(fields.getValue("id").annotations.any { it.annotationClass.simpleName == "UuidV7" })
            assertTrue(
                fields
                    .getValue("title")
                    .annotations
                    .map {
                        it.annotationClass.simpleName
                    }.containsAll(listOf("NotNull", "MaxCodePoints", "NoNul")),
            )
            assertTrue(
                fields
                    .getValue("content")
                    .annotations
                    .map {
                        it.annotationClass.simpleName
                    }.containsAll(listOf("NotNull", "NoNul")),
            )
        }
    }
}
