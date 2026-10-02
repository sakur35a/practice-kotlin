import org.springframework.boot.gradle.tasks.bundling.BootBuildImage

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
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    implementation("com.fasterxml.uuid:java-uuid-generator:5.1.0")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-mysql")

    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation("org.mockito.kotlin:mockito-kotlin:6.1.0")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-mysql")
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

tasks.named<BootBuildImage>("bootBuildImage") {
    val imageRepository = "ghcr.io/sakur35a/practice-kotlin"

    imageName.set("$imageRepository:${project.version}")
    tags.set(listOf("$imageRepository:latest"))
    createdDate.set("now")
    environment.set(
        mapOf(
            // 빌드 중 훈련 실행으로 JVM AOT 캐시를 만들어 이미지에 담는다 (기동 시간 단축)
            "BP_JVM_AOTCACHE_ENABLED" to "true",
            "TRAINING_RUN_JAVA_TOOL_OPTIONS" to "-XX:TieredStopAtLevel=1 -Dspring.profiles.active=training",
        ),
    )
    imagePlatform.set("linux/amd64")

    docker {
        publishRegistry {
            username.set(providers.environmentVariable("GHCR_USERNAME"))
            password.set(providers.environmentVariable("GHCR_TOKEN"))
        }
    }
}
