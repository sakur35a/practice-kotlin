package com.example.diary

import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.core.StandardHost
import org.apache.catalina.valves.ErrorReportValve
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

// Tomcat이 Spring MVC에 닿기 전에 직접 거절하는 요청(경로의 퍼센트 인코딩 오류, 요청 줄이 HTTP가 아님, 너무 긴 URI나 헤더,
// 경로의 ../ )은 Spring의 오류 처리를 거치지 않고 Tomcat의 HTML 오류 페이지가 나간다. 그 응답도 RFC 9457 형식이 되도록
// 오류 보고 밸브를 바꾼다. 이 응답에는 요청에서 온 값(경로, 헤더)을 되돌려 보내지 않는다 (instance 멤버를 쓰지 않는 이유).
class ProblemErrorReportValve : ErrorReportValve() {
    override fun report(
        request: Request,
        response: Response,
        throwable: Throwable?,
    ) {
        val code = response.status
        if (code < 400 || response.contentWritten > 0 || !response.setErrorReported()) return

        val status =
            HttpStatus.resolve(code) ?: if (code >= 500) HttpStatus.INTERNAL_SERVER_ERROR else HttpStatus.BAD_REQUEST
        val detail =
            if (status.is5xxServerError) {
                "The server encountered an unexpected error."
            } else {
                "The request could not be processed."
            }
        val body = """{"detail":"$detail","status":$code,"title":"${status.reasonPhrase}"}"""

        try {
            response.contentType = "application/problem+json"
            response.characterEncoding = "UTF-8"
            response.setHeader("Access-Control-Allow-Origin", "*")
            response.setHeader("Access-Control-Expose-Headers", "Retry-After")
            response.setHeader("Cache-Control", "no-store")
            response.setHeader("Content-Language", "en")
            response.reporter?.let {
                it.write(body)
                response.finishResponse()
            }
        } catch (_: Exception) {
            // 오류 보고가 또 실패해도 더 할 수 있는 일이 없다 (Tomcat 기본 밸브와 같다)
        }
    }
}

@Component
class TomcatProblemCustomizer : WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    override fun customize(factory: TomcatServletWebServerFactory) {
        factory.addContextCustomizers({ context ->
            (context.parent as? StandardHost)?.errorReportValveClass = ProblemErrorReportValve::class.java.name
        })
    }
}
