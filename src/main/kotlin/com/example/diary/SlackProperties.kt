package com.example.diary

import org.springframework.boot.context.properties.ConfigurationProperties

// Slack 알림 설정. 값은 환경변수 SLACK_WEBHOOK_URL에서 온다 (application.yaml). 비어 있으면 알림을 보내지 않는다.
// webhook 주소는 비밀이라서 toString에 드러나지 않게 한다 (실수로 로그에 찍혀도 새지 않는다).
@ConfigurationProperties("slack")
data class SlackProperties(
    val webhookUrl: String = "",
) {
    override fun toString(): String = "SlackProperties(webhookUrl=***)"
}
