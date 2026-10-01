package com.example.diary.diary

import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DiaryRepository : JpaRepository<Diary, UUID> {
    fun findAllByOrderByIdDesc(limit: Limit): List<Diary>

    fun findByIdLessThanOrderByIdDesc(
        id: UUID,
        limit: Limit,
    ): List<Diary>
}
