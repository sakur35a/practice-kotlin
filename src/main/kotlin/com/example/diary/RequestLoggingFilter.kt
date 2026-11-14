package com.example.diary

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class RequestLoggingFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val startedAt = System.nanoTime()

        try {
            filterChain.doFilter(request, response)
        } finally {
            requestLogger.info(
                "layer=http method={} path={} status={} elapsedMs={}",
                request.method,
                request.requestURI,
                response.status,
                (System.nanoTime() - startedAt) / 1_000_000,
            )
        }
    }

    // 상위 클래스(GenericFilterBean)에 이미 logger 필드가 있어서 다른 이름을 쓴다
    private companion object {
        private val requestLogger = LoggerFactory.getLogger(RequestLoggingFilter::class.java)
    }
}
