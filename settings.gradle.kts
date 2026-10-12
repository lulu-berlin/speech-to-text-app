// Root Gradle build for human developers without Nix (roadmap item 33).
// The foojay toolchain resolver is root-only: any recent JVM runs gradlew, and
// ./gradlew run resolves the flake-pinned JDK 21 (versions.json) as the app's
// runtime. The backend and frontend builds never run through Nix, so the flake's
// locked Gradle dependency cache is unaffected by this plugin.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "stt-app"

// The backend stays a standalone Gradle build (devenv's `cd backend && gradle
// check`, ADR 0014) and is included here as a composite build; the frontend is a
// node-plugin subproject because frontend/ itself has no Gradle build.
includeBuild("backend")
include(":frontend")
