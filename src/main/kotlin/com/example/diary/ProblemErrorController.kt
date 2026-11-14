package com.example.diary

import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.core.NestedExceptionUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

// 서블릿 컨테이너가 /error로 넘기는 오류(필터에서 난 예외, Spring MVC 바깥에서 난 오류)를 Spring Boot 기본 오류 JSON 대신
// RFC 9457 형식으로 만든다. Spring MVC 안에서 난 오류는 GlobalExceptionHandler가 먼저 처리하므로 여기에 오지 않는다.
// ERROR 디스패치에는 서블릿 필터(HttpCacheFilter의 CORS 헤더 포함)가 적용되지 않아서 헤더도 여기서 직접 붙인다.
@RestController
class ProblemErrorController(
    private val slackNotifier: SlackNotifier,
) : ErrorController {
    @RequestMapping("/error")
    fun error(request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int
        // 오류 디스패치 없이 /error를 직접 부르면 이 경로 자체가 없는 것처럼 404로 답한다
        val status =
            code?.let { HttpStatus.resolve(it) }
                ?: if (code == null) HttpStatus.NOT_FOUND else HttpStatus.INTERNAL_SERVER_ERROR
        val problem = ProblemDetail.forStatusAndDetail(status, detailOf(status))
        (request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) as? String)?.let {
            problem.instance =
                URI.create(it.substringBefore('?'))
        }

        if (status.is5xxServerError) {
            val cause = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) as? Throwable
            logger.error(
                "layer=http action=server_fault status={} type={} rootCause={}",
                status.value(),
                cause?.javaClass?.name,
                cause?.let { NestedExceptionUtils.getMostSpecificCause(it).javaClass.name },
            )
            slackNotifier.notifyOnce("server-fault", "diary API: unexpected server error (${status.value()})")
        }

        return ResponseEntity.status(status).headers(Problems.applyHeaders(HttpHeaders())).body(problem)
    }

    private fun detailOf(status: HttpStatus): String =
        when {
            status == HttpStatus.NOT_FOUND -> "No resource exists at this path."
            status.is5xxServerError -> "The server encountered an unexpected error."
            else -> "The request could not be processed."
        }

    private companion object {
        private val logger = LoggerFactory.getLogger(ProblemErrorController::class.java)
    }
}
