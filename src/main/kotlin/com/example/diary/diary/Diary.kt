package com.example.diary.diary

import com.fasterxml.uuid.Generators
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

// JPA 엔티티가 곧 도메인이다. 웹 계층은 이 클래스를 직접 받거나 돌려주지 않고 DTO를 쓴다.
@Entity
@Table(name = "diaries")
class Diary(
    // PostgreSQL uuid 컬럼. Hibernate가 java.util.UUID를 uuid로 바로 매핑하므로 columnDefinition이 필요 없다
    @Id
    @Column(name = "id")
    private val id: UUID = newDiaryId(),
    @Column(name = "title", nullable = false, length = TITLE_MAX_LENGTH)
    val title: String,
    // text는 크기 제한이 없다. @Lob을 쓰지 않는다(PostgreSQL에서는 oid로 저장된다)
    @Column(name = "content", nullable = false, columnDefinition = "text")
    val content: String,
) : Persistable<UUID> {
    // timestamptz. hibernate.jdbc.time_zone=UTC라서 어느 서버 시간대에서든 같은 순간이 저장된다
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()

    // id를 직접 정하는 엔티티라 save()가 기존 행을 조용히 덮어쓰지 않도록 새 엔티티임을 알려준다
    @Transient
    private var new: Boolean = true

    // 이 불변식들은 요청 DTO의 제약(DiaryCreateRequest)과 같은 규칙이다. 가장자리(HTTP)에서 먼저 거르고 도메인에서 한 번 더 지킨다:
    // 가장자리는 클라이언트에게 필드별 오류를 돌려주려는 것이고, 도메인은 다른 경로(시험, 가져오기, 이후 새 진입점)로 들어와도
    // 잘못된 일기가 만들어지지 않게 하려는 것이다. 두 곳이 어긋나지 않도록 상수와 판정 함수를 함께 쓴다.
    init {
        require(isUuidV7(id)) {
            "Diary id는 UUID v7이어야 합니다: ${id.version()}"
        }
        require(codePointLength(title) <= TITLE_MAX_LENGTH) {
            "Diary title은 ${TITLE_MAX_LENGTH}자(코드 포인트) 이하여야 합니다"
        }
        require(!containsNul(title) && !containsNul(content)) {
            "Diary title과 content에는 NUL 문자(U+0000)를 넣을 수 없습니다"
        }
    }

    override fun getId(): UUID = id

    override fun isNew(): Boolean = new

    @PostLoad
    @PostPersist
    private fun markNotNew() {
        new = false
    }

    // other.id(필드)가 아니라 getId()로 읽는다: Hibernate 프록시는 필드가 비어 있고 메서드 호출만 실제 엔티티로 넘겨 준다
    override fun equals(other: Any?): Boolean = this === other || (other is Diary && id == other.getId())

    override fun hashCode(): Int = id.hashCode()

    companion object {
        private const val ID_VERSION = 7

        // varchar(255)는 UTF-16 글자 수(String.length)가 아니라 코드 포인트 수로 센다. 이모지는 String.length로는 2다.
        const val TITLE_MAX_LENGTH = 255

        private val idGenerator = Generators.timeBasedEpochGenerator()

        private fun newDiaryId(): UUID = idGenerator.generate()

        fun isUuidV7(id: UUID): Boolean = id.version() == ID_VERSION

        fun codePointLength(text: String): Int = text.codePointCount(0, text.length)

        // PostgreSQL text와 varchar는 NUL 문자를 담지 못한다 (JSON에서는 \u0000으로 올 수 있다)
        fun containsNul(text: String): Boolean = text.indexOf('\u0000') >= 0
    }
}
