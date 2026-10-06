# 0015 — Code formatting: ktlint and prettier

Status: accepted · Date: 2026-10-05

## Context
The user requires automatic code formatting, with running it a requirement of the standard build/test commands. Repository rules mandate tools come from the project configuration; the backend needs no new devenv tool (the formatter resolves through Gradle).

## Decision
- Kotlin (.kt and .kts) is formatted with **ktlint** (1.8.0), run as a Gradle task over the `ktlint-cli` artifact (`com.pinterest.ktlint:ktlint-cli`, pinned in `libs.versions.toml`). There is no maintained first-party Gradle plugin, so the project uses ktlint's documented custom integration: `ktlintCheck` and `ktlintFormat` `JavaExec` tasks.
- **Enforcement:** `gradle check` depends on `ktlintCheck` (which runs before `test`); `gradle build` and `gradle check` fail on format violations. `gradle ktlintFormat` auto-fixes.
- TypeScript/TSX/JS is formatted with **prettier** (`.prettierrc`: semi, single quotes, print width 100, trailing commas); `prettier --check` runs inside `pnpm test` once the frontend exists, so the frontend's standard test command fails on violations.
- A root `.editorconfig` drives both tools: UTF-8, LF, final newline, trailing-whitespace trim; 4-space indent for Kotlin, 2-space for frontend files.

## Consequences
- Unformatted code cannot pass the standard verification commands (`gradle check`, `pnpm test`).
- ktlint's default standard rules (import ordering, class-signature layout, final newline) are adopted; existing code was migrated with `gradle ktlintFormat` in a dedicated refactor commit.
- Formatting changes are mechanical and reviewed as their own commits, never mixed into behavior `Test:` cycles.
- A ktlint upgrade changes the enforced style only on the next `ktlintFormat` run; pinning the version in the catalog keeps style stable.
