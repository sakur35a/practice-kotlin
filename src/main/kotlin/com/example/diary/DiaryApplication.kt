package com.example.diary

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.core.Ordered
import org.springframework.resilience.annotation.EnableResilientMethods

@SpringBootApplication
// SlackProperties, DiaryProperties 같은 설정 클래스를 찾아 등록한다
@ConfigurationPropertiesScan
// 재시도가 트랜잭션 바깥에서 돌아야 매 시도마다 새 연결과 새 트랜잭션을 쓴다
@EnableResilientMethods(order = Ordered.LOWEST_PRECEDENCE - 1)
class DiaryApplication

fun main(args: Array<String>) {
    runApplication<DiaryApplication>(*args)
}
