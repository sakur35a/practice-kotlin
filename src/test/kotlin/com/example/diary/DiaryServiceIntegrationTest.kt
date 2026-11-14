package com.example.diary

import com.example.diary.diary.Diary
import com.example.diary.diary.DiaryService
import com.example.diary.diary.DuplicateDiaryIdException
import com.example.diary.pagination.CursorQuery
import jakarta.persistence.EntityManager
import org.hibernate.proxy.HibernateProxy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    DiaryService::class,
    PostgreSQLTestConfiguration::class,
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
        fun `Hibernate 프록시와도 id로 같은 일기다`() {
            val created = diaryService.createDiary(id = null, title = "제목", content = "내용")
            entityManager.flush()
            entityManager.clear()

            // getReference는 필드가 비어 있는 프록시를 준다. equals가 필드를 직접 읽으면 같은 일기인데도 다르다고 나온다
            val proxy = entityManager.getReference(Diary::class.java, created.id)

            assertTrue(proxy is HibernateProxy)
            assertEquals(created, proxy)
            assertEquals(proxy, created)
            assertEquals(created.hashCode(), proxy.hashCode())
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
        fun `수 MB 본문도 크기 제한 없이 저장한다`() {
            // MySQL은 mediumtext(16MB)라 상한이 있었다. PostgreSQL text는 제한이 없다. 한글 3바이트 기준 약 6MB
            val content = "가".repeat(2_000_000)
            val created = diaryService.createDiary(id = null, title = "긴 일기", content = content)
            entityManager.clear()

            assertEquals(content, diaryService.findById(created.id)?.content)
        }

        @Test
        fun `생성 시각은 시간대와 상관없이 UTC 순간으로 저장한다`() {
            val created = diaryService.createDiary(id = null, title = "제목", content = "내용")
            entityManager.flush()

            // timestamptz를 UTC 벽시계 시각으로 풀어 읽는다. JVM이나 세션 시간대에 따라 달라지면 이 값이 어긋난다
            val storedUtc =
                jdbcTemplate.queryForObject(
                    "select (created_at at time zone 'UTC') from diaries where id = ?",
                    LocalDateTime::class.java,
                    created.id,
                )!!

            val now = LocalDateTime.now(ZoneOffset.UTC)
            assertTrue(
                java.time.Duration
                    .between(storedUtc, now)
                    .abs()
                    .seconds < 10,
            )
        }

        @Test
        fun `컬럼 타입은 uuid, timestamptz, text다`() {
            val types =
                jdbcTemplate
                    .queryForList(
                        "select column_name, data_type from information_schema.columns where table_name = 'diaries'",
                    ).associate { it["column_name"] to it["data_type"] }

            assertEquals("uuid", types["id"])
            assertEquals("timestamp with time zone", types["created_at"])
            assertEquals("text", types["content"])
        }

        @Test
        fun `id 정렬은 자바 UUID 비교가 아니라 16바이트 부호 없는 순서를 따른다`() {
            // 앞 48비트가 시각이다. 0x8000...은 자바 long으로는 음수라 UUID.compareTo가 가장 작다고 보지만 DB는 가장 크다고 본다.
            // keyset 페이지네이션(id < ? order by id desc)이 DB 순서를 따르므로 이쪽이 맞아야 한다.
            fun v7(timestampMs: Long): UUID =
                UUID(
                    (timestampMs shl 16) or (7L shl 12) or 0x123L,
                    Long.MIN_VALUE or 0x456L,
                )
            val small = v7(0x0000_0000_1000L)
            val middle = v7(0x7fff_ffff_ffffL)
            val big = v7(0x8000_0000_0001L)
            listOf(middle, big, small).forEach { diaryService.createDiary(id = it, title = "제목", content = "내용") }
            entityManager.clear()

            val first = diaryService.findDiarySlice(CursorQuery(size = 2))
            val second = diaryService.findDiarySlice(CursorQuery(cursorId = first.nextCursorId, size = 2))

            assertEquals(listOf(big, middle), first.items.map { it.id })
            assertEquals(listOf(small), second.items.map { it.id })
            assertTrue(big < middle, "자바 비교로는 순서가 뒤집혀 있어야 이 시험이 의미가 있다")
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
        fun `이미 존재하는 ID로 저장하면 덮어쓰지 않고 중복 id 예외로 실패한다`() {
            val id = Diary(title = "제목", content = "내용").id
            diaryService.createDiary(id = id, title = "처음", content = "내용")
            entityManager.flush()
            entityManager.clear()

            // 데이터베이스 기본 키 위반(SQLState 23505)을 도메인 예외로 옮긴다. HTTP에서는 409다
            val failure =
                assertFailsWith<DuplicateDiaryIdException> {
                    diaryService.createDiary(id = id, title = "덮어쓰기 시도", content = "내용")
                }
            assertEquals(id, failure.id)
        }

        @Test
        fun `UUID v7이 아닌 ID는 저장하지 않는다`() {
            assertFailsWith<IllegalArgumentException> {
                diaryService.createDiary(id = UUID.randomUUID(), title = "제목", content = "내용")
            }
        }

        @Test
        fun `같은 id는 애플리케이션 검사를 거치지 않아도 DB 기본 키가 막는다`() {
            val id = Diary(title = "제목", content = "내용").id
            val insert = "insert into diaries (id, created_at, title, content) values (?, now(), 'a', 'b')"
            jdbcTemplate.update(insert, id)

            assertFailsWith<DataIntegrityViolationException> { jdbcTemplate.update(insert, id) }
        }

        @Test
        fun `255자를 넘는 제목은 도메인이 먼저 거부하고, 우회해도 DB 컬럼이 거부한다`() {
            assertFailsWith<IllegalArgumentException> {
                diaryService.createDiary(id = null, title = "가".repeat(256), content = "내용")
            }

            // 도메인을 거치지 않는 경로(직접 SQL)로 넣어도 varchar(255)가 막는다. 이모지는 코드 포인트 255개면 통과한다
            val insert = "insert into diaries (id, created_at, title, content) values (?, now(), ?, 'b')"
            jdbcTemplate.update(insert, Diary(title = "a", content = "b").id, "😀".repeat(255))
            assertFailsWith<DataIntegrityViolationException> {
                jdbcTemplate.update(insert, Diary(title = "a", content = "b").id, "가".repeat(256))
            }
        }

        @Test
        fun `NUL 문자는 도메인이 거부하고, 우회해도 PostgreSQL이 거부한다`() {
            assertFailsWith<IllegalArgumentException> {
                diaryService.createDiary(id = null, title = "제목", content = "a\u0000b")
            }

            assertFailsWith<DataIntegrityViolationException> {
                jdbcTemplate.update(
                    "insert into diaries (id, created_at, title, content) values (?, now(), 'a', ?)",
                    Diary(title = "a", content = "b").id,
                    "a\u0000b",
                )
            }
        }
    }
}
