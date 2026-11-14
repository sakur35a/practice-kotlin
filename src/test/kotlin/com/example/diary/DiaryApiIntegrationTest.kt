package com.example.diary

import com.example.diary.diary.Diary
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// 실제 Tomcat과 실제 PostgreSQL을 띄워서 MockMvc가 보여 주지 못하는 것을 확인한다:
//  - 컨테이너(Tomcat)가 Spring MVC보다 먼저 거절하는 요청과 서블릿 필터의 예외 (RFC 9457 형식인지)
//  - 같은 id를 동시에 쓰는 경쟁 상태 (데이터베이스 기본 키가 가려낸다)
//  - 실제 브라우저 출처(Pages 배포, 미리보기 배포, 로컬 개발)에서의 CORS
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgreSQLTestConfiguration::class, DiaryApiIntegrationTest.FaultFilterConfiguration::class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class DiaryApiIntegrationTest(
    @param:LocalServerPort private val port: Int,
) {
    // 요청 경로가 /__fault이면 서블릿 필터에서 예외를 던진다. Spring MVC 바깥에서 난 예외가 어떻게 나가는지 보려는 시험용이다
    @TestConfiguration(proxyBeanMethods = false)
    class FaultFilterConfiguration {
        @Bean
        fun faultFilter(): FilterRegistrationBean<OncePerRequestFilter> {
            val filter =
                object : OncePerRequestFilter() {
                    override fun doFilterInternal(
                        request: HttpServletRequest,
                        response: HttpServletResponse,
                        filterChain: FilterChain,
                    ) {
                        if (request.requestURI == "/__fault") error("필터 안의 비밀 일기 내용 SELECT")
                        filterChain.doFilter(request, response)
                    }
                }
            return FilterRegistrationBean<OncePerRequestFilter>(filter).apply { order = Int.MIN_VALUE }
        }
    }

    private val client = HttpClient.newHttpClient()
    private val json = JsonMapper()
    private val origins =
        listOf(
            "https://ssobbs13.pp.ua",
            "https://diary-6c6.pages.dev",
            "https://0a1b2c3d.diary-6c6.pages.dev",
            "http://localhost:5173",
        )

    private class Reply(
        val status: Int,
        private val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]?.joinToString(", ")

        fun hasHeader(name: String): Boolean = headers.containsKey(name.lowercase())
    }

    private fun send(
        method: String,
        path: String,
        body: String? = null,
        vararg headers: Pair<String, String>,
    ): Reply {
        val builder = HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
        builder.method(
            method,
            if (body ==
                null
            ) {
                HttpRequest.BodyPublishers.noBody()
            } else {
                HttpRequest.BodyPublishers.ofString(body)
            },
        )
        if (body != null &&
            headers.none { it.first.equals("Content-Type", ignoreCase = true) }
        ) {
            builder.header("Content-Type", "application/json")
        }
        headers.forEach { (name, value) -> builder.header(name, value) }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return Reply(response.statusCode(), response.headers().map().mapKeys { it.key.lowercase() }, response.body())
    }

    // HttpClient가 보낼 수 없는 요청(깨진 퍼센트 인코딩, HTTP가 아닌 요청 줄)을 소켓으로 직접 보낸다
    private fun raw(request: String): Reply {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("localhost", port), 2000)
            socket.soTimeout = 5000
            socket.getOutputStream().write(request.toByteArray())
            val text = socket.getInputStream().readAllBytes().toString(Charsets.UTF_8)
            val head = text.substringBefore("\r\n\r\n")
            val lines = head.split("\r\n")
            val headers =
                lines
                    .drop(
                        1,
                    ).groupBy({ it.substringBefore(':').lowercase() }, { it.substringAfter(':').trim() })
            return Reply(lines.first().split(" ")[1].toInt(), headers, text.substringAfter("\r\n\r\n", ""))
        }
    }

    private fun problem(reply: Reply): JsonNode = json.readTree(reply.body)

    // 어느 경로로 만들어진 오류든 같아야 하는 것: problem+json, 표준 멤버, 브라우저가 읽을 수 있는 CORS, 저장 금지
    private fun assertProblem(
        reply: Reply,
        status: Int,
        title: String,
    ): JsonNode {
        assertEquals(status, reply.status, reply.body)
        assertTrue(reply.header("Content-Type")!!.startsWith("application/problem+json"), reply.header("Content-Type"))
        val body = problem(reply)
        assertEquals(status, body["status"].asInt())
        assertEquals(title, body["title"].asString())
        assertTrue(body.has("detail"))
        assertEquals("*", reply.header("Access-Control-Allow-Origin"))
        assertEquals("Retry-After", reply.header("Access-Control-Expose-Headers"))
        assertEquals("no-store", reply.header("Cache-Control"))
        assertEquals("en", reply.header("Content-Language"))
        assertFalse(reply.hasHeader("ETag"))
        assertFalse(reply.hasHeader("Cloudflare-CDN-Cache-Control"))
        assertFalse(reply.hasHeader("Cache-Tag"))
        return body
    }

    @Nested
    inner class `성공` {
        @Test
        fun `쓴 일기를 읽고 목록 맨 위에서 본다`() {
            val id = Diary(title = "a", content = "b").id
            val created = send("POST", "/diary", """{"id":"$id","title":"첫 일기 😀","content":"내용\n둘째 줄"}""")

            assertEquals(201, created.status)
            assertEquals("/diary/$id", created.header("Location"))
            assertEquals(setOf("id", "title", "content"), problem(created).propertyNames().toSet())
            assertEquals("첫 일기 😀", problem(created)["title"].asString())

            val detail = send("GET", "/diary/$id")
            assertEquals(200, detail.status)
            assertEquals("max-age=86400, immutable", detail.header("Cache-Control"))
            assertEquals("내용\n둘째 줄", problem(detail)["content"].asString())

            val list = send("GET", "/diary?size=100")
            assertEquals(id.toString(), problem(list)["items"][0]["id"].asString())
        }
    }

    @Nested
    inner class `같은 id` {
        @Test
        fun `같은 id로 다시 쓰면 409다`() {
            val id = Diary(title = "a", content = "b").id
            assertEquals(201, send("POST", "/diary", """{"id":"$id","title":"처음","content":"내용"}""").status)

            val second = send("POST", "/diary", """{"id":"$id","title":"덮어쓰기 시도","content":"다른 내용"}""")

            val body = assertProblem(second, 409, "Duplicate diary id")
            assertEquals("https://api.ssobbs13.pp.ua/problems/duplicate-id", body["type"].asString())
            assertEquals("처음", problem(send("GET", "/diary/$id"))["title"].asString()) // 덮어쓰지 않았다
        }

        @Test
        fun `같은 id를 동시에 쓰면 201은 정확히 하나이고 나머지는 409다`() {
            val id = Diary(title = "a", content = "b").id
            val threads = 8
            val ready = CountDownLatch(threads)
            val go = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(threads)

            val futures =
                (1..threads).map { n ->
                    pool.submit<Int> {
                        ready.countDown()
                        go.await()
                        send("POST", "/diary", """{"id":"$id","title":"동시 $n","content":"내용"}""").status
                    }
                }
            ready.await()
            go.countDown()
            val statuses = futures.map { it.get() }
            pool.shutdown()

            // 존재 여부를 미리 조회하면 둘 다 "없다"고 보고 둘 다 저장을 시도한다. 기본 키가 가려내고 그 위반이 409로 나간다
            assertEquals(1, statuses.count { it == 201 }, "상태: $statuses")
            assertEquals(threads - 1, statuses.count { it == 409 }, "상태: $statuses")
            assertEquals(200, send("GET", "/diary/$id").status)
        }
    }

    @Nested
    inner class `브라우저 출처별 CORS (오류 본문도 읽을 수 있어야 한다)` {
        @Test
        fun `프리플라이트는 모든 출처에서 POST와 content-type을 허용한다`() {
            origins.forEach { origin ->
                val reply =
                    send(
                        "OPTIONS",
                        "/diary",
                        null,
                        "Origin" to origin,
                        "Access-Control-Request-Method" to "POST",
                        "Access-Control-Request-Headers" to "content-type",
                    )

                assertEquals(200, reply.status, origin)
                assertEquals("*", reply.header("Access-Control-Allow-Origin"), origin)
                assertEquals("POST", reply.header("Access-Control-Allow-Methods"), origin)
                assertEquals("content-type", reply.header("Access-Control-Allow-Headers"), origin)
            }
        }

        @Test
        fun `검증 실패(422)와 그 밖의 오류 응답에 모든 출처가 읽을 수 있는 CORS 헤더가 있다`() {
            origins.forEach { origin ->
                val validation = send("POST", "/diary", """{"title":null}""", "Origin" to origin)
                assertProblem(validation, 422, "Validation failed")

                val notFound = send("GET", "/diary/${Diary(title = "a", content = "b").id}", null, "Origin" to origin)
                assertProblem(notFound, 404, "Not Found")

                val badParameter = send("GET", "/diary?size=abc", null, "Origin" to origin)
                assertProblem(badParameter, 400, "Malformed request")
            }
        }
    }

    @Nested
    inner class `컨트롤러에 닿기 전과 컨테이너의 오류` {
        @Test
        fun `없는 경로는 404, 지원하지 않는 메서드는 405와 Allow, 잘못된 Content-Type은 415다`() {
            assertProblem(send("GET", "/nope", null, "Origin" to origins[0]), 404, "Not Found")

            val methodNotAllowed = send("PUT", "/diary", "{}", "Origin" to origins[0])
            assertProblem(methodNotAllowed, 405, "Method Not Allowed")
            assertEquals(setOf("GET", "POST"), methodNotAllowed.header("Allow")!!.split(", ").toSet())

            assertProblem(send("POST", "/diary", "x", "Content-Type" to "text/plain"), 415, "Unsupported Media Type")
        }

        @Test
        fun `받을 수 없는 Accept는 406이고 성공 응답용 캐시 헤더가 오류에 남지 않는다`() {
            assertProblem(send("GET", "/diary", null, "Accept" to "text/html"), 406, "Not Acceptable")
        }

        @Test
        fun `쿼리 문자열의 퍼센트 인코딩이 틀리면 400이다`() {
            val body =
                assertProblem(
                    raw("GET /diary?size=% HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"),
                    400,
                    "Malformed request",
                )

            assertEquals("https://api.ssobbs13.pp.ua/problems/malformed-request", body["type"].asString())
        }

        @Test
        fun `Tomcat이 직접 거절하는 요청도 problem+json이다 (경로의 퍼센트 인코딩 오류, HTTP가 아닌 요청, 너무 긴 URI)`() {
            assertProblem(
                raw("GET /diary/%zz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"),
                400,
                "Bad Request",
            )
            assertProblem(raw("GARBAGE\r\n\r\n"), 400, "Bad Request")
            assertProblem(
                raw("GET /diary?x=${"a".repeat(20000)} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"),
                400,
                "Bad Request",
            )
        }

        @Test
        fun `서블릿 필터에서 난 예외는 500 problem+json이고 내부 정보가 없다`() {
            val reply = send("GET", "/__fault", null, "Origin" to origins[1])

            assertProblem(reply, 500, "Internal Server Error")
            assertFalse(reply.body.contains("SELECT"))
            assertFalse(reply.body.contains("IllegalStateException"))
            assertEquals("/__fault", problem(reply)["instance"].asString())
        }

        @Test
        fun `오류 디스패치 주소를 직접 부르면 그냥 없는 경로(404)다`() {
            assertProblem(send("GET", "/error"), 404, "Not Found")
        }
    }

    @Test
    fun `응답이 담는 값이 실제로 있다 (위 시험의 전제)`() {
        assertNotNull(send("GET", "/diary").header("ETag"))
    }
}
