package com.example.diary

import com.example.diary.diary.Diary
import com.example.diary.diary.DiaryService
import com.example.diary.pagination.CursorQuery
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    DiaryService::class,
    MySQLTestConfiguration::class,
)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class DiaryServiceIntegrationTest(
    private val diaryService: DiaryService,
    private val entityManager: EntityManager,
    private val jdbcTemplate: JdbcTemplate,
) {
    @Nested
    inner class `성공` {
        @Test
        fun `일기를 저장하고 조회한다`() {
            val created = diaryService.createDiary(id = null, title = "제목", content = "내용")
            entityManager.clear()

            val result = diaryService.findById(created.id)

            assertEquals(created, result)
            assertEquals("제목", result?.title)
            assertEquals("내용", result?.content)
        }

        @Test
        fun `직접 정한 UUID v7 ID로 저장한다`() {
            val id = Diary(title = "제목", content = "내용").id

            val created = diaryService.createDiary(id = id, title = "제목", content = "내용")

            assertEquals(id, created.id)
        }

        @Test
        fun `한글과 이모지와 긴 본문을 그대로 저장한다`() {
            val content = "줄바꿈\n\"따옴표\" '작은따옴표' 😀 " + "가".repeat(5000)
            val created = diaryService.createDiary(id = null, title = "한글 제목 😀", content = content)
            entityManager.clear()

            val result = diaryService.findById(created.id)

            assertEquals("한글 제목 😀", result?.title)
            assertEquals(content, result?.content)
        }

        @Test
        fun `생성 시각은 시간대와 상관없이 UTC로 저장한다`() {
            val created = diaryService.createDiary(id = null, title = "제목", content = "내용")
            entityManager.flush()

            val stored =
                jdbcTemplate.queryForObject(
                    "select created_at from diaries where id = ?",
                    java.sql.Timestamp::class.java,
                    uuidBytes(created.id),
                )

            // datetime(6)을 UTC 기준 LocalDateTime으로 읽은 값이 저장한 순간(UTC)과 몇 초 안에 있어야 한다
            val storedUtc = stored!!.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC)
            assertTrue(java.time.Duration.between(storedUtc, java.time.Instant.now()).abs().seconds < 10)
        }

        @Test
        fun `커서 페이지네이션은 id 내림차순으로 이어서 읽는다`() {
            val ids = (1..5).map { diaryService.createDiary(id = null, title = "제목$it", content = "내용").id }
            entityManager.clear()
            val expected = ids.sortedDescending()

            val first = diaryService.findDiarySlice(CursorQuery(size = 2))
            val second = diaryService.findDiarySlice(CursorQuery(cursorId = first.nextCursorId, size = 2))
            val third = diaryService.findDiarySlice(CursorQuery(cursorId = second.nextCursorId, size = 2))

            assertEquals(expected.subList(0, 2), first.items.map { it.id })
            assertEquals(expected.subList(2, 4), second.items.map { it.id })
            assertEquals(expected.subList(4, 5), third.items.map { it.id })
            assertTrue(first.hasNext && second.hasNext)
            assertEquals(false, third.hasNext)
            assertNull(third.nextCursorId)
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
            assertEquals(emptyList(), diaryService.findDiarySlice(CursorQuery()).items)
        }

        @Test
        fun `정확히 페이지 크기만큼 있으면 다음 페이지가 없다`() {
            repeat(2) { diaryService.createDiary(id = null, title = "제목", content = "내용") }
            entityManager.clear()

            val slice = diaryService.findDiarySlice(CursorQuery(size = 2))

            assertEquals(2, slice.items.size)
            assertEquals(false, slice.hasNext)
        }
    }

    @Nested
    inner class `실패` {
        @Test
        fun `이미 존재하는 ID로 저장하면 덮어쓰지 않고 실패한다`() {
            val id = Diary(title = "제목", content = "내용").id
            diaryService.createDiary(id = id, title = "처음", content = "내용")
            entityManager.flush()
            entityManager.clear()

            assertFailsWith<DataIntegrityViolationException> {
                diaryService.createDiary(id = id, title = "덮어쓰기 시도", content = "내용")
            }
        }

        @Test
        fun `UUID v7이 아닌 ID는 저장하지 않는다`() {
            assertFailsWith<IllegalArgumentException> {
                diaryService.createDiary(id = UUID.randomUUID(), title = "제목", content = "내용")
            }
        }
    }

    private fun uuidBytes(id: UUID): ByteArray =
        java.nio.ByteBuffer
            .allocate(16)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .array()
}
