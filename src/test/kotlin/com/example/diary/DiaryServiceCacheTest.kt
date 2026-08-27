package com.example.diary

import com.example.diary.diary.DIARY_SLICES_CACHE
import com.example.diary.diary.Diary
import com.example.diary.diary.DiaryRepository
import com.example.diary.diary.DiaryService
import com.example.diary.pagination.CursorQuery
import com.example.diary.pagination.CursorSlice
import com.github.benmanes.caffeine.cache.Caffeine
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.caffeine.CaffeineCacheManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.time.Duration
import kotlin.test.assertEquals

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [DiaryServiceCacheTestConfiguration::class])
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class DiaryServiceCacheTest(
    private val diaryService: DiaryService,
    private val diaryRepository: DiaryRepository,
    private val cacheManager: CacheManager,
) {
    @BeforeEach
    fun setUp() {
        cacheManager.getCache(DIARY_SLICES_CACHE)?.clear()
        clearInvocations(diaryRepository)
    }

    @Test
    fun `같은 커서 조회 결과를 캐시한다`() {
        val query = CursorQuery()
        val expected = CursorSlice<Diary>(emptyList(), false, null)
        whenever(diaryRepository.findDiarySlice(query)).thenReturn(expected)

        val first = diaryService.findDiarySlice(query)
        val second = diaryService.findDiarySlice(query)

        assertEquals(expected, first)
        assertEquals(expected, second)
        verify(diaryRepository, times(1)).findDiarySlice(query)
    }

    @Test
    fun `일기를 생성하면 조회 캐시를 무효화한다`() {
        val query = CursorQuery()
        val cached = CursorSlice<Diary>(emptyList(), false, null)
        val diary = Diary(title = "제목", content = "내용")
        whenever(diaryRepository.findDiarySlice(query)).thenReturn(cached)

        diaryService.findDiarySlice(query)
        diaryService.createDiary(diary)
        diaryService.findDiarySlice(query)

        verify(diaryRepository, times(2)).findDiarySlice(query)
        verify(diaryRepository).createDiary(diary)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableCaching
class DiaryServiceCacheTestConfiguration {
    @Bean
    fun diaryRepository(): DiaryRepository = mock()

    @Bean
    fun diaryService(diaryRepository: DiaryRepository): DiaryService = DiaryService(diaryRepository)

    @Bean
    fun cacheManager(): CacheManager =
        CaffeineCacheManager(DIARY_SLICES_CACHE).apply {
            setCaffeine(
                Caffeine
                    .newBuilder()
                    .maximumSize(100)
                    .expireAfterWrite(Duration.ofMinutes(5)),
            )
        }
}
