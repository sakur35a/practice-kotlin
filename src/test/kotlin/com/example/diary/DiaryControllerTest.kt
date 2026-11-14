package com.example.diary

import com.example.diary.diary.Diary
import com.example.diary.diary.DiaryController
import com.example.diary.diary.DiaryResponse
import com.example.diary.diary.DiaryService
import com.example.diary.diary.DuplicateDiaryIdException
import com.example.diary.diary.EdgeCache
import com.example.diary.pagination.CursorQuery
import com.example.diary.pagination.CursorSlice
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionSystemException
import java.sql.SQLException
import java.util.UUID

// API의 JSON 모양, 캐시/CORS 헤더, 오류 계약(RFC 9457)이 엔티티가 아니라 컨트롤러와 DTO와 예외 핸들러로 정해지는지 확인한다.
// Cloudflare Pages 프런트와 엣지 캐시가 이 응답에 기대고 있으므로, 여기 단언을 바꾸는 것은 API를 바꾸는 일이다 (deploy/README.md 7절).
// 서블릿 컨테이너 쪽(Tomcat 오류 보고, 필터 예외, /error)과 실제 DB가 필요한 경쟁 상태는 DiaryApiIntegrationTest가 다룬다.
@WebMvcTest(DiaryController::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class DiaryControllerTest(
    private val mvc: MockMvcTester,
) {
    @MockitoBean
    lateinit var diaryService: DiaryService

    @MockitoBean
    lateinit var edgeCache: EdgeCache

    // 5xx 시험이 실제 Slack으로 요청을 보내지 않게 하고, 알림이 나가는 조건을 확인한다
    @MockitoBean
    lateinit var slackNotifier: SlackNotifier

    private val frontOrigin = "https://diary-6c6.pages.dev"
    private val validationType = "https://api.ssobbs13.pp.ua/problems/validation-error"
    private val malformedType = "https://api.ssobbs13.pp.ua/problems/malformed-request"
    private val duplicateType = "https://api.ssobbs13.pp.ua/problems/duplicate-id"

    private fun newDiary(content: String = "내용") = Diary(title = "제목", content = content)

    private fun slice(
        vararg diaries: Diary,
        hasNext: Boolean = false,
    ) = CursorSlice(
        items = diaries.toList(),
        hasNext = hasNext,
        nextCursorId = if (hasNext) diaries.last().id else null,
    )

    private fun createRequest(json: String) =
        mvc
            .post()
            .uri("/diary")
            .header("Origin", frontOrigin)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)

    // 모든 오류 응답이 지켜야 하는 것: 상태, problem+json, 표준 멤버(status, title, instance, type), 공통 헤더
    private fun assertProblem(
        result: MvcTestResult,
        status: HttpStatus,
        title: String,
        instance: String,
        type: String? = null,
    ) {
        assertThat(result).hasStatus(status).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status.value())
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo(title)
        assertThat(result).bodyJson().extractingPath("$.instance").isEqualTo(instance)
        assertThat(result).bodyJson().hasPath("$.detail")
        if (type == null) {
            // type이 없으면 about:blank다 (RFC 9457 3.1.1). 상태 코드만으로 뜻이 다 전해지는 오류다
            assertThat(result).bodyJson().doesNotHavePath("$.type")
            assertThat(result).bodyJson().doesNotHavePath("$.errors")
        } else {
            assertThat(result).bodyJson().extractingPath("$.type").isEqualTo(type)
        }
        assertThat(result)
            .hasHeader("Access-Control-Allow-Origin", "*")
            .hasHeader("Access-Control-Expose-Headers", "Retry-After")
            .hasHeader("Cache-Control", "no-store")
            .hasHeader("Content-Language", "en")
            .headers()
            .doesNotContainHeader("Cloudflare-CDN-Cache-Control")
            .doesNotContainHeader("Cache-Tag")
            .doesNotContainHeader("ETag")
    }

    @Nested
    inner class `성공` {
        @Test
        fun `일기를 만들면 201과 id, title, content만 돌려준다`() {
            val diary = newDiary()
            whenever(diaryService.createDiary(anyOrNull(), any(), any())).thenReturn(diary)

            val created = createRequest("""{"title":"제목","content":"내용"}""").exchange()

            assertThat(created).hasStatus(HttpStatus.CREATED).hasHeader("Location", "/diary/${diary.id}")
            assertThat(
                created,
            ).bodyJson().convertTo(DiaryResponse::class.java).isEqualTo(DiaryResponse(diary.id, "제목", "내용"))
            assertThat(created).bodyJson().doesNotHavePath("$.createdAt")
            assertThat(created).hasHeader("Access-Control-Allow-Origin", "*")
        }

        @Test
        fun `일기 한 건 조회 응답에는 엔티티 내부 필드가 없다`() {
            val diary = newDiary()
            whenever(diaryService.findById(diary.id)).thenReturn(diary)

            val found = mvc.get().uri("/diary/${diary.id}").exchange()

            assertThat(found).hasStatusOk()
            assertThat(found).bodyJson().convertTo(DiaryResponse::class.java).isEqualTo(DiaryResponse(diary))
            assertThat(found).bodyJson().doesNotHavePath("$.new")
            assertThat(found).bodyJson().doesNotHavePath("$.createdAt")
        }

        @Test
        fun `목록은 열 글자 미리보기와 커서를 돌려준다`() {
            val diary = newDiary("12345678901")
            whenever(diaryService.findDiarySlice(any<CursorQuery>())).thenReturn(slice(diary, hasNext = true))

            val list = mvc.get().uri("/diary").exchange()

            assertThat(list).hasStatusOk()
            assertThat(list).bodyJson().extractingPath("$.items[0].content").isEqualTo("1234567890...")
            assertThat(list).bodyJson().extractingPath("$.hasNext").isEqualTo(true)
            assertThat(list).bodyJson().extractingPath("$.nextCursorId").isEqualTo(diary.id.toString())
        }

        @Test
        fun `목록은 캐시 헤더와 ETag를 주고, 같은 ETag로 다시 물으면 304다`() {
            whenever(diaryService.findDiarySlice(any<CursorQuery>())).thenReturn(slice(newDiary()))

            val first = mvc.get().uri("/diary").exchange()
            assertThat(first)
                .hasStatusOk()
                .hasHeader("Cache-Control", "no-cache")
                .hasHeader("Cloudflare-CDN-Cache-Control", "max-age=300")
                .hasHeader("Cache-Tag", EdgeCache.LIST_TAG)
                .hasHeader("Access-Control-Allow-Origin", "*")
            val etag = first.response.getHeader("ETag")!!

            assertThat(mvc.get().uri("/diary").header("If-None-Match", etag)).hasStatus(HttpStatus.NOT_MODIFIED)
        }

        @Test
        fun `상세는 브라우저가 서버에 다시 묻지 않게 오래 캐시한다`() {
            val diary = newDiary()
            whenever(diaryService.findById(diary.id)).thenReturn(diary)

            assertThat(mvc.get().uri("/diary/${diary.id}"))
                .hasStatusOk()
                .hasHeader("Cache-Control", "max-age=86400, immutable")
                .hasHeader("Cloudflare-CDN-Cache-Control", "max-age=86400")
        }

        @Test
        fun `목록의 첫 페이지는 매번 확인하고, 커서로 가는 이전 페이지는 오래 캐시한다`() {
            val diary = newDiary()
            whenever(diaryService.findDiarySlice(any<CursorQuery>())).thenReturn(slice(diary))

            assertThat(mvc.get().uri("/diary")).hasHeader("Cache-Control", "no-cache")
            assertThat(mvc.get().uri("/diary?cursorId=${diary.id}"))
                .hasHeader("Cache-Control", "max-age=86400, immutable")
                .hasHeader("Cloudflare-CDN-Cache-Control", "max-age=300")
                .hasHeader("Cache-Tag", EdgeCache.LIST_TAG)
        }

        @Test
        fun `size는 1부터 100까지 받고 생략하면 20이다`() {
            whenever(diaryService.findDiarySlice(any<CursorQuery>())).thenReturn(slice())

            assertThat(mvc.get().uri("/diary?size=1")).hasStatusOk()
            assertThat(mvc.get().uri("/diary?size=100")).hasStatusOk()
            assertThat(mvc.get().uri("/diary")).hasStatusOk()

            verify(diaryService).findDiarySlice(CursorQuery(size = 1))
            verify(diaryService).findDiarySlice(CursorQuery(size = 100))
            verify(diaryService).findDiarySlice(CursorQuery(size = 20))
        }

        @Test
        fun `글을 쓰면 엣지 목록 purge를 요청한다`() {
            whenever(diaryService.createDiary(anyOrNull(), any(), any())).thenReturn(newDiary())

            assertThat(createRequest("""{"title":"제목","content":"내용"}""")).hasStatus(HttpStatus.CREATED)

            verify(edgeCache, times(1)).purgeList()
        }

        @Test
        fun `프런트의 프리플라이트는 POST와 content-type을 허용한다`() {
            assertThat(
                mvc
                    .options()
                    .uri("/diary")
                    .header("Origin", frontOrigin)
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "content-type"),
            ).hasStatusOk()
                .hasHeader("Access-Control-Allow-Origin", "*")
                .hasHeader("Access-Control-Allow-Methods", "POST")
                .hasHeader("Access-Control-Allow-Headers", "content-type")
                .hasHeader("Access-Control-Max-Age", "1800")
        }
    }

    @Nested
    inner class `경계` {
        @Test
        fun `제목은 코드 포인트로 255자까지 받는다 (이모지는 UTF-16으로 두 칸이다)`() {
            whenever(diaryService.createDiary(anyOrNull(), any(), any())).thenReturn(newDiary())

            assertThat(
                createRequest("""{"title":"${"😀".repeat(255)}","content":"내용"}"""),
            ).hasStatus(HttpStatus.CREATED)
        }

        @Test
        fun `빈 제목과 빈 본문도 도메인이 받으므로 받는다`() {
            whenever(diaryService.createDiary(anyOrNull(), any(), any())).thenReturn(newDiary())

            assertThat(createRequest("""{"title":"","content":""}""")).hasStatus(HttpStatus.CREATED)
        }

        @Test
        fun `id를 주지 않거나 null이면 서버가 만든다`() {
            whenever(diaryService.createDiary(anyOrNull(), any(), any())).thenReturn(newDiary())

            assertThat(createRequest("""{"title":"제목","content":"내용"}""")).hasStatus(HttpStatus.CREATED)
            assertThat(createRequest("""{"id":null,"title":"제목","content":"내용"}""")).hasStatus(HttpStatus.CREATED)

            verify(diaryService, times(2)).createDiary(isNull(), any(), any())
        }

        @Test
        fun `UUID v7이 아닌 id가 경로에 오면 오류가 아니라 없는 일기(404)다`() {
            whenever(diaryService.findById(any())).thenReturn(null)
            val v4 = UUID.randomUUID() // 버전 4. 도메인이 만든 적 없는 id라서 "없다"가 맞는 답이다

            assertProblem(mvc.get().uri("/diary/$v4").exchange(), HttpStatus.NOT_FOUND, "Not Found", "/diary/$v4")
        }
    }

    @Nested
    inner class `실패 - 요청을 해석할 수 없다 (400 malformed-request)` {
        @Test
        fun `깨진 JSON은 400이다`() {
            val result = createRequest("{").exchange()

            assertProblem(result, HttpStatus.BAD_REQUEST, "Malformed request", "/diary", malformedType)
            assertThat(
                result,
            ).bodyJson()
                .extractingPath(
                    "$.detail",
                ).isEqualTo("The request body could not be read as the expected JSON document.")
            assertThat(result).bodyJson().doesNotHavePath("$.errors")
            verifyNoInteractions(diaryService)
        }

        @Test
        fun `본문이 없으면 400이다`() {
            assertProblem(
                mvc
                    .post()
                    .uri(
                        "/diary",
                    ).header("Origin", frontOrigin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .exchange(),
                HttpStatus.BAD_REQUEST,
                "Malformed request",
                "/diary",
                malformedType,
            )
        }

        @Test
        fun `필드 타입이 틀리면 400이고 어느 필드인지(JSON Pointer)만 알려 준다`() {
            val result = createRequest("""{"title":{"a":"비밀 일기 내용"},"content":"내용"}""").exchange()

            assertProblem(result, HttpStatus.BAD_REQUEST, "Malformed request", "/diary", malformedType)
            assertThat(result).bodyJson().extractingPath("$.errors[0].pointer").isEqualTo("/title")
            assertThat(result).bodyJson().extractingPath("$.errors[0].code").isEqualTo("invalid-value")
            assertThat(result.response.contentAsString).doesNotContain("비밀 일기 내용")
        }

        @Test
        fun `id가 UUID 형식이 아니면 400이다 (형식 오류는 제약 위반이 아니다)`() {
            val result = createRequest("""{"id":"abc","title":"제목","content":"내용"}""").exchange()

            assertProblem(result, HttpStatus.BAD_REQUEST, "Malformed request", "/diary", malformedType)
            assertThat(result).bodyJson().extractingPath("$.errors[0].pointer").isEqualTo("/id")
        }

        @Test
        fun `숫자가 아닌 size는 400이고 어느 파라미터인지 알려 준다`() {
            val result =
                mvc
                    .get()
                    .uri("/diary?size=abc")
                    .header("Origin", frontOrigin)
                    .exchange()

            assertProblem(result, HttpStatus.BAD_REQUEST, "Malformed request", "/diary", malformedType)
            assertThat(result).bodyJson().extractingPath("$.errors[0].parameter").isEqualTo("size")
            assertThat(result).bodyJson().extractingPath("$.errors[0].code").isEqualTo("invalid-format")
            assertThat(result).bodyJson().doesNotHavePath("$.errors[0].pointer")
        }

        @Test
        fun `형식이 틀린 cursorId와 경로의 id는 400이다`() {
            val cursor =
                mvc
                    .get()
                    .uri("/diary?cursorId=abc")
                    .header("Origin", frontOrigin)
                    .exchange()
            assertProblem(cursor, HttpStatus.BAD_REQUEST, "Malformed request", "/diary", malformedType)
            assertThat(cursor).bodyJson().extractingPath("$.errors[0].parameter").isEqualTo("cursorId")

            val path =
                mvc
                    .get()
                    .uri("/diary/abc")
                    .header("Origin", frontOrigin)
                    .exchange()
            assertProblem(path, HttpStatus.BAD_REQUEST, "Malformed request", "/diary/abc", malformedType)
            assertThat(path).bodyJson().extractingPath("$.errors[0].parameter").isEqualTo("id")
            verifyNoInteractions(diaryService)
        }
    }

    @Nested
    inner class `실패 - 해석은 되지만 제약을 어긴다` {
        @Test
        fun `본문이 제약을 어기면 422이고 errors에 pointer와 code가 있다`() {
            val result =
                createRequest(
                    """{"id":"${UUID.randomUUID()}","title":"${"가".repeat(256)}","content":null}""",
                ).exchange()

            assertProblem(result, HttpStatus.UNPROCESSABLE_CONTENT, "Validation failed", "/diary", validationType)
            assertThat(
                result,
            ).bodyJson()
                .extractingPath(
                    "$.detail",
                ).isEqualTo("The request violates one or more constraints. See the errors member.")
            // 정렬되어 있다: pointer 순서 /content, /id, /title
            assertThat(result).bodyJson().extractingPath("$.errors.length()").isEqualTo(3)
            assertThat(result).bodyJson().extractingPath("$.errors[0]").isEqualTo(
                mapOf("code" to "required", "detail" to "is required", "pointer" to "/content"),
            )
            assertThat(result).bodyJson().extractingPath("$.errors[1]").isEqualTo(
                mapOf("code" to "not-uuid-v7", "detail" to "must be a UUID version 7", "pointer" to "/id"),
            )
            assertThat(result).bodyJson().extractingPath("$.errors[2]").isEqualTo(
                mapOf("code" to "too-long", "detail" to "must be at most 255 characters", "pointer" to "/title"),
            )
        }

        @Test
        fun `title과 content가 없으면 422다 (필수 필드)`() {
            val result = createRequest("{}").exchange()

            assertProblem(result, HttpStatus.UNPROCESSABLE_CONTENT, "Validation failed", "/diary", validationType)
            assertThat(result).bodyJson().extractingPath("$.errors[0].pointer").isEqualTo("/content")
            assertThat(result).bodyJson().extractingPath("$.errors[1].pointer").isEqualTo("/title")
        }

        @Test
        fun `NUL 문자가 든 제목과 본문은 422다 (PostgreSQL이 저장하지 못한다)`() {
            val result = createRequest("""{"title":"a\u0000b","content":"c\u0000d"}""").exchange()

            assertProblem(result, HttpStatus.UNPROCESSABLE_CONTENT, "Validation failed", "/diary", validationType)
            assertThat(result).bodyJson().extractingPath("$.errors[0]").isEqualTo(
                mapOf(
                    "code" to "nul-character",
                    "detail" to "must not contain the NUL character (U+0000)",
                    "pointer" to "/content",
                ),
            )
            assertThat(result).bodyJson().extractingPath("$.errors[1].pointer").isEqualTo("/title")
        }

        @Test
        fun `제약 위반은 서비스에 닿지 않고 purge도 알림도 없다`() {
            assertThat(
                createRequest("""{"id":"${UUID.randomUUID()}","title":"제목","content":"내용"}"""),
            ).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
            assertThat(mvc.get().uri("/diary?size=0")).hasStatus(HttpStatus.BAD_REQUEST)

            verifyNoInteractions(diaryService)
            verify(edgeCache, never()).purgeList()
            verifyNoInteractions(slackNotifier)
        }

        @Test
        fun `범위 밖 size는 400 validation-error이고 parameter와 code가 있다 (대상 URI의 문제라서 422가 아니다)`() {
            val small =
                mvc
                    .get()
                    .uri("/diary?size=0")
                    .header("Origin", frontOrigin)
                    .exchange()
            assertProblem(small, HttpStatus.BAD_REQUEST, "Validation failed", "/diary", validationType)
            assertThat(small).bodyJson().extractingPath("$.errors[0]").isEqualTo(
                mapOf("code" to "too-small", "detail" to "must be at least 1", "parameter" to "size"),
            )

            val large =
                mvc
                    .get()
                    .uri("/diary?size=101")
                    .header("Origin", frontOrigin)
                    .exchange()
            assertProblem(large, HttpStatus.BAD_REQUEST, "Validation failed", "/diary", validationType)
            assertThat(large).bodyJson().extractingPath("$.errors[0]").isEqualTo(
                mapOf("code" to "too-large", "detail" to "must be at most 100", "parameter" to "size"),
            )
        }

        @Test
        fun `오류 문구는 Accept-Language와 상관없이 영어다`() {
            val result =
                mvc
                    .get()
                    .uri("/diary?size=0")
                    .header("Accept-Language", "ko-KR")
                    .exchange()

            assertThat(result).bodyJson().extractingPath("$.errors[0].detail").isEqualTo("must be at least 1")
            assertThat(result).hasHeader("Content-Language", "en")
        }
    }

    @Nested
    inner class `실패 - 컨트롤러에 닿기 전의 오류와 그 밖의 표준 상태` {
        @Test
        fun `없는 경로는 404다`() {
            assertProblem(
                mvc
                    .get()
                    .uri("/nope")
                    .header("Origin", frontOrigin)
                    .exchange(),
                HttpStatus.NOT_FOUND,
                "Not Found",
                "/nope",
            )
        }

        @Test
        fun `없는 일기는 404다`() {
            whenever(diaryService.findById(any())).thenReturn(null)
            val id = UUID.randomUUID()

            val result =
                mvc
                    .get()
                    .uri("/diary/$id")
                    .header("Origin", frontOrigin)
                    .exchange()

            assertProblem(result, HttpStatus.NOT_FOUND, "Not Found", "/diary/$id")
            assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("No diary exists with this id.")
        }

        @Test
        fun `지원하지 않는 메서드는 405이고 Allow 헤더가 있다`() {
            val result =
                mvc
                    .put()
                    .uri("/diary")
                    .header("Origin", frontOrigin)
                    .exchange()

            assertProblem(result, HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed", "/diary")
            // 순서는 구현이 정하지 않는다(Set). RFC 9110 15.5.6은 405에 Allow를 요구한다
            assertThat(result.response.getHeader("Allow")!!.split(", ")).containsExactlyInAnyOrder("GET", "POST")
        }

        @Test
        fun `지원하지 않는 Content-Type은 415다`() {
            val result =
                mvc
                    .post()
                    .uri(
                        "/diary",
                    ).header("Origin", frontOrigin)
                    .contentType(MediaType.TEXT_PLAIN)
                    .content("x")
                    .exchange()

            assertProblem(result, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type", "/diary")
        }

        @Test
        fun `받을 수 없는 Accept는 406이고, 컨트롤러가 채운 성공용 캐시 헤더가 오류에 남지 않는다`() {
            whenever(diaryService.findDiarySlice(any<CursorQuery>())).thenReturn(slice(newDiary()))

            val result =
                mvc
                    .get()
                    .uri("/diary")
                    .header("Accept", "text/html")
                    .header("Origin", frontOrigin)
                    .exchange()

            // assertProblem이 Cloudflare-CDN-Cache-Control, Cache-Tag, ETag가 없음을 함께 확인한다
            assertProblem(result, HttpStatus.NOT_ACCEPTABLE, "Not Acceptable", "/diary")
        }

        @Test
        fun `같은 id로 만들면 409 duplicate-id이고 purge하지 않는다`() {
            whenever(diaryService.createDiary(anyOrNull(), any(), any()))
                .thenThrow(DuplicateDiaryIdException(UUID.randomUUID(), RuntimeException("23505")))

            val result = createRequest("""{"title":"제목","content":"내용"}""").exchange()

            assertProblem(result, HttpStatus.CONFLICT, "Duplicate diary id", "/diary", duplicateType)
            assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("A diary with this id already exists.")
            verify(edgeCache, never()).purgeList()
            verifyNoInteractions(slackNotifier)
        }
    }

    @Nested
    inner class `실패 - 서버 쪽` {
        @Test
        fun `데이터베이스를 쓸 수 없으면 503이고 Retry-After가 있다`() {
            listOf(
                DataAccessResourceFailureException("연결 실패: 비밀 일기 내용"),
                CannotCreateTransactionException("트랜잭션을 열지 못했다"),
                // 롤백 실패가 원래 예외를 덮는 경우: 원인 사슬의 SQLState 08xxx로 알아본다
                TransactionSystemException("롤백 실패", SQLException("An I/O error occurred", "08006")),
            ).forEach { failure ->
                whenever(diaryService.findById(any())).thenThrow(failure)
                val id = UUID.randomUUID()

                val result =
                    mvc
                        .get()
                        .uri("/diary/$id")
                        .header("Origin", frontOrigin)
                        .exchange()

                assertProblem(result, HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", "/diary/$id")
                assertThat(result).hasHeader("Retry-After", "5")
                assertThat(
                    result,
                ).bodyJson()
                    .extractingPath(
                        "$.detail",
                    ).isEqualTo("The database is temporarily unavailable. Try again later.")
                assertThat(result.response.contentAsString).doesNotContain("비밀 일기 내용").doesNotContain("Exception")
            }
        }

        @Test
        fun `쓰기 중 데이터베이스를 쓸 수 없어도 503이고 purge하지 않는다`() {
            whenever(
                diaryService.createDiary(anyOrNull(), any(), any()),
            ).thenThrow(CannotCreateTransactionException("x"))

            assertProblem(
                createRequest("""{"title":"제목","content":"내용"}""").exchange(),
                HttpStatus.SERVICE_UNAVAILABLE,
                "Service Unavailable",
                "/diary",
            )
            verify(edgeCache, never()).purgeList()
        }

        @Test
        fun `데이터베이스 장애(503)는 종류별로 한 번 알린다`() {
            whenever(diaryService.findById(any())).thenThrow(CannotCreateTransactionException("x"))

            assertThat(mvc.get().uri("/diary/${UUID.randomUUID()}")).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)

            verify(slackNotifier).notifyOnce("database-unavailable", "diary API: database unavailable (503)")
        }

        @Test
        fun `예상하지 못한 예외는 500이고, 내부 정보나 일기 내용을 응답에 싣지 않는다`() {
            whenever(diaryService.findById(any()))
                .thenThrow(IllegalStateException("org.example.InternalClass SELECT content FROM diaries 비밀 일기 내용"))
            val id = UUID.randomUUID()

            val result =
                mvc
                    .get()
                    .uri("/diary/$id")
                    .header("Origin", frontOrigin)
                    .exchange()

            assertProblem(result, HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "/diary/$id")
            assertThat(
                result,
            ).bodyJson().extractingPath("$.detail").isEqualTo("The server encountered an unexpected error.")
            assertThat(result.response.contentAsString)
                .doesNotContain("InternalClass")
                .doesNotContain("SELECT")
                .doesNotContain("비밀 일기 내용")
                .doesNotContain("IllegalStateException")
            verify(slackNotifier).notifyOnce("server-fault", "diary API: unexpected server error (500)")
        }

        @Test
        fun `도메인 불변식을 어긴 IllegalArgumentException이 새어 나오면 클라이언트 오류가 아니라 서버 결함(500)이다`() {
            // 가장자리 검증이 도메인과 같은 규칙이라면 여기까지 올 수 없다. 오면 검증이 어긋난 것이므로 조용히 400으로 돌리지 않는다
            whenever(
                diaryService.createDiary(anyOrNull(), any(), any()),
            ).thenThrow(IllegalArgumentException("Diary id는 UUID v7이어야 합니다: 4"))

            assertProblem(
                createRequest("""{"title":"제목","content":"내용"}""").exchange(),
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "/diary",
            )
            verify(edgeCache, never()).purgeList()
        }

        @Test
        fun `4xx는 Slack으로 알리지 않는다`() {
            createRequest("{").exchange()
            createRequest("{}").exchange()
            mvc.get().uri("/nope").exchange()
            mvc.put().uri("/diary").exchange()

            verifyNoInteractions(slackNotifier)
        }
    }
}
