package com.example.diary

import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 서버 쪽 장애를 Slack에 알린다 (4xx 같은 클라이언트 오류는 알리지 않는다. 호출하는 쪽: GlobalExceptionHandler).
//  - 같은 종류(key)는 프로세스마다 한 번만 보낸다. 장애가 이어질 때 알림으로 넘치지 않게 하려는 것이다.
//  - webhook 주소가 비어 있으면 보내지 않는다. 예전에는 비어 있으면 localhost:8080/dummy로 보냈는데,
//    그 주소는 같은 컴퓨터의 다른 앱일 수 있다.
//  - 요청을 처리하는 스레드를 막지 않도록 가상 스레드에서 보내고, 연결과 읽기에 시간 제한을 둔다(EdgeCache와 같다).
//  - 보내는 데 실패해도 예외를 던지지 않는다: Slack 장애가 원래 장애를 증폭시키면 안 된다.
@Component
class SlackNotifier internal constructor(
    private val properties: SlackProperties,
    private val http: RestClient,
) {
    @Autowired
    constructor(properties: SlackProperties) : this(
        properties,
        RestClient
            .builder()
            .requestFactory(
                JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build())
                    .apply { setReadTimeout(Duration.ofSeconds(8)) },
            ).build(),
    )

    private val notifiedKeys = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    fun notifyOnce(
        key: String,
        message: String,
    ) {
        if (properties.webhookUrl.isBlank()) return
        if (!notifiedKeys.add(key)) return

        executor.execute {
            try {
                http
                    .post()
                    .uri(properties.webhookUrl)
                    .body(mapOf("text" to message))
                    .retrieve()
                    .toBodilessEntity()
            } catch (_: Exception) {
                // 알림 실패는 삼킨다
            }
        }
    }

    // 종료나 재배포 때 진행 중인 전송을 잠깐 기다린다
    @PreDestroy
    fun close() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
