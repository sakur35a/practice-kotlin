package com.example.diary

import com.example.diary.diary.DuplicateDiaryIdException
import org.slf4j.LoggerFactory
import org.springframework.beans.TypeMismatchException
import org.springframework.context.MessageSourceResolvable
import org.springframework.core.NestedExceptionUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.FieldError
import org.springframework.validation.method.ParameterErrors
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import org.springframework.web.servlet.resource.NoResourceFoundException
import tools.jackson.core.JacksonException

// API의 모든 오류를 RFC 9457(Problem Details, application/problem+json)로 돌려준다. 오류 계약(상태 코드, type, 멤버)은 deploy/README.md에 있다.
// Spring MVC가 컨트롤러에 닿기 전에 내는 오류(404, 405, 406, 415, 본문 해석 실패, 파라미터 변환 실패, 검증 실패)는
// ResponseEntityExceptionHandler가 ProblemDetail로 만들고, 아래는 그 위에 이 API의 약속(type, errors, 헤더)을 얹는다.
// 서블릿 필터와 컨테이너가 내는 오류는 ProblemErrorController가 같은 모양으로 만든다.
//
// detail에는 내부 정보(예외 클래스, SQL, 스택)와 일기 내용을 넣지 않는다. 문구는 모두 고정된 영어다.
@RestControllerAdvice
class GlobalExceptionHandler(
    private val slackNotifier: SlackNotifier,
) : ResponseEntityExceptionHandler() {
    // 같은 id로 만들려 했다 (데이터베이스 기본 키 위반을 DiaryService가 옮긴 것). RFC 9110 15.5.10: 대상 리소스의 현재 상태와 충돌
    @ExceptionHandler(DuplicateDiaryIdException::class)
    fun handleDuplicateDiaryId(
        ex: DuplicateDiaryIdException,
        request: WebRequest,
    ): ResponseEntity<Any>? =
        problem(ex, HttpStatus.CONFLICT, request) {
            type = Problems.DUPLICATE_ID
            title = "Duplicate diary id"
            detail = "A diary with this id already exists."
        }

    // 위에서 따로 다루지 않은 예외. 데이터베이스를 쓸 수 없는 상황이면 503, 나머지는 진짜 서버 결함이라 500이다.
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(
        ex: Exception,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        if (ex is ErrorResponse) return handleExceptionInternal(ex, null, ex.headers, ex.statusCode, request)

        // 쿼리 문자열의 퍼센트 인코딩이 틀렸다(?size=%). Tomcat이 파라미터를 읽다가 던진다. 클라이언트 오류라서 400이다.
        // 서버 구현(Tomcat) 클래스에 컴파일 의존하지 않으려고 이름으로 확인한다
        if (ex.javaClass.name == "org.apache.tomcat.util.http.InvalidParameterException") {
            return malformedProblem(ex, "The query string contains an invalid percent-encoded sequence.", null, request)
        }

        if (DatabaseErrors.isUnavailable(ex)) {
            // RFC 9110 15.6.4(503)와 10.2.3(Retry-After): 잠시 뒤 다시 시도하면 된다. DB 재시작은 보통 몇 초 안에 끝난다
            val headers = HttpHeaders()
            headers[HttpHeaders.RETRY_AFTER] = RETRY_AFTER_SECONDS.toString()
            return problem(ex, HttpStatus.SERVICE_UNAVAILABLE, request, headers) {
                detail = "The database is temporarily unavailable. Try again later."
            }
        }

        return problem(ex, HttpStatus.INTERNAL_SERVER_ERROR, request) {
            detail = "The server encountered an unexpected error."
        }
    }

    // 본문(@RequestBody) 검증 실패. 문법은 맞는 JSON인데 제약을 어겼다 (RFC 9110 15.5.21 Unprocessable Content)
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errors =
            ex.bindingResult.allErrors.map { error ->
                ProblemError(
                    code = codeOf(error),
                    detail = error.defaultMessage ?: "is invalid",
                    pointer = (error as? FieldError)?.let { jsonPointer(it.field) } ?: "",
                )
            }

        return validationProblem(ex, HttpStatus.UNPROCESSABLE_CONTENT, errors, request)
    }

    // 쿼리 파라미터와 경로 변수 검증 실패(Spring의 메서드 검증). 요청 "내용"이 아니라 대상 URI의 일부가 틀렸으므로 400이다.
    // RFC 9110 15.5.21은 422를 "내용의 문법은 맞지만 담긴 지시를 처리할 수 없는 경우"로 한정한다. 본문이 같이 검증된 경우만 422다.
    override fun handleHandlerMethodValidationException(
        ex: HandlerMethodValidationException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        var bodyInvolved = false
        val errors =
            ex.parameterValidationResults.flatMap { result ->
                if (result is ParameterErrors) {
                    bodyInvolved =
                        bodyInvolved ||
                        result.methodParameter.hasParameterAnnotation(RequestBody::class.java)
                    result.allErrors.map { error ->
                        ProblemError(
                            code = codeOf(error),
                            detail = error.defaultMessage ?: "is invalid",
                            pointer = (error as? FieldError)?.let { jsonPointer(it.field) } ?: "",
                        )
                    }
                } else {
                    val name = parameterName(result.methodParameter)
                    result.resolvableErrors.map { error ->
                        ProblemError(
                            code = codeOf(error),
                            detail = error.defaultMessage ?: "is invalid",
                            parameter = name,
                        )
                    }
                }
            }

        return validationProblem(
            ex,
            if (bodyInvolved) HttpStatus.UNPROCESSABLE_CONTENT else HttpStatus.BAD_REQUEST,
            errors,
            request,
        )
    }

    // JSON 문법 오류, 본문 없음, 필드 타입이 맞지 않음 (RFC 9110 15.5.1: 요청을 해석할 수 없다)
    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        // 어느 필드인지만 알려 준다. Jackson 메시지에는 값이나 JSON 조각이 들어갈 수 있어서 쓰지 않는다
        val pointer = (ex.cause as? JacksonException)?.let { pointerOf(it) }
        val errors =
            pointer?.let {
                listOf(
                    ProblemError("invalid-value", "has a value of the wrong type or format", pointer = it),
                )
            }

        return malformedProblem(
            ex,
            "The request body could not be read as the expected JSON document.",
            errors,
            request,
        )
    }

    // 쿼리 파라미터나 경로 변수를 타입으로 바꿀 수 없다 (size=abc, /diary/abc)
    override fun handleTypeMismatch(
        ex: TypeMismatchException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val name = (ex as? MethodArgumentTypeMismatchException)?.name ?: ex.propertyName
        val errors = name?.let { listOf(ProblemError("invalid-format", "has an invalid format", parameter = it)) }

        return malformedProblem(ex, "A request parameter could not be read.", errors, request)
    }

    // 정적 리소스 핸들러의 기본 문구("No static resource ...")는 내부 구현을 드러내서 고정 문구로 바꾼다
    override fun handleNoResourceFoundException(
        ex: NoResourceFoundException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        ex.body.detail = "No resource exists at this path."

        return super.handleNoResourceFoundException(ex, headers, status, request)
    }

    // 모든 오류 응답이 지나는 곳: 공통 헤더를 붙이고, 서버 쪽 결함이면 기록하고 알린다
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        if (statusCode.is5xxServerError) reportServerFault(ex, statusCode)

        // 컨트롤러가 먼저 응답 헤더를 채운 뒤 오류가 날 수 있다(예: 응답 변환 단계의 406). 그때 성공 응답용 캐시 헤더
        // (Cloudflare-CDN-Cache-Control, Cache-Tag, ETag)가 오류에 남아 엣지가 오류를 저장하지 않도록 헤더를 모두 비우고 시작한다.
        (request as? ServletWebRequest)?.response?.takeIf { !it.isCommitted }?.reset()

        return super.handleExceptionInternal(ex, body, Problems.applyHeaders(HttpHeaders(headers)), statusCode, request)
    }

    private fun problem(
        ex: Exception,
        status: HttpStatus,
        request: WebRequest,
        headers: HttpHeaders = HttpHeaders(),
        customize: ProblemDetail.() -> Unit,
    ): ResponseEntity<Any>? =
        handleExceptionInternal(ex, ProblemDetail.forStatus(status).apply(customize), headers, status, request)

    private fun validationProblem(
        ex: Exception,
        status: HttpStatus,
        errors: List<ProblemError>,
        request: WebRequest,
    ) = problem(ex, status, request) {
        type = Problems.VALIDATION_ERROR
        title = "Validation failed"
        detail = "The request violates one or more constraints. See the errors member."
        // 검증기가 돌려주는 순서는 일정하지 않아서 정렬한다 (같은 요청은 항상 같은 응답)
        setProperty("errors", errors.sortedWith(compareBy({ it.pointer ?: it.parameter }, { it.code })))
    }

    private fun malformedProblem(
        ex: Exception,
        detail: String,
        errors: List<ProblemError>?,
        request: WebRequest,
    ) = problem(ex, HttpStatus.BAD_REQUEST, request) {
        type = Problems.MALFORMED_REQUEST
        title = "Malformed request"
        this.detail = detail
        if (errors != null) setProperty("errors", errors)
    }

    // 서버 쪽 결함(5xx)은 예외 "타입"만 기록한다. 예외 메시지에는 DB 오류를 통해 일기 내용(개인 정보)이 섞일 수 있다.
    // Slack은 종류마다 프로세스당 한 번 알린다: 데이터베이스 장애(503)와 그 밖의 서버 결함(500)을 따로 센다.
    // 클라이언트 오류(4xx)는 기록도 알림도 하지 않는다 (요청 로그에 상태 코드가 남는다).
    private fun reportServerFault(
        ex: Exception,
        status: HttpStatusCode,
    ) {
        // 데이터베이스가 내려간 동안에는 요청마다 같은 줄이 쌓이므로 503은 WARN으로 낮춘다
        val rootCause = NestedExceptionUtils.getMostSpecificCause(ex).javaClass.name
        if (status.value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            faultLogger.warn(
                "layer=http action=server_fault status={} type={} rootCause={}",
                status.value(),
                ex.javaClass.name,
                rootCause,
            )
        } else {
            faultLogger.error(
                "layer=http action=server_fault status={} type={} rootCause={}",
                status.value(),
                ex.javaClass.name,
                rootCause,
            )
        }

        if (status.value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            slackNotifier.notifyOnce("database-unavailable", "diary API: database unavailable (503)")
        } else {
            slackNotifier.notifyOnce("server-fault", "diary API: unexpected server error (${status.value()})")
        }
    }

    private fun codeOf(error: MessageSourceResolvable): String =
        CODES[error.codes?.firstOrNull()?.substringBefore('.')] ?: "invalid"

    private fun parameterName(parameter: org.springframework.core.MethodParameter): String? =
        parameter.getParameterAnnotation(RequestParam::class.java)?.name?.ifEmpty { null }
            ?: parameter.getParameterAnnotation(PathVariable::class.java)?.name?.ifEmpty { null }
            ?: parameter.parameterName

    private fun pointerOf(ex: JacksonException): String? {
        val path = ex.path
        if (path.isEmpty()) return null

        return path.joinToString(separator = "") { reference ->
            "/" + (reference.propertyName?.let(::escapePointerToken) ?: reference.index.toString())
        }
    }

    private fun jsonPointer(field: String): String =
        "/" + field.split('.').joinToString("/", transform = ::escapePointerToken)

    // RFC 6901 3: '~'는 "~0", '/'는 "~1"
    private fun escapePointerToken(token: String): String = token.replace("~", "~0").replace("/", "~1")

    private companion object {
        // 상위 클래스(ResponseEntityExceptionHandler)에 이미 logger 필드가 있어서 다른 이름을 쓴다
        private val faultLogger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

        const val RETRY_AFTER_SECONDS = 5

        // errors[].code: 제약 이름을 클라이언트가 기대어도 되는 안정적인 코드로 옮긴다 (deploy/README.md에 같은 표가 있다)
        val CODES =
            mapOf(
                "NotNull" to "required",
                "MaxCodePoints" to "too-long",
                "UuidV7" to "not-uuid-v7",
                "NoNul" to "nul-character",
                "Min" to "too-small",
                "Max" to "too-large",
            )
    }
}
