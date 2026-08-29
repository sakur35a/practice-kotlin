import org.springframework.boot.gradle.tasks.bundling.BootBuildImage

buildscript {
    dependencies {
        classpath("org.flywaydb:flyway-database-postgresql:13.0.0")
    }
}

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"

    id("org.flywaydb.flyway") version "13.0.0"
    id("org.jooq.jooq-codegen-gradle") version "3.21.5"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "com.example"
version =
    providers
        .gradleProperty("appVersion")
        .orElse("0.0.1-SNAPSHOT")
        .get()
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
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    implementation("com.fasterxml.uuid:java-uuid-generator:5.1.0")
    implementation("com.github.ben-manes.caffeine:caffeine")

    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")

    runtimeOnly("org.postgresql:postgresql")

    jooqCodegen("org.postgresql:postgresql")

    implementation("io.github.oshai:kotlin-logging-jvm:7.0.13")

    testImplementation("org.mockito.kotlin:mockito-kotlin:6.1.0")
    testImplementation("org.springframework.boot:spring-boot-jooq-test")
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

sourceSets {
    main {
        java {
            srcDir("src/jooq/java")
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

flyway {
    url = "jdbc:postgresql://localhost:5432/mydb"
    user = "myuser"
    password = "mypassword"
    schemas = arrayOf("public")
}

jooq {
    configuration {
        jdbc {
            driver = "org.postgresql.Driver"
            url = "jdbc:postgresql://localhost:5432/mydb"
            user = "myuser"
            password = "mypassword"
        }

        generator {
            name = "org.jooq.codegen.JavaGenerator"

            database {
                name = "org.jooq.meta.postgres.PostgresDatabase"
                inputSchema = "public"
                excludes = "flyway_schema_history"

                forcedTypes {
                    forcedType {
                        userType = "java.time.Instant"
                        autoConverter = true
                        includeExpression = ".*\\.CREATED_AT"
                    }
                }
            }

            generate {
                isPojos = false
                isDaos = false
                isWhereMethodOverrides = false
            }

            target {
                packageName = "com.example.jooq.generated"
                directory =
                    layout.projectDirectory
                        .dir("src/jooq/java")
                        .asFile.absolutePath
            }
        }
    }
}

val migrationFiles = fileTree("src/main/resources/db/migration")

tasks.named("flywayMigrate") {
    inputs.files(migrationFiles)
}

tasks.named("jooqCodegen") {
    dependsOn(tasks.named("flywayMigrate"))
    inputs.files(migrationFiles)
}

tasks.named<BootBuildImage>("bootBuildImage") {
    val imageRepository = "ghcr.io/sakur35a/practice-kotlin"

    imageName.set("$imageRepository:${project.version}")
    tags.set(listOf("$imageRepository:latest"))
    createdDate.set("now")
    imagePlatform.set("linux/amd64")

    docker {
        publishRegistry {
            username.set(providers.environmentVariable("GHCR_USERNAME"))
            password.set(providers.environmentVariable("GHCR_TOKEN"))
        }
    }
}
