package com.example.diary

import com.example.diary.diary.Diary
import com.example.diary.diary.DiaryRepository
import com.example.diary.diary.DiaryService
import org.junit.jupiter.api.Test
import org.springframework.boot.jooq.test.autoconfigure.JooqTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import kotlin.test.assertEquals

@JooqTest
@Import(
    DiaryService::class,
    DiaryRepository::class,
    PostgresTestConfiguration::class,
)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class DiaryServiceIntegrationTest(
    private val diaryService: DiaryService,
) {
    @Test
    fun `일기를 저장하고 조회한다`() {
        val diary =
            Diary(
                title = "제목",
                content = "내용",
            )

        diaryService.createDiary(diary)

        val result = diaryService.findAll()

        assertEquals(listOf(diary), result)
    }
}
