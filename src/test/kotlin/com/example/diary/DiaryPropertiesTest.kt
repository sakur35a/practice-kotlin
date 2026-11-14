package com.example.diary

import com.example.diary.diary.DiaryProperties
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import kotlin.test.assertEquals

// application.yaml의 키(slack.webhook-url, diary.cloudflare.zone-id/api-token)가 설정 클래스에 묶이는지 확인한다.
// 환경변수(SLACK_WEBHOOK_URL, CLOUDFLARE_ZONE_ID, CLOUDFLARE_API_TOKEN)는 application.yaml이 이 키로 옮긴다.
class DiaryPropertiesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SlackProperties::class, DiaryProperties::class)
    class PropertiesConfiguration

    private val runner = ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration::class.java)

    @Test
    fun `설정 값이 있으면 그대로 묶인다`() {
        runner
            .withPropertyValues(
                "slack.webhook-url=https://hooks.example.test/diary",
                "diary.cloudflare.zone-id=zone1",
                "diary.cloudflare.api-token=token1",
            ).run { context ->
                assertEquals(
                    "https://hooks.example.test/diary",
                    context.getBean(SlackProperties::class.java).webhookUrl,
                )
                assertEquals(
                    DiaryProperties.Cloudflare("zone1", "token1"),
                    context.getBean(DiaryProperties::class.java).cloudflare,
                )
            }
    }

    @Test
    fun `Slack 주소가 비어 있으면 빈 문자열이다 (알림을 보내지 않는다는 뜻)`() {
        runner.withPropertyValues("slack.webhook-url=").run { context ->
            assertEquals("", context.getBean(SlackProperties::class.java).webhookUrl)
        }
    }

    @Test
    fun `Cloudflare 설정이 없으면 비어 있어서 purge를 건너뛴다`() {
        runner.withPropertyValues("slack.webhook-url=https://hooks.example.test/diary").run { context ->
            assertEquals(DiaryProperties.Cloudflare("", ""), context.getBean(DiaryProperties::class.java).cloudflare)
        }
    }
}
