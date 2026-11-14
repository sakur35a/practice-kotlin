plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    kotlin("plugin.jpa") version "2.3.21"
    kotlin("plugin.allopen") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"

version = providers.gradleProperty("appVersion").orElse("0.0.1-SNAPSHOT").get()

description = "diary"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    implementation("com.fasterxml.uuid:java-uuid-generator:5.1.0")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    runtimeOnly("org.postgresql:postgresql")
    // PostgreSQL 유닉스 소켓(peer 인증)으로 붙을 때만 쓴다. pgjdbc는 소켓을 직접 열 줄 몰라서 소켓 팩토리가 필요하다.
    // JDBC URL의 socketFactory=... 한 줄을 바꾸는 것으로 TCP와 소켓 중에 고른다 (deploy/README.md)
    runtimeOnly("com.kohlschutter.junixsocket:junixsocket-core:2.11.1")

    testImplementation("org.mockito.kotlin:mockito-kotlin:6.1.0")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-Xannotation-default-target=param-property",
        )
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// 배포 스크립트(deploy/hoody)가 파일 이름을 고정해서 쓴다
tasks.bootJar {
    archiveFileName.set("diary.jar")
}
