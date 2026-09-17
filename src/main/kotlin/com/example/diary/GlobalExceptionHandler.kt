package com.example.diary

import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.client.RestClient

@RestControllerAdvice
class GlobalExceptionHandler(
    restClientBuilder: RestClient.Builder,
    @Value("\${slack.webhook-url}") private val slackWebhookUrl: String,
) {
    private val restClient = restClientBuilder.build()

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(ex: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            ex.message ?: "Invalid request",
        )

    @ExceptionHandler(DataAccessResourceFailureException::class)
    fun handleDataAccessResourceFailureException(ex: DataAccessResourceFailureException) =
        restClient
            .post()
            .uri(slackWebhookUrl)
            .body(sendSlack("send from DataAccessResourceFailureException"))
            .retrieve()
            .toBodilessEntity()

    @ExceptionHandler(Exception::class)
    fun handleException(ex: Exception) =
        restClient
            .post()
            .uri(slackWebhookUrl)
            .body(sendSlack("send from Exception"))
            .retrieve()
            .toBodilessEntity()

    private fun sendSlack(message: String) {
        restClient
            .post()
            .uri(slackWebhookUrl)
            .body(mapOf("text" to message))
            .retrieve()
            .toBodilessEntity()
    }
}
