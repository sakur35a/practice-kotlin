package com.example.diary.diary

import com.example.diary.pagination.CursorQuery
import com.example.diary.pagination.CursorSlice
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private val logger = KotlinLogging.logger {}
const val DIARY_SLICES_CACHE = "diarySlices"

@Service
class DiaryService(
    private val diaryRepository: DiaryRepository,
) {
    @Transactional(readOnly = true)
    fun findAll(): List<Diary> = diaryRepository.findAll()

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = [DIARY_SLICES_CACHE], sync = true)
    fun findDiarySlice(cursorQuery: CursorQuery): CursorSlice<Diary> {
        val startedAt = System.nanoTime()
        val slice = diaryRepository.findDiarySlice(cursorQuery)

        logger.info {
            "layer=service operation=findDiarySlice elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000} items=${slice.items.size}"
        }
        return slice
    }

    @Transactional
    @CacheEvict(cacheNames = [DIARY_SLICES_CACHE], allEntries = true)
    fun createDiary(diary: Diary): Diary {
        val startedAt = System.nanoTime()
        diaryRepository.createDiary(diary)

        logger.info {
            "layer=service operation=createDiary elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000} id=${diary.id}"
        }
        return diary
    }
}
