# 0049 — Gradle 9.8 in every build path; the root wrapper is for humans without Nix

Status: accepted, scopes 0014's "no Gradle wrapper" · Date: 2026-10-12

## Context
Roadmap item 33 adds a committed Gradle wrapper so that a developer with only a JVM can run `./gradlew run`; its consultation pinned the wrapper to the flake's Gradle (8.14.4 at the locked nixpkgs rev). The first run on an Ubuntu container with Java 25.0.4.1 failed before any build script ran: `What went wrong: 25.0.4.1`. Gradle 8.14 compiles `.kts` scripts with its embedded Kotlin 2.0.21, which cannot parse a Java 25 version string; Gradle 8.14 supports running on Java up to 24. Java 25 has been the current LTS since 2025-09, so "any recent JVM runs gradlew" did not hold. The locked nixpkgs already ships `gradle_9` (9.8.0, embedded Kotlin 2.4.10, runs on Java 17–27 per Gradle's compatibility matrix).

ADR 0014 decided "no Gradle wrapper": agents build with the devenv's Gradle and the Nix build with nixpkgs' Gradle, and a wrapper would be a second version source. Item 33's consultation (user, 2026-10-11) scoped that rule: it governs agents, which always work in devenv. The wrapper and its downloads exist for human developers who use neither Nix nor an agent.

## Decision
- **One Gradle, 9.8.0, in all three paths** (user, 2026-10-12): the flake builds the backend with `pkgs.gradle_9.override { java = pkgs.jdk; }`; the devenv provides the same package through `languages.java.gradle` (the devenv no longer uses `languages.kotlin`, which hard-codes nixpkgs' default `gradle`); the committed wrapper downloads 9.8.0 with a pinned `distributionSha256Sum`. `versions.json` records the flake's Gradle version, and `./gradlew checkVersions` and the flake's `versions-sync` check fail when the wrapper or the manifest drifts (item 33).
- **Gradle runs on the runtime JDK 21** in the flake and the devenv, not on `gradle_9`'s default JDK 25: the build and test JVM stay equal to the app's runtime JDK and to the environment item 16 verified. The backend's bytecode is pinned to 21 (Kotlin `jvmTarget`, Java `targetCompatibility`), so a human's newer launcher JVM produces the same app; `./gradlew run` starts the app on a toolchain-resolved JDK 21 (foojay, root build only).
- **The root wrapper is for human developers without Nix.** Agents keep running the devenv's `gradle` (`cd backend && gradle check`, ADR 0014); they neither use nor remove `gradlew`. The backend stays a standalone build that the root build includes as a composite build.

## Consequences
- A Gradle bump is one coordinated change: the nixpkgs pin (flake and devenv, ADR 0036), the regenerated `versions.json` (`nix run .#update-versions`), the wrapper (`gradle wrapper --gradle-version … --gradle-distribution-sha256-sum …`) and `backend/deps.json` (the `mitmCache` update script). The checks name the missing step.
- Gradle 9 removed conventions that 8.x only deprecated: custom `Test` tasks set `testClassesDirs` and `classpath` explicitly (without them `integrationTest` became NO-SOURCE and silently ran nothing).
- Gradle 9's HTML test report writes one directory per test name, so test names with non-ASCII characters need UTF-8 file names in the JVM: the devenv and the flake's backend build set `LC_CTYPE=C.UTF-8`; humans who run the backend tests through Gradle need a UTF-8 locale (the README says so). `./gradlew run` does not run tests and needs none.
- Humans can launch `gradlew` on Java 17 or newer (Gradle 9.8 runs on Java 17–27; tested with 25).
