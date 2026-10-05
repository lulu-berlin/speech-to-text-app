plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "stt"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// Custom configuration: ktlint CLI for the formatting check (ADR 0015).
// No type-safe accessor exists for script-defined configurations, so the standard API is used.
val ktlintConfiguration = configurations.create("ktlint")

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host.jvm)
    testImplementation(libs.kotest.runner.junit5)

    add("ktlint", libs.ktlint.cli)
}

application {
    mainClass.set("stt.MainKt")
}

// Formatting gate (ADR 0015): `gradle check` runs ktlintCheck before the tests.
val ktlintMain = "com.pinterest.ktlint.Main"

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Check Kotlin formatting with ktlint"
    classpath(ktlintConfiguration)
    mainClass.set(ktlintMain)
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "verification"
    description = "Auto-format Kotlin sources with ktlint"
    classpath(ktlintConfiguration)
    mainClass.set(ktlintMain)
    args("--format")
}

tasks.named("check") {
    dependsOn("ktlintCheck")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        includeEngines("kotest")
    }
}
