package com.example.diary

import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.client.RestClient

@RestControllerAdvice
class GlobalExceptionHandler(@Value("\${slack.webhook-url}") private val slackWebhookUrl: String) {
    private val restClient = RestClient.create()
    private var sendSlack = true

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(ex: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            ex.message ?: "Invalid request",
        )

    // 반환값이 없으면 빈 본문의 200이 나가서 DB 장애가 성공처럼 보인다
    @ExceptionHandler(DataAccessResourceFailureException::class, TransientDataAccessException::class)
    fun handleDataAccessResourceFailureException(ex: Exception): ProblemDetail {
        if (sendSlack) {
            sendSlack = false

            try {
                sendSlack("send from DataAccessResourceFailureException")
            } catch (_: Exception) {
                // Slack 장애가 원래 장애를 증폭시키면 안 됨
            }
        }

        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Database unavailable")
    }

    @ExceptionHandler(Exception::class)
    fun handleException(ex: Exception): ProblemDetail {
        // 404, 405 같은 Spring MVC 예외는 원래 상태를 유지한다
        if (ex is ErrorResponse) return ex.body

        if (sendSlack) {
            sendSlack = false

            try {
                sendSlack("send from Exception")
            } catch (_: Exception) {
                // Slack 장애가 원래 장애를 증폭시키면 안 됨
            }
        }

        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error")
    }

    private fun sendSlack(message: String) {
        restClient
            .post()
            .uri(slackWebhookUrl)
            .body(mapOf("text" to message))
            .retrieve()
            .toBodilessEntity()
    }
}
