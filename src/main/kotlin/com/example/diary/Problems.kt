package com.example.diary

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import java.net.URI
import java.util.Locale

// RFC 9457 오류 응답(application/problem+json)의 공통 약속. 오류 계약 전체는 deploy/README.md에 있다.
object Problems {
    // 상태 코드만으로 뜻이 다 전해지는 오류는 type을 쓰지 않는다(about:blank. Spring은 이 값을 본문에서 생략한다. RFC 9457 3.1.1).
    // 상태 코드 말고 더 알려야 하는 오류만 안정적인 절대 URI를 쓴다. 이 URI는 식별자이고 가져다 보라는 주소가 아니다(RFC 9457 3.1.1).
    private const val BASE = "https://api.ssobbs13.pp.ua/problems/"

    val VALIDATION_ERROR: URI = URI.create("${BASE}validation-error")
    val MALFORMED_REQUEST: URI = URI.create("${BASE}malformed-request")
    val DUPLICATE_ID: URI = URI.create("${BASE}duplicate-id")

    // 오류 응답에 붙는 헤더. 어떤 경로로 만들어진 오류(컨트롤러 앞 오류, 필터 밖의 /error 포함)든 같아야 해서 한 곳에서 정한다.
    //  - Content-Type: 클라이언트의 Accept와 상관없이 problem+json (Accept가 안 맞아 406이 난 응답에서 또 406이 나지 않게)
    //  - Access-Control-Allow-Origin: 브라우저 프런트가 다른 출처에서도 오류 본문을 읽을 수 있게 (성공 응답과 같은 값)
    //  - Access-Control-Expose-Headers: Retry-After는 CORS 안전 목록에 없어서 노출하지 않으면 브라우저 JS가 503의 Retry-After를 읽지 못한다
    //  - Cache-Control: no-store: 404와 405는 휴리스틱 캐시 대상이 될 수 있다(RFC 9110 15.1). 브라우저와 Cloudflare가 오류를 저장하지 않게 한다
    //  - Content-Language: title과 detail은 영어로 통일한다
    fun applyHeaders(headers: HttpHeaders): HttpHeaders {
        headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        headers.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        headers.set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.RETRY_AFTER)
        headers.cacheControl = "no-store"
        headers.contentLanguage = Locale.ENGLISH
        return headers
    }
}

// 검증 실패와 해석 실패의 `errors` 확장 멤버 한 건 (RFC 9457 4.1의 예시를 따른다).
// 요청 본문의 필드면 pointer(RFC 6901 JSON Pointer), 쿼리나 경로 입력이면 parameter를 쓴다. 둘 중 하나만 있다.
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProblemError(
    val code: String,
    val detail: String,
    val pointer: String? = null,
    val parameter: String? = null,
)
