package com.example.diary

import com.example.diary.diary.Diary
import com.example.diary.diary.DiaryRepository
import com.example.diary.diary.DiaryService
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.jooq.test.autoconfigure.JooqTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DuplicateKeyException
import org.springframework.test.context.TestConstructor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    @Nested
    inner class `성공` {
        @Test
        fun `일기를 저장하고 조회한다`() {
            val diary =
                Diary(
                    title = "제목",
                    content = "내용",
                )

            diaryService.createDiary(diary)

            val result = diaryService.findById(diary.id)

            assertEquals(diary, result)
        }
    }

    @Nested
    inner class `경계` {
        @Test
        fun `존재하지 않는 일기를 조회하면 null을 반환한다`() {
            val absentId = UUID.fromString("00000000-0000-4000-8000-000000000000")

            assertNull(diaryService.findById(absentId))
        }

        @Test
        fun `저장된 일기가 없으면 빈 목록을 반환한다`() {
            assertEquals(emptyList(), diaryService.findAll())
        }
    }

    @Nested
    inner class `실패` {
        @Test
        fun `이미 존재하는 ID로 저장하면 실패한다`() {
            val diary = Diary(title = "제목", content = "내용")

            diaryService.createDiary(diary)

            assertFailsWith<DuplicateKeyException> {
                diaryService.createDiary(diary)
            }
        }
    }
}
