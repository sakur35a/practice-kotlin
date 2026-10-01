package com.example.diary

import com.example.diary.diary.EdgeCache
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.mock.http.client.MockClientHttpResponse
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EdgeCacheTest {
    private val purgeUrl = "https://api.cloudflare.com/client/v4/zones/zone1/purge_cache"

    @Test
    fun `태그로 purge를 요청한다`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server
            .expect(requestTo(purgeUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer token1"))
            .andExpect(content().json("""{"tags":["diary-list"]}"""))
            .andRespond(withSuccess("""{"success":true}""", MediaType.APPLICATION_JSON))
        val edgeCache = EdgeCache("zone1", "token1", builder.build())

        edgeCache.purgeList()
        edgeCache.close() // 비동기 실행이 끝나길 기다린다

        server.verify()
    }

    @Test
    fun `purge가 실패해도 예외를 던지지 않는다`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(purgeUrl)).andRespond(withServerError())
        val edgeCache = EdgeCache("zone1", "token1", builder.build())

        edgeCache.purgeList()
        edgeCache.close()

        server.verify()
    }

    @Test
    fun `설정이 비어 있으면 호출하지 않는다`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build() // 기대한 요청이 없으므로 호출되면 실패한다
        val edgeCache = EdgeCache("", "", builder.build())

        edgeCache.purgeList()
        edgeCache.close()

        server.verify()
    }

    @Test
    fun `purgeList는 응답을 기다리지 않고 돌아온다`() {
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val factory =
            ClientHttpRequestFactory { uri: URI, method: HttpMethod ->
                object : MockClientHttpRequest(method, uri) {
                    override fun executeInternal() =
                        run {
                            release.await(5, TimeUnit.SECONDS) // Cloudflare가 느린 상황
                            finished.countDown()
                            MockClientHttpResponse(ByteArray(0), HttpStatus.OK)
                        }
                }
            }
        val edgeCache = EdgeCache("zone1", "token1", RestClient.builder().requestFactory(factory).build())

        val startedAt = System.nanoTime()
        edgeCache.purgeList()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(elapsedMs < 1_000, "purgeList가 응답을 기다렸다: ${elapsedMs}ms")
        assertEquals(1, finished.count)
        release.countDown()
        edgeCache.close()
        assertEquals(0, finished.count)
    }
}
