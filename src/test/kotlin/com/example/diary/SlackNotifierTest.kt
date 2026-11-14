package com.example.diary

import com.example.diary.diary.DiaryProperties
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertFalse

// 전송은 가상 스레드에서 비동기로 일어나므로, 시험은 close()로 진행 중인 전송이 끝나기를 기다린 뒤 서버 목을 확인한다 (EdgeCacheTest와 같다)
class SlackNotifierTest {
    private val webhookUrl = "https://hooks.example.test/diary"

    private fun newNotifier(
        url: String,
        builder: RestClient.Builder,
    ) = SlackNotifier(SlackProperties(url), builder.build())

    @Test
    fun `같은 종류의 장애는 한 번만 보낸다`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server
            .expect(ExpectedCount.once(), requestTo(webhookUrl))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().json("""{"text":"첫 장애"}"""))
            .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN))
        val notifier = newNotifier(webhookUrl, builder)

        notifier.notifyOnce("server-fault", "첫 장애")
        notifier.notifyOnce("server-fault", "두 번째 장애") // 이미 알렸으므로 요청이 없어야 한다 (예상 밖 요청이면 서버 목이 실패한다)
        notifier.close()

        server.verify()
    }

    @Test
    fun `종류가 다르면 각각 한 번씩 보낸다`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(ExpectedCount.times(2), requestTo(webhookUrl)).andRespond(withSuccess("ok", MediaType.TEXT_PLAIN))
        val notifier = newNotifier(webhookUrl, builder)

        notifier.notifyOnce("database-unavailable", "DB 장애")
        notifier.notifyOnce("server-fault", "서버 결함")
        notifier.notifyOnce("database-unavailable", "DB 장애 계속")
        notifier.close()

        server.verify()
    }

    @Test
    fun `webhook 주소가 비어 있으면 보내지 않는다 (옛 기본값 localhost 주소로도 보내지 않는다)`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build() // 기대한 요청이 없으므로 호출되면 실패한다
        val notifier = newNotifier("", builder)

        notifier.notifyOnce("server-fault", "장애")
        notifier.close()

        server.verify()
    }

    @Test
    fun `Slack이 실패해도 예외를 던지지 않고, 실패한 뒤에도 다시 보내지 않는다`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(ExpectedCount.once(), requestTo(webhookUrl)).andRespond(withServerError())
        val notifier = newNotifier(webhookUrl, builder)

        notifier.notifyOnce("server-fault", "장애")
        notifier.notifyOnce("server-fault", "장애 계속")
        notifier.close()

        server.verify()
    }

    @Test
    fun `동시에 여러 요청이 실패해도 한 번만 보낸다`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server
            .expect(ExpectedCount.once(), requestTo(webhookUrl))
            .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN))
        val notifier = newNotifier(webhookUrl, builder)
        val threads = 8
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)

        val futures =
            (1..threads).map {
                pool.submit {
                    ready.countDown()
                    go.await()
                    notifier.notifyOnce("server-fault", "동시 장애")
                }
            }
        ready.await()
        go.countDown()
        futures.forEach { it.get() }
        pool.shutdown()
        notifier.close()

        server.verify()
    }

    @Test
    fun `설정 값은 toString에 드러나지 않는다`() {
        val text = SlackProperties(webhookUrl).toString() + DiaryProperties.Cloudflare("zone", "secret-token")

        assertFalse(text.contains(webhookUrl))
        assertFalse(text.contains("secret-token"))
    }
}
