package com.example.diary

import com.example.diary.diary.Diary
import com.example.diary.diary.DiaryRepository
import com.example.diary.diary.DiaryService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

class DiaryServiceTest {
    @Test
    fun `저장된 일기가 있을 때 전체 일기를 조회한다`() {
        val diaryRepository = mock<DiaryRepository>()
        val diaryService = DiaryService(diaryRepository)

        val diaries =
            listOf(
                Diary(
                    title = "첫 번째2 일기",
                    content = "첫 번째 내용",
                ),
                Diary(
                    title = "두 번째 일기",
                    content = "두 번째 내용",
                ),
            )

        whenever(
            diaryRepository.findAll(),
        ).thenReturn(diaries)

        val result = diaryService.findAll()

        assertEquals(diaries, result)

        verify(diaryRepository, times(1))
            .findAll()
    }

    @Test
    fun `저장된 일기가 없을 때 빈 목록을 반환한다`() {
        val diaryRepository = mock<DiaryRepository>()
        val diaryService = DiaryService(diaryRepository)

        whenever(
            diaryRepository.findAll(),
        ).thenReturn(emptyList())

        val result = diaryService.findAll()

        assertEquals(emptyList(), result)

        verify(diaryRepository, times(1))
            .findAll()
    }

    @Test
    fun `정상적인 일기를 생성한다`() {
        val diaryRepository = mock<DiaryRepository>()
        val diaryService = DiaryService(diaryRepository)

        val diary =
            Diary(
                title = "제목",
                content = "내용",
            )

        doNothing()
            .whenever(diaryRepository)
            .createDiary(diary)

        diaryService.createDiary(diary)

        verify(diaryRepository, times(1))
            .createDiary(diary)
    }
}
