package com.example.diary.diary

import com.example.diary.pagination.CursorQuery
import com.example.diary.pagination.CursorSlice
import com.example.jooq.generated.tables.Diaries.DIARIES
import com.example.jooq.generated.tables.records.DiariesRecord
import org.jooq.DSLContext
import org.jooq.impl.DSL.noCondition
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class DiaryRepository(
    private val dsl: DSLContext,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun findAll(): List<Diary> =
        dsl
            .selectFrom(DIARIES)
            .fetch(::toDiary)

    fun findDiarySlice(query: CursorQuery): CursorSlice<Diary> {
        val startedAt = System.nanoTime()
        val condition = query.cursorId?.let(DIARIES.ID::lessThan) ?: noCondition()

        val diaries =
            dsl
                .selectFrom(DIARIES)
                .where(condition)
                .orderBy(DIARIES.ID.desc())
                .limit(query.size + 1)
                .fetch(::toDiary)

        val hasNext = diaries.size > query.size
        val items = if (hasNext) diaries.dropLast(1) else diaries

        logger.info(
            "layer=repository operation=findDiarySlice elapsedMs={} items={}",
            (System.nanoTime() - startedAt) / 1_000_000,
            items.size,
        )

        return CursorSlice(
            items = items,
            hasNext = hasNext,
            nextCursorId = if (hasNext) items.last().id else null,
        )
    }

    fun createDiary(diary: Diary) {
        val rowsAffected =
            dsl
                .insertInto(DIARIES)
                .set(DIARIES.ID, diary.id)
                .set(DIARIES.TITLE, diary.title)
                .set(DIARIES.CONTENT, diary.content)
                .set(DIARIES.CREATED_AT, Instant.now())
                .execute()

        check(rowsAffected == 1) { "Diary insert affected $rowsAffected rows" }
    }

    private fun toDiary(record: DiariesRecord): Diary =
        Diary(
            id = requireNotNull(record[DIARIES.ID]),
            title = requireNotNull(record[DIARIES.TITLE]),
            content = requireNotNull(record[DIARIES.CONTENT]),
        )
}
