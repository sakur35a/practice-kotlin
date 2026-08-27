package com.example.diary.diary

import com.fasterxml.uuid.Generators
import java.util.UUID

data class Diary(
    val id: UUID = newDiaryId(),
    val title: String,
    val content: String,
) {
    init {
        require(id.version() == UUID_V7) {
            "Diary id는 UUID v7이어야 합니다: ${id.version()}"
        }
    }
}

private const val UUID_V7 = 7
private val diaryIdGenerator = Generators.timeBasedEpochGenerator()

private fun newDiaryId(): UUID = diaryIdGenerator.generate()
