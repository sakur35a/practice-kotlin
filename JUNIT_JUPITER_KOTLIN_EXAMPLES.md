# JUnit Jupiter Kotlin Examples

이 프로젝트는 Spring Boot의 테스트 의존성을 통해 JUnit Jupiter 6.0.3과
`junit-jupiter-params`를 사용한다. 별도 의존성을 추가하지 않고 아래 기능을 사용할 수 있다.

## 빠른 선택 기준

| 상황 | 기능 |
|---|---|
| 한 가지 동작을 검증 | `@Test` |
| 단순 입력 여러 개 | `@ValueSource` |
| 입력과 기대값의 표 | `@CsvSource` |
| `null`, 빈 문자열 경계 | `@NullSource`, `@EmptySource`, `@NullAndEmptySource` |
| enum 값 전체 또는 일부 | `@EnumSource` |
| 객체나 복잡한 입력 | `@MethodSource` |
| 관련 assertion을 함께 실행 | `assertAll` |
| 예외 타입과 내용을 검증 | `assertFailsWith` |
| 테스트마다 준비/정리 | `@BeforeEach`, `@AfterEach` |
| 동작별 문맥을 묶음 | `@Nested` |
| 임시 파일과 디렉터리 | `@TempDir` |
| 실행 시간이 무한히 늘어나는 것을 방지 | `@Timeout` |
| 통합 테스트를 선택적으로 실행 | `@Tag` |
| 특정 환경에서만 실행 | `assumeTrue`, `@EnabledOnOs` |
| 실행 중 테스트 케이스 생성 | `@TestFactory` |
| 같은 테스트 반복 | `@RepeatedTest` |

기본값은 `@Test`다. 같은 본문이 입력값만 바뀌며 세 번 이상 반복될 때
parameterized test로 바꾸는 편이 읽기 쉽다.

## 1. 일반 테스트

테스트 이름에 조건과 기대 결과를 적으면 별도의 성공/실패 그룹이 필요하지 않다.

```kotlin
class DiaryTest {
    @Test
    fun `생성한 일기 ID는 UUID v7이다`() {
        val diary = Diary(title = "제목", content = "내용")

        assertEquals(7, diary.id.version())
    }
}
```

## 2. ValueSource

하나의 단순 매개변수에 여러 값을 넣을 때 사용한다.

```kotlin
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class DiaryPreviewResponseTest {
    @ParameterizedTest
    @ValueSource(strings = ["", "1", "1234567890"])
    fun `열 글자 이하의 내용은 그대로 보여준다`(content: String) {
        val preview = DiaryPreviewResponse(Diary(title = "제목", content = content))

        assertEquals(content, preview.content)
    }
}
```

`ValueSource`는 문자열, 숫자, 문자, boolean 같은 단순 값에 적합하다. 기대값이
입력마다 다르면 `CsvSource`를 사용한다.

## 3. CsvSource

입력과 기대값을 표처럼 표현할 때 사용한다.

```kotlin
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DiaryPreviewResponseTest {
    @ParameterizedTest(name = "{index}: {0} -> {1}")
    @CsvSource(
        "123456789, 123456789",
        "1234567890, 1234567890",
        "12345678901, 1234567890...",
    )
    fun `내용을 미리보기로 변환한다`(
        content: String,
        expected: String,
    ) {
        val preview = DiaryPreviewResponse(Diary(title = "제목", content = content))

        assertEquals(expected, preview.content)
    }
}
```

쉼표나 줄바꿈이 포함된 복잡한 데이터는 `CsvSource`보다 `MethodSource`가 안전하다.

## 4. NullSource와 EmptySource

nullable 입력의 경계를 짧게 표현한다.

```kotlin
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource

@ParameterizedTest
@NullAndEmptySource
fun `제목이 없으면 거부한다`(title: String?) {
    assertFailsWith<IllegalArgumentException> {
        require(!title.isNullOrBlank())
    }
}
```

공백 문자열도 필요하면 `ValueSource`를 함께 붙일 수 있다.

```kotlin
@ParameterizedTest
@NullAndEmptySource
@ValueSource(strings = [" ", "\t"])
fun `빈 제목은 거부한다`(title: String?) {
    assertTrue(title.isNullOrBlank())
}
```

## 5. EnumSource

enum 전체 또는 선택한 값에 같은 규칙을 검증한다.

```kotlin
enum class DiaryStatus {
    DRAFT,
    PUBLISHED,
    DELETED,
}

@ParameterizedTest
@EnumSource(DiaryStatus::class)
fun `모든 상태는 표시 이름을 가진다`(status: DiaryStatus) {
    assertTrue(status.name.isNotBlank())
}
```

일부 값만 선택할 수도 있다.

```kotlin
@ParameterizedTest
@EnumSource(
    value = DiaryStatus::class,
    names = ["PUBLISHED", "DELETED"],
)
fun `공개 이후 상태는 초안이 아니다`(status: DiaryStatus) {
    assertNotEquals(DiaryStatus.DRAFT, status)
}
```

## 6. MethodSource

객체, nullable 값, 쉼표가 포함된 문자열 등 `CsvSource`로 읽기 어려운 케이스에 사용한다.

```kotlin
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class CursorSliceTest {
    @ParameterizedTest
    @MethodSource("validSlices")
    fun `유효한 페이지 상태를 생성한다`(slice: CursorSlice<String>) {
        assertEquals(slice.hasNext, slice.nextCursorId != null)
    }

    companion object {
        @JvmStatic
        fun validSlices() =
            listOf(
                Arguments.of(CursorSlice(emptyList<String>(), false, null)),
                Arguments.of(CursorSlice(listOf("일기"), true, UUID.randomUUID())),
            )
    }
}
```

Kotlin에서는 companion object의 source 함수에 `@JvmStatic`을 붙이는 것이 가장 단순하다.
케이스가 두 개뿐이고 본문도 다르면 일반 `@Test` 두 개가 더 읽기 쉽다.

## 7. CsvFileSource

케이스가 많고 비개발자도 데이터를 편집해야 할 때 CSV 파일을 사용할 수 있다.

```kotlin
import org.junit.jupiter.params.provider.CsvFileSource

@ParameterizedTest
@CsvFileSource(resources = ["/preview-cases.csv"], numLinesToSkip = 1)
fun `CSV 기준으로 미리보기를 만든다`(
    content: String,
    expected: String,
) {
    val preview = DiaryPreviewResponse(Diary(title = "제목", content = content))

    assertEquals(expected, preview.content)
}
```

작은 표를 별도 파일로 분리하면 오히려 찾기 어려워진다. 케이스가 충분히 많을 때만 사용한다.

## 8. assertAll

한 결과의 여러 속성을 모두 확인하고, 실패 내용을 한 번에 보고 싶을 때 사용한다.

```kotlin
import org.junit.jupiter.api.assertAll

@Test
fun `일기 미리보기를 만든다`() {
    val diary = Diary(title = "제목", content = "12345678901")

    val preview = DiaryPreviewResponse(diary)

    assertAll(
        { assertEquals(diary.id, preview.id) },
        { assertEquals("제목", preview.title) },
        { assertEquals("1234567890...", preview.content) },
    )
}
```

서로 무관한 여러 동작을 하나의 `assertAll`에 넣지는 않는다.

## 9. 예외 검증

Kotlin에서는 `assertFailsWith`가 간결하다. 반환된 예외로 메시지도 검증할 수 있다.

```kotlin
@Test
fun `UUID v7이 아닌 ID는 거부한다`() {
    val exception =
        assertFailsWith<IllegalArgumentException> {
            Diary(
                id = UUID.randomUUID(),
                title = "제목",
                content = "내용",
            )
        }

    assertTrue(exception.message.orEmpty().contains("UUID v7"))
}
```

예외 메시지가 외부 계약이 아니라면 타입만 검증한다. 문구까지 고정하면 사소한 메시지 변경에도
테스트가 깨진다.

## 10. BeforeEach와 AfterEach

각 테스트가 동일한 준비와 정리를 반드시 수행할 때 사용한다.

```kotlin
class DiaryRepositoryTest {
    @BeforeEach
    fun cleanBefore() {
        dsl.deleteFrom(DIARIES).execute()
    }

    @AfterEach
    fun cleanAfter() {
        dsl.deleteFrom(DIARIES).execute()
    }
}
```

준비 코드가 한 테스트에서만 쓰이면 테스트 안에 둔다. lifecycle 함수로 이동하면 테스트가
숨은 상태에 의존하게 된다.

## 11. BeforeAll과 TestInstance

비싼 자원을 클래스당 한 번 준비할 때 사용할 수 있다.

```kotlin
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpensiveResourceTest {
    @BeforeAll
    fun prepareOnce() {
        // 클래스 전체에서 공유할 비싼 준비 작업
    }
}
```

공유된 mutable state는 테스트 순서 의존성을 만든다. Testcontainers처럼 이미 Spring이나
JUnit이 생명주기를 관리하는 자원에는 중복해서 사용하지 않는다.

## 12. Nested

성공/실패보다 같은 동작이나 메서드의 문맥을 묶을 때 유용하다.

```kotlin
class DiaryPreviewResponseTest {
    @Nested
    inner class `내용 미리보기` {
        @Test
        fun `열 글자까지 그대로 보여준다`() { /* ... */ }

        @Test
        fun `열 글자를 넘으면 생략한다`() { /* ... */ }
    }
}
```

테스트가 몇 개뿐이면 중첩 없이 평평하게 두는 편이 낫다.

## 13. TempDir

테스트 전용 임시 디렉터리를 안전하게 만들고 자동으로 정리한다.

```kotlin
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileExportTest {
    @Test
    fun `파일을 저장한다`(@TempDir tempDir: Path) {
        val output = tempDir.resolve("diary.txt")

        Files.writeString(output, "내용")

        assertEquals("내용", Files.readString(output))
    }
}
```

직접 `/tmp` 경로를 만들거나 테스트 종료 후 삭제 코드를 작성할 필요가 없다.

## 14. Timeout

외부 시스템이나 동시성 코드가 영원히 대기하지 않도록 상한을 둔다.

```kotlin
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

@Test
@Timeout(value = 2, unit = TimeUnit.SECONDS)
fun `조회는 제한 시간 안에 끝난다`() {
    diaryService.findAll()
}
```

성능 목표를 검증하는 용도로 사용하면 CI 환경 차이 때문에 불안정해진다. timeout은 성능
측정보다 무한 대기 방지에 사용한다.

## 15. Tag

느린 통합 테스트를 분리해서 실행할 때 사용한다.

```kotlin
import org.junit.jupiter.api.Tag

@Tag("integration")
@JooqTest
class DiaryServiceIntegrationTest {
    // PostgreSQL 통합 테스트
}
```

현재 `test` task는 모든 tag를 실행한다. 통합 테스트만 따로 실행하려면
`build.gradle.kts`에 별도 task를 등록한다.

```kotlin
tasks.register<Test>("integrationTest") {
    useJUnitPlatform {
        includeTags("integration")
    }
}
```

```bash
./gradlew integrationTest
```

## 16. Assumptions

필수 환경이 없을 때 실패가 아니라 테스트 생략으로 처리한다.

```kotlin
import org.junit.jupiter.api.Assumptions.assumeTrue

@Test
fun `Docker 환경에서 컨테이너를 실행한다`() {
    assumeTrue(System.getenv("CI") == "true", "CI에서만 실행")

    // CI 전용 검증
}
```

업무 조건을 assumption으로 숨기면 안 된다. OS, Docker, 환경 변수처럼 테스트 실행 환경에만
사용한다.

## 17. OS와 JRE 조건

플랫폼 종속 코드를 특정 환경에서만 실행한다.

```kotlin
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

@Test
@EnabledOnOs(OS.LINUX)
fun `Linux 전용 명령을 실행한다`() {
    // Linux 전용 검증
}
```

`@EnabledOnJre`, `@EnabledForJreRange`, `@EnabledIfEnvironmentVariable`도 같은 계열이다.

## 18. TestInfo

현재 테스트의 이름이나 tag가 실제 테스트 로직에 필요할 때 주입받는다.

```kotlin
import org.junit.jupiter.api.TestInfo

@Test
fun `테스트 메타데이터를 확인한다`(testInfo: TestInfo) {
    assertTrue(testInfo.displayName.contains("메타데이터"))
}
```

단순 로그 출력에 매번 사용하지 않는다. 실패 보고서에 이미 테스트 이름이 나온다.

## 19. RepeatedTest

동일한 테스트를 지정한 횟수만큼 반복한다.

```kotlin
import org.junit.jupiter.api.RepeatedTest

@RepeatedTest(10)
fun `생성한 ID는 서로 다르다`() {
    val first = Diary(title = "제목", content = "내용").id
    val second = Diary(title = "제목", content = "내용").id

    assertNotEquals(first, second)
}
```

flaky test를 통과시키기 위해 반복하지 않는다. 반복해도 확률적 버그를 증명할 수 없으면 더
결정적인 입력과 검증을 만든다.

## 20. DynamicTest와 TestFactory

실행 시점에 데이터가 결정될 때 테스트를 동적으로 만든다.

```kotlin
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

class PreviewDynamicTest {
    @TestFactory
    fun `미리보기 사례`() =
        listOf(
            "1234567890" to "1234567890",
            "12345678901" to "1234567890...",
        ).map { (content, expected) ->
            dynamicTest("$content -> $expected") {
                val preview = DiaryPreviewResponse(Diary(title = "제목", content = content))
                assertEquals(expected, preview.content)
            }
        }
}
```

정적인 표는 `CsvSource`나 `MethodSource`가 더 단순하다. 파일 탐색이나 런타임 모델처럼
테스트 개수를 컴파일 시점에 알 수 없을 때만 `TestFactory`를 사용한다.

## 21. Disabled

일시적으로 실행하지 않을 테스트에 이유를 남긴다.

```kotlin
import org.junit.jupiter.api.Disabled

@Disabled("외부 API sandbox 복구 후 활성화")
@Test
fun `외부 API와 통합한다`() {
    // ...
}
```

기한 없이 비활성화된 테스트는 삭제된 테스트와 같다. issue 번호나 다시 활성화할 조건을
반드시 기록한다.

## 이 프로젝트에 권장하는 조합

현재 도메인 테스트에는 다음 정도면 충분하다.

```text
DiaryTest                  일반 @Test + assertFailsWith
DiaryPreviewResponseTest   @CsvSource
CursorSliceTest            일반 @Test
통합 테스트                @JooqTest + @Tag("integration")
```

`MethodSource`, `TestFactory`, 커스텀 extension은 실제 입력이 단순 annotation으로 표현되지
않을 때 추가한다.
