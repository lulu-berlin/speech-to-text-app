plugins {
    application
    kotlinJvm()
    kotlinSerialization()
}

group = "stt"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("ch.qos.logback:logback-classic")

    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("io.kotest:kotest-runner-junit5")
}

application {
    mainClass.set("stt.MainKt")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        includeEngines("kotest")
    }
}