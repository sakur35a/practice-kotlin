package com.example.diary

import com.example.diary.diary.Diary
import com.example.diary.diary.DiaryPreviewResponse
import com.example.diary.pagination.CursorSlice
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DiaryDomainTest {
    @Test
    fun `생성한 일기 ID는 UUID v7이다`() {
        assertEquals(7, Diary(title = "제목", content = "내용").id.version())
    }

    @Test
    fun `미리보기 내용은 열 글자 뒤를 생략한다`() {
        val diary = Diary(title = "제목", content = "12345678901")

        val preview = DiaryPreviewResponse(diary)

        assertEquals("제목", preview.title)
        assertEquals("1234567890...", preview.content)
    }

    @Test
    fun `다음 페이지가 있으면 다음 커서가 필수다`() {
        assertFailsWith<IllegalArgumentException> {
            CursorSlice<Diary>(items = emptyList(), hasNext = true, nextCursorId = null)
        }
    }

    @Test
    fun `UUID v7이 아닌 ID는 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            Diary(id = UUID.randomUUID(), title = "제목", content = "내용")
        }
    }
}
