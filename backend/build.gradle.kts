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
        // JDK 21 review guard: an app must not use an API newer than 21, because
        // `./gradlew run` executes it on JDK 21. jvmTarget alone only sets the classfile
        // version; a compiler running on a newer JDK (a human's launcher JVM) would
        // still accept newer APIs. -Xjdk-release makes the compiler reject them, and is a
        // no-op where Gradle already runs on JDK 21 (devenv, Nix).
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}
// Java sources (none today) get the same guarantee: -source/-target above do not limit
// the API surface, --release does.
tasks.compileJava.configure {
    options.release.set(21)
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

// The Windows start script names every jar as `%APP_HOME%\lib\<jar>` on one `set CLASSPATH=` line; under a deep
// checkout path cmd.exe expands it past its 8,191-character limit and refuses to start ("The input line is too
// long", roadmap item 79). Java's classpath wildcard names the same jars in one entry at any depth; the java call
// keeps quoting it, so cmd.exe passes `lib\*` through and Java expands it. The POSIX script keeps its jar list.
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val script = windowsScript
        val text = script.readText()
        val classpathLine = Regex("(?m)^set CLASSPATH=.*$")
        check(classpathLine.containsMatchIn(text)) { "no `set CLASSPATH=` line in $script" }
        script.writeText(text.replace(classpathLine, Regex.escapeReplacement("set CLASSPATH=%APP_HOME%\\lib\\*")))
    }
}

// Formatting gate (ADR 0015): `gradle check` runs ktlintCheck before the tests.
val ktlintMain = "com.pinterest.ktlint.Main"

// The backend's own Kotlin files (ktlint's default patterns, which any explicit pattern
// replaces) plus the root build scripts of the Gradle bootstrap (roadmap item 33): agents
// never run the root build, so this gate is the one their `gradle check` reaches. The Nix
// build's source holds only backend/, so there the root scripts do not exist and only the
// defaults apply.
val ktlintPatterns =
    listOf("**/*.kt", "**/*.kts") +
        listOf("build.gradle.kts", "settings.gradle.kts", "frontend/build.gradle.kts")
            .filter { rootDir.parentFile.resolve(it).isFile }
            .map { "../$it" }

tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "Check Kotlin formatting with ktlint (the backend and the root build scripts)"
    classpath(ktlintConfiguration)
    mainClass.set(ktlintMain)
    args(ktlintPatterns)
}

tasks.register<JavaExec>("ktlintFormat") {
    group = "verification"
    description = "Auto-format Kotlin sources with ktlint (the backend and the root build scripts)"
    classpath(ktlintConfiguration)
    mainClass.set(ktlintMain)
    args(listOf("--format") + ktlintPatterns)
}

tasks.named("check") {
    dependsOn("ktlintCheck")
}

// Full exception format for failing tests in the console (roadmap item 77). A separate script so
// TestLoggingIntegrationSpec can apply the same file to its fixture build.
apply(from = "gradle/test-logging.gradle.kts")

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        includeEngines("kotest")
    }
    // Gradle launches a test worker with gradle-worker.jar plus the project's own output
    // directories, so java.class.path inside the test JVM has no dependency jars. A spec that
    // forks a JVM (ClusteringHeapIntegrationSpec) needs the test runtime classpath, which only
    // the build knows.
    systemProperty(
        "stt.test.runtimeClasspath",
        sourceSets.test
            .get()
            .runtimeClasspath.asPath,
    )
    // The Gradle distribution running this build, so TestLoggingIntegrationSpec starts its fixture
    // build with the same Gradle (the devenv's locally, the wrapper's in CI). Not Gradle TestKit:
    // its jars carry Gradle's own SLF4J provider, which displaces logback in every test (item 77).
    gradle.gradleHomeDir?.let { systemProperty("stt.test.gradleHome", it.absolutePath) }
    // Each test JVM gets its own java.io.tmpdir under build/, recreated empty before the run. The
    // JDK default is the temp dir every process of the user shares (the Gradle daemon included), so
    // a spec that watches the app's temp dir for spool files (UploadStoredInDatabaseSpec) would
    // record files other processes write there (roadmap item 77).
    val privateTmpDir =
        layout.buildDirectory
            .dir("test-tmp/$name")
            .get()
            .asFile
    systemProperty("java.io.tmpdir", privateTmpDir.absolutePath)
    doFirst {
        privateTmpDir.deleteRecursively()
        privateTmpDir.mkdirs()
    }
}

// Unit default: `gradle test`/`gradle check` run no Integration-tagged tests
// (ADR 0021); `gradle integrationTest` runs only those. Kotest 6 filters via
// the kotest.tags expression (`!Tag` = exclude).
tasks.named<Test>("test") {
    systemProperty("kotest.tags", "!Integration")
    // WindowsStartScriptSpec measures the generated Windows start script (roadmap item 79).
    val startScripts = tasks.named<CreateStartScripts>("startScripts")
    dependsOn(startScripts)
    doFirst {
        systemProperty("stt.test.windowsStartScript", startScripts.get().windowsScript.absolutePath)
    }
}

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Run Integration-tagged tests (real processes), ADR 0021"
    useJUnitPlatform {
        includeEngines("kotest")
    }
    systemProperty("kotest.tags", "Integration")
    // Gradle 9 dropped the convention that pointed custom Test tasks at the `test`
    // source set: without these two lines the task is NO-SOURCE and runs nothing (ADR 0049).
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
}
