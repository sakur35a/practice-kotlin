package com.example.diary

import com.example.diary.diary.DiaryPreviewResponse
import com.example.diary.diary.DiaryService
import com.example.diary.pagination.CursorQuery
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.resilience.annotation.EnableResilientMethods

@SpringBootApplication
// 재시도가 트랜잭션 바깥에서 돌아야 매 시도마다 새 연결과 새 트랜잭션을 쓴다
@EnableResilientMethods(order = Ordered.LOWEST_PRECEDENCE - 1)
class DiaryApplication {
    @Bean
    fun diaryWarmup(diaryService: DiaryService) =
        SmartInitializingSingleton {
            diaryService.findDiarySlice(CursorQuery()).mapItems(::DiaryPreviewResponse)
        }
}

fun main(args: Array<String>) {
    runApplication<DiaryApplication>(*args)
}
