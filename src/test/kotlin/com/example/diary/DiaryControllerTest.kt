package com.example.diary

import com.example.diary.diary.Diary
import com.example.diary.diary.EdgeCache
import com.example.diary.diary.DiaryController
import com.example.diary.diary.DiaryService
import com.example.diary.pagination.CursorQuery
import com.example.diary.pagination.CursorSlice
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

// API의 JSON 모양이 엔티티가 아니라 DTO로 정해지는지 확인한다
@WebMvcTest(DiaryController::class)
class DiaryControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var diaryService: DiaryService

    @MockitoBean
    lateinit var edgeCache: EdgeCache

    @Test
    fun `일기를 만들면 201과 id, title, content만 돌려준다`() {
        val diary = Diary(title = "제목", content = "내용")
        whenever(diaryService.createDiary(anyOrNull(), any(), any())).thenReturn(diary)

        mockMvc
            .perform(post("/diary").contentType(MediaType.APPLICATION_JSON).content("""{"title":"제목","content":"내용"}"""))
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", "/diary/${diary.id}"))
            .andExpect(jsonPath("$.id").value(diary.id.toString()))
            .andExpect(jsonPath("$.title").value("제목"))
            .andExpect(jsonPath("$.content").value("내용"))
            .andExpect(jsonPath("$.createdAt").doesNotExist())
    }

    @Test
    fun `일기 한 건 조회 응답에는 엔티티 내부 필드가 없다`() {
        val diary = Diary(title = "제목", content = "내용")
        whenever(diaryService.findById(diary.id)).thenReturn(diary)

        mockMvc
            .perform(get("/diary/${diary.id}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(diary.id.toString()))
            .andExpect(jsonPath("$.new").doesNotExist())
            .andExpect(jsonPath("$.createdAt").doesNotExist())
    }

    @Test
    fun `목록은 열 글자 미리보기와 커서를 돌려준다`() {
        val diary = Diary(title = "제목", content = "12345678901")
        whenever(diaryService.findDiarySlice(any<CursorQuery>()))
            .thenReturn(CursorSlice(items = listOf(diary), hasNext = true, nextCursorId = diary.id))

        mockMvc
            .perform(get("/diary"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].content").value("1234567890..."))
            .andExpect(jsonPath("$.hasNext").value(true))
            .andExpect(jsonPath("$.nextCursorId").value(diary.id.toString()))
    }

    @Test
    fun `없는 일기는 404다`() {
        whenever(diaryService.findById(any())).thenReturn(null)

        mockMvc.perform(get("/diary/${UUID.randomUUID()}")).andExpect(status().isNotFound)
    }

    @Test
    fun `UUID v7이 아닌 id로 만들면 400이다`() {
        whenever(diaryService.createDiary(anyOrNull(), any(), any())).thenThrow(IllegalArgumentException("Diary id는 UUID v7이어야 합니다: 4"))

        mockMvc
            .perform(
                post("/diary")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"id":"${UUID.randomUUID()}","title":"제목","content":"내용"}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `목록은 캐시 헤더와 ETag를 주고, 같은 ETag로 다시 물으면 304다`() {
        val diary = Diary(title = "제목", content = "내용")
        whenever(diaryService.findDiarySlice(any<CursorQuery>()))
            .thenReturn(CursorSlice(items = listOf(diary), hasNext = false, nextCursorId = null))

        val etag =
            mockMvc
                .perform(get("/diary"))
                .andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("Cloudflare-CDN-Cache-Control", "max-age=300"))
                .andExpect(header().string("Cache-Tag", EdgeCache.LIST_TAG))
                .andExpect(header().string("Access-Control-Allow-Origin", "*"))
                .andReturn()
                .response
                .getHeader("ETag")!!

        mockMvc.perform(get("/diary").header("If-None-Match", etag)).andExpect(status().isNotModified)
    }

    @Test
    fun `상세는 브라우저가 서버에 다시 묻지 않게 오래 캐시한다`() {
        val diary = Diary(title = "제목", content = "내용")
        whenever(diaryService.findById(diary.id)).thenReturn(diary)

        mockMvc
            .perform(get("/diary/${diary.id}"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "max-age=86400, immutable"))
            .andExpect(header().string("Cloudflare-CDN-Cache-Control", "max-age=86400"))
    }

    @Test
    fun `목록의 첫 페이지는 매번 확인하고, 커서로 가는 이전 페이지는 오래 캐시한다`() {
        val diary = Diary(title = "제목", content = "내용")
        whenever(diaryService.findDiarySlice(any<CursorQuery>()))
            .thenReturn(CursorSlice(items = listOf(diary), hasNext = false, nextCursorId = null))

        mockMvc
            .perform(get("/diary"))
            .andExpect(header().string("Cache-Control", "no-cache"))

        mockMvc
            .perform(get("/diary?cursorId=${diary.id}"))
            .andExpect(header().string("Cache-Control", "max-age=86400, immutable"))
            .andExpect(header().string("Cloudflare-CDN-Cache-Control", "max-age=300"))
            .andExpect(header().string("Cache-Tag", EdgeCache.LIST_TAG))
    }

    @Test
    fun `글을 쓰면 엣지 목록 purge를 요청한다`() {
        val diary = Diary(title = "제목", content = "내용")
        whenever(diaryService.createDiary(anyOrNull(), any(), any())).thenReturn(diary)

        mockMvc
            .perform(post("/diary").contentType(MediaType.APPLICATION_JSON).content("""{"title":"제목","content":"내용"}"""))
            .andExpect(status().isCreated)

        verify(edgeCache, times(1)).purgeList()
    }

    @Test
    fun `글쓰기가 실패하면 purge하지 않는다`() {
        whenever(diaryService.createDiary(anyOrNull(), any(), any())).thenThrow(IllegalArgumentException("Diary id는 UUID v7이어야 합니다: 4"))

        mockMvc
            .perform(post("/diary").contentType(MediaType.APPLICATION_JSON).content("""{"title":"제목","content":"내용"}"""))
            .andExpect(status().isBadRequest)

        verify(edgeCache, never()).purgeList()
    }

    @Test
    fun `없는 일기 응답에는 엣지 캐시 헤더가 없다`() {
        whenever(diaryService.findById(any())).thenReturn(null)

        mockMvc
            .perform(get("/diary/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
            .andExpect(header().doesNotExist("Cloudflare-CDN-Cache-Control"))
    }
}
