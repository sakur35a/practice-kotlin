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

    private companion object {
        // Diary.kt에서만 사용하는 생성 보조 코드이므로 파일 내부 구현으로 둔다.
        // 특정 인스턴스의 상태가 아니라 파일의 생성자 기본값을 계산하는 역할이다.
        private const val UUID_V7 = 7

        private fun newDiaryId(): UUID = diaryIdGenerator.generate()
    }
}

private val diaryIdGenerator = Generators.timeBasedEpochGenerator()
