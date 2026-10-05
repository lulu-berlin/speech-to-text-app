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

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host.jvm)
    testImplementation(libs.kotest.runner.junit5)
}

application {
    mainClass.set("stt.MainKt")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        includeEngines("kotest")
    }
}