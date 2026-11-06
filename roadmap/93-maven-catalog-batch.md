# 93 — Current Maven dependencies: the catalog batch and the regenerated deps.json

Status: spec-approved
Depends on: [03](../docs/specs/database-schema.md) (done; the migrations), [08](../docs/specs/speaker-embeddings.md) (done; the ONNX Runtime pin and item 30's regression history)
Blocks: —

## Goal

The backend builds on current Maven dependencies — Ktor 3.6.1, Kotest 6.2.6, Logback 1.6.6, Exposed
1.6.0, Flyway 13.12.0, sqlite-jdbc 3.54.1.0, JNA 5.19.1, ONNX Runtime 1.31.1 — with the same
behaviour, `backend/deps.json` regenerated, and Kotlin/kotlinx-serialization still on Ktor's baseline.

## Evidence

- **The catalog** (`backend/gradle/libs.versions.toml`): Kotlin 2.3.21, Ktor 3.6.0, Kotest 6.2.5,
  Logback 1.6.5, kotlinx-coroutines 1.11.0, Exposed 1.5.0, Flyway 13.9.0, sqlite-jdbc 3.50.3.0, JNA
  5.14.0, ONNX Runtime 1.31.0. Latest on Maven Central (checked 2026-11-04): Ktor **3.6.1**, Kotest
  **6.2.6**, Logback **1.6.6**, Exposed **1.6.0**, Flyway **13.12.0**, sqlite-jdbc **3.54.1.0**, JNA
  **5.19.1**, ONNX Runtime **1.31.1**; coroutines 1.11.0 and ktlint 1.8.0 are current.
- **Kotlin stays paired with Ktor**: stable Kotlin is 2.4.21 and kotlinx-serialization 1.12.0, but
  the catalog's comment pins the pair to what Ktor is built against ("Ktor 3.6.0 is built against
  Kotlin 2.3.21 and kotlinx-serialization 1.11.0; keep the pair when bumping"). Ktor 3.6.1 is still
  the 2.3 line, so Kotlin does not move independently.
- **ONNX Runtime carries item 30's history**: 1.29 and 1.30 segfaulted on one machine
  (CPU-dispatched recursion), 1.26–1.28 degraded CAM++ embeddings everywhere; 1.31.0 is the next
  release on the safe line, and 1.31.1 is a patch on it that must pass the same integration
  evidence. sqlite-jdbc and onnxruntime are force-pinned in `backend/build.gradle.kts:40-46`
  (ADR 0042).
- **`backend/deps.json` is the Nix Gradle lock**: a dependency change regenerates it with
  `nix build .#backend.mitmCache.updateScript --print-out-paths` and the printed script
  (`flake.nix`'s comment), and a stale lock reds the Nix build.
- **The suites that judge it**: `gradle check`, the Integration-tagged suites (real processes,
  migrations on a real SQLite file, ONNX diarization) and the e2e journeys.

## Decided (consultation, 2026-11-04)

- Filed at the user's request from the dependency scan: the catalog batch in one item, because the
  versions pair (Kotlin/serialization) and `deps.json` make piecemeal bumps more expensive than one.
- The forced pins (ADR 0042) stay forced; their versions move, not the mechanism.
- **Exempt from ADR 0046's per-feature e2e test** (the user): no app feature changes; the existing
  journeys and the Integration suites running on the new versions are the proof.

## Proposal

1. Bump the catalog entries listed above; leave Kotlin, kotlinx-serialization and ktlint where they
   are. Confirm Ktor 3.6.1's expected Kotlin/serialization pair from its POM before starting.
2. Fix whatever API changes the minors require (Exposed, Flyway, sqlite-jdbc, JNA), keeping the
   schema, the migrations and the queries as they are. Two checks first, from the release notes:
   whether Exposed 1.6.0's changes reach the DSL/DAO surface the backend uses, and whether Flyway
   13.10–13.12 changes `baseline`/`validate` behaviour for existing `stt.db` files.
3. Re-run `gradle check`, the integration suites and the e2e journeys; re-run the ONNX Runtime
   regression scenarios item 30 records (the crash and the degraded embeddings).
4. Regenerate `backend/deps.json`, then verify `nix build .` (user-run) and `checkVersions`.
5. Update the catalog's comments (the Ktor pair and ADR 0042's forced pins) where versions moved.

## Open questions

None. (Settled 2026-11-04: the Exposed and Flyway questions are facts to check first, proposal step
2.)

## Test list

- [ ] (basic) `gradle check` and the Integration-tagged suites pass on the new versions.
- [ ] (basic) The migrations apply to a fresh database and to an existing one, and the queue and
      history behaviour is unchanged.
- [ ] (basic) SQLite reads and writes go through the new sqlite-jdbc under ADR 0042's forced pin, and
      only one sqlite-jdbc version is on the classpath.
- [ ] (basic) Diarization yields the same speaker turns on ONNX Runtime 1.31.1: the item-30
      regression scenarios (the crash and the degraded-embedding case) stay green.
- [ ] (edge) Ktor 3.6.1's server, DI, content negotiation and SSE endpoints behave as their specs
      say: an upload error and a stream drop keep their current handling.
- [ ] (edge) Logback's output pattern and levels are unchanged, and Kotest's tags still split the
      unit and Integration suites.
- [ ] (must not break) Kotlin and kotlinx-serialization stay on Ktor's pair, and ktlint stays at its
      current version.
- [ ] (must not break) `backend/deps.json` is regenerated, and `nix build .` (user-run),
      `checkVersions` and the e2e journeys are green with it.

## e2e stance

Exempt from ADR 0046's per-feature e2e test (decided 2026-11-04): no app feature changes, and the
existing journeys and Integration suites running on the new versions are its proof.

## Approval log

- 2026-11-04: filed as a draft by the root agent from the dependency scan (Maven Central, the eight
  catalog entries behind their latest release).
- 2026-11-04: consultation with the user: the Exposed/Flyway questions become checks in proposal
  step 2; exempt from ADR 0046. Test list approved as written; status set to spec-approved.
