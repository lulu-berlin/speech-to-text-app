# 0006 — Exposed, Flyway, and Kotest

Status: accepted · Date: 2026-10-05

## Context
The Kotlin backend (ADR 0001) needs database access to SQLite, versioned schema evolution, and a test framework that works well with coroutines and mandatory TDD (ADR 0009).

## Decision
- Database access: **Exposed** (SQL DSL).
- Schema migrations: **Flyway** with versioned SQL migrations.
- Tests: **Kotest**, plus `kotlinx-coroutines-test` for coroutine code (`runTest`, virtual time).

## Consequences
- The schema is defined in Flyway SQL files; Exposed table objects mirror it and must stay in sync.
- Exposed/JDBC calls are blocking and run inside `withContext(Dispatchers.IO)`.
- Engine and queue tests can use virtual time instead of real delays.
- The Kotest spec style is chosen with the user in roadmap item 02 and recorded in `docs/conventions/`.
