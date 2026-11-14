package com.example.diary.diary

import org.springframework.boot.context.properties.ConfigurationProperties

// diary.* 설정. diary.warmup.enabled는 빈을 만들지 말지 정하는 조건이라 DiaryWarmup의 @ConditionalOnProperty가 직접 읽는다.
@ConfigurationProperties("diary")
data class DiaryProperties(
    val cloudflare: Cloudflare = Cloudflare(),
) {
    // 글이 써지면 엣지에 캐시된 목록을 purge한다. 둘 중 하나라도 비어 있으면 건너뛴다.
    data class Cloudflare(
        val zoneId: String = "",
        val apiToken: String = "",
    ) {
        override fun toString(): String = "Cloudflare(zoneId=$zoneId, apiToken=***)"
    }
}
