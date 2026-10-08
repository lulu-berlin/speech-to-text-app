# 0025 — Nix build uses Gradle's default JVM memory settings

Status: accepted · Date: 2026-10-08

## Context
`flake.nix` passes `-Dorg.gradle.jvmargs=-Xmx384m -XX:MaxMetaspaceSize=256m` to the backend build. The cap was added on 2026-10-05, when the Nix sandbox OOM-killed the Gradle daemon running beside a separate Kotlin compile daemon and ktlint. [ADR 0022](0022-kotlin-compiler-in-process.md) later moved Kotlin compilation into the Gradle daemon and kept Gradle's default heap settings for development, but the flake's cap stayed.

On 2026-10-08 `nix build .` failed in `:compileKotlin` with `OutOfMemoryError: Metaspace` both on `main` (05526bf) and on the roadmap item 08 branch, so the failure is not caused by item 08. With the devenv toolchain (JDK 21.0.12.1, Gradle 8.14.4), a clean in-process `compileKotlin` under the same flags fails at 160 MB metaspace and passes from 176 MB, identically on `main` and on item 08. Inside the Nix build it exceeds 256 MB. The flake's nixpkgs pin differs from devenv's, which probably explains the difference; this was not verified.

## Decision
- `flake.nix` sets no `org.gradle.jvmargs`: the Nix build uses Gradle's defaults (`-Xmx512m -XX:MaxMetaspaceSize=384m`), the same as the development build under ADR 0022.
- Kotlin compilation stays in-process in the Nix build (ADR 0022).

## Consequences
- Development and Nix builds run with the same JVM memory settings; one less Nix-only knob.
- The reason for the original cap (a separate Kotlin daemon next to the Gradle daemon) no longer exists. If the Nix sandbox OOM-kills the build again, or the defaults run out as the code grows, the settings are changed in a new ADR, as ADR 0022 already requires.
- Whether `nix build .` passes with the defaults is verified by the user (no Nix in the agent sandbox).
