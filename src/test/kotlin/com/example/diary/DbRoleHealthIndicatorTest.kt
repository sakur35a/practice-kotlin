package com.example.diary

import com.example.diary.diary.DiaryRepository
import com.example.diary.diary.DiaryService
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.jooq.test.autoconfigure.JooqTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import kotlin.test.Test
import kotlin.test.assertEquals

@JooqTest
@Import(
    DbRoleHealthIndicator::class,
    DiaryService::class,
    DiaryRepository::class,
    PostgresTestConfiguration::class,
)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class DbRoleHealthIndicatorTest(
    private val dbRoleHealthIndicator: DbRoleHealthIndicator,
) {
    @Test
    fun `primary에 붙어 있으면 UP과 primary 역할을 보여준다`() {
        val health = dbRoleHealthIndicator.health()

        assertEquals(Status.UP, health.status)
        assertEquals("primary", health.details["role"])
    }
}
