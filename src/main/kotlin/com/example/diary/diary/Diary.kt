package com.example.diary.diary

import com.fasterxml.uuid.Generators
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

// JPA 엔티티가 곧 도메인이다. 웹 계층은 이 클래스를 직접 받거나 돌려주지 않고 DTO를 쓴다.
@Entity
@Table(name = "diaries")
class Diary(
    @Id
    @Column(name = "id", columnDefinition = "binary(16)")
    private val id: UUID = newDiaryId(),
    @Column(name = "title", nullable = false, length = 255)
    val title: String,
    @Column(name = "content", nullable = false, columnDefinition = "mediumtext")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    val content: String,
) : Persistable<UUID> {
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    val createdAt: Instant = Instant.now()

    // id를 직접 정하는 엔티티라 save()가 기존 행을 조용히 덮어쓰지 않도록 새 엔티티임을 알려준다
    @Transient
    private var new: Boolean = true

    init {
        require(id.version() == UUID_V7) {
            "Diary id는 UUID v7이어야 합니다: ${id.version()}"
        }
    }

    override fun getId(): UUID = id

    override fun isNew(): Boolean = new

    @PostLoad
    @PostPersist
    private fun markNotNew() {
        new = false
    }

    override fun equals(other: Any?): Boolean = this === other || (other is Diary && id == other.id)

    override fun hashCode(): Int = id.hashCode()

    private companion object {
        private const val UUID_V7 = 7

        private val idGenerator = Generators.timeBasedEpochGenerator()

        private fun newDiaryId(): UUID = idGenerator.generate()
    }
}
