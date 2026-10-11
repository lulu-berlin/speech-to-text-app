plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "stt"
version = "0.1.0-SNAPSHOT"

// Roadmap item 33: the app's bytecode targets the flake-pinned JDK 21 (versions.json)
// no matter which recent JVM runs Gradle; `./gradlew run` boots the app on a
// toolchain-resolved JDK 21 (foojay for humans). In devenv and the Nix build the
// Gradle JVM is already 21, so nothing changes there (ADR 0036).
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

repositories {
    mavenCentral()
}

// Forced at the catalog version (ADR 0042): flyway-database-nc-sqlite once resolved a
// newer sqlite-jdbc over the catalog pin via highest-wins; onnxruntime is pinned at 1.31.0
// because 1.29.0/1.30.0's native lib segfault on one machine (roadmap item 30; 1.26.0-1.28.0
// rejected: degraded embeddings) and a transitive must never pull it back up. The catalog stays
// the single source of truth — change the version in gradle/libs.versions.toml, not here.
configurations.configureEach {
    resolutionStrategy.force(libs.sqlite.jdbc)
    resolutionStrategy.force(libs.onnxruntime)
}

// Custom configuration: ktlint CLI for the formatting check (ADR 0015).
// No type-safe accessor exists for script-defined configurations, so the standard API is used.
val ktlintConfiguration = configurations.create("ktlint")

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.di)
    implementation(libs.jna)
    implementation(libs.logback.classic)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.slf4j)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.nc.sqlite)
    implementation(libs.sqlite.jdbc)
    implementation(libs.onnxruntime)

    testImplementation(libs.ktor.server.test.host.jvm)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotlinx.coroutines.test)

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

// Unit default: `gradle test`/`gradle check` run no Integration-tagged tests
// (ADR 0021); `gradle integrationTest` runs only those. Kotest 6 filters via
// the kotest.tags expression (`!Tag` = exclude).
tasks.named<Test>("test") {
    systemProperty("kotest.tags", "!Integration")
}

val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Run Integration-tagged tests (real processes), ADR 0021"
    useJUnitPlatform {
        includeEngines("kotest")
    }
    systemProperty("kotest.tags", "Integration")
}
