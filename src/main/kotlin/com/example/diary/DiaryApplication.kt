package com.example.diary

import org.jooq.DSLContext
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class DiaryApplication {
    @Bean
    fun jooqWarmup(dsl: DSLContext) = SmartInitializingSingleton { dsl.selectOne().fetch() }
}

fun main(args: Array<String>) {
    runApplication<DiaryApplication>(*args)
}
