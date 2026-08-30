package com.example.diary

import com.example.diary.diary.DiaryPreviewResponse
import com.example.diary.diary.DiaryService
import com.example.diary.pagination.CursorQuery
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
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
