package com.example.diary

import com.example.diary.diary.DiaryPreviewResponse
import com.example.diary.diary.DiaryService
import com.example.diary.pagination.CursorQuery
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// 기동 직후 첫 요청이 느리지 않도록 목록 조회 경로를 한 번 지나간다.
// 별도 설정 클래스라서 웹 슬라이스 테스트에는 로딩되지 않는다.
// DB 없이 앱을 한 번 띄우는 AOT 캐시 훈련 실행에서는 diary.warmup.enabled=false로 끈다.
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "diary.warmup", name = ["enabled"], matchIfMissing = true)
class DiaryWarmup {
    @Bean
    fun diaryWarmupRunner(diaryService: DiaryService) =
        SmartInitializingSingleton {
            diaryService.findDiarySlice(CursorQuery()).mapItems(::DiaryPreviewResponse)
        }
}
