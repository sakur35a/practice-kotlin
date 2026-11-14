package com.example.diary

import com.example.diary.diary.Diary
import com.example.diary.diary.DiaryPreviewResponse
import com.example.diary.pagination.CursorSlice
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DiaryDomainTest {
    @Nested
    inner class `성공` {
        @Test
        fun `생성한 일기 ID는 UUID v7이다`() {
            assertEquals(7, Diary(title = "제목", content = "내용").getId().version())
        }

        @Test
        fun `같은 ID의 일기는 같은 일기다`() {
            val id = Diary(title = "제목", content = "내용").getId()

            assertEquals(Diary(id, "제목", "내용"), Diary(id, "다른 제목", "다른 내용"))
            assertNotEquals(Diary(title = "제목", content = "내용"), Diary(title = "제목", content = "내용"))
        }
    }

    @Nested
    inner class `경계` {
        @Test
        fun `미리보기 내용은 열 글자까지 그대로 보여준다`() {
            val diary = Diary(title = "제목", content = "1234567890")

            val preview = DiaryPreviewResponse(diary)

            assertEquals("1234567890", preview.content)
        }

        @Test
        fun `미리보기 내용은 열한 글자부터 생략한다`() {
            val diary = Diary(title = "제목", content = "12345678901")

            val preview = DiaryPreviewResponse(diary)

            assertEquals("제목", preview.title)
            assertEquals("1234567890...", preview.content)
        }
    }

    @Nested
    inner class `실패` {
        @Test
        fun `다음 페이지가 있으면 다음 커서가 필수다`() {
            assertFailsWith<IllegalArgumentException> {
                CursorSlice<Diary>(items = emptyList(), hasNext = true, nextCursorId = null)
            }
        }

        @Test
        fun `제목은 코드 포인트로 255자를 넘으면 거부한다`() {
            Diary(title = "😀".repeat(255), content = "내용") // UTF-16으로는 510칸이지만 255자다
            assertFailsWith<IllegalArgumentException> { Diary(title = "😀".repeat(256), content = "내용") }
        }

        @Test
        fun `제목과 본문에 NUL 문자가 있으면 거부한다`() {
            assertFailsWith<IllegalArgumentException> { Diary(title = "a\u0000b", content = "내용") }
            assertFailsWith<IllegalArgumentException> { Diary(title = "제목", content = "a\u0000b") }
        }

        @Test
        fun `UUID v7이 아닌 ID는 거부한다`() {
            assertFailsWith<IllegalArgumentException> {
                Diary(id = UUID.randomUUID(), title = "제목", content = "내용")
            }
        }
    }
}
