package com.example.diary.diary

import com.example.diary.pagination.CursorQuery
import com.example.diary.pagination.CursorSlice
import com.example.jooq.generated.tables.Diaries.DIARIES
import com.example.jooq.generated.tables.records.DiariesRecord
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jooq.DSLContext
import org.jooq.impl.DSL.noCondition
import org.springframework.stereotype.Repository
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Repository
class DiaryRepository(
    private val dsl: DSLContext,
) {
    fun findAll(): List<Diary> =
        dsl
            .selectFrom(DIARIES)
            .fetch(::toDiary)

    fun findDiarySlice(query: CursorQuery): CursorSlice<Diary> {
        val startedAt = System.nanoTime()
        val condition = query.cursorId?.let(DIARIES.ID::lessThan) ?: noCondition()

        val connectionStartedAt = System.nanoTime()
        val (diaries, connectionMs, sqlMs) =
            dsl.connectionResult { connection ->
                val sqlStartedAt = System.nanoTime()
                val diaries =
                    dsl
                        .configuration()
                        .derive(connection)
                        .dsl()
                        .selectFrom(DIARIES)
                        .where(condition)
                        .orderBy(DIARIES.ID.desc())
                        .limit(query.size + 1)
                        .fetch(::toDiary)

                Triple(
                    diaries,
                    (sqlStartedAt - connectionStartedAt) / 1_000_000,
                    (System.nanoTime() - sqlStartedAt) / 1_000_000,
                )
            }

        val hasNext = diaries.size > query.size
        val items = if (hasNext) diaries.dropLast(1) else diaries

        val slice =
            CursorSlice(
                items = items,
                hasNext = hasNext,
                nextCursorId = if (hasNext) items.last().id else null,
            )

        logger.info {
            "layer=repository operation=findDiarySlice elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000} " +
                "connectionMs=$connectionMs sqlMs=$sqlMs items=${slice.items.size}"
        }
        return slice
    }

    fun createDiary(diary: Diary) {
        val startedAt = System.nanoTime()
        val rowsAffected =
            dsl
                .insertInto(DIARIES)
                .set(DIARIES.ID, diary.id)
                .set(DIARIES.TITLE, diary.title)
                .set(DIARIES.CONTENT, diary.content)
                .set(DIARIES.CREATED_AT, Instant.now())
                .execute()

        check(rowsAffected == 1) { "Diary insert affected $rowsAffected rows" }

        logger.info {
            "layer=repository operation=createDiary elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000} id=${diary.id}"
        }
    }

    private fun toDiary(record: DiariesRecord): Diary =
        Diary(
            id = requireNotNull(record[DIARIES.ID]),
            title = requireNotNull(record[DIARIES.TITLE]),
            content = requireNotNull(record[DIARIES.CONTENT]),
        )
}
