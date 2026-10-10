# 0042 — Catalog versions are authoritative via `resolutionStrategy.force`

Status: accepted, amends [0014](0014-build-tooling-gradle-catalog-no-wrapper.md) (implementation detail) · Date: 2026-10-10

## Context

ADR 0014 made `gradle/libs.versions.toml` the single source of dependency versions, but the catalog entry
is only an advisory pin: Gradle's highest-wins conflict resolution lets a transitive dependency resolve over
it. That happened for the backend's `sqlite-jdbc` pin: `org.flywaydb:flyway-database-nc-sqlite:13.9.0`
declared sqlite-jdbc 3.50.3.0, which resolved over the 3.49.1.0 catalog pin. Every test suite — and item 26's
flaky-failure diagnosis, which decompiled the driver — ran against 3.50.3.0 while the catalog still claimed
3.49.1.0 (roadmap item 27).

## Decision

For any dependency where a transitive must never resolve over the catalog, the backend forces the catalog
version in Gradle config:

```kotlin
configurations.configureEach {
    resolutionStrategy.force(libs.sqlite.jdbc)
}
```

The force references the catalog accessor (`libs.sqlite.jdbc`), so the catalog stays the single source of
truth: changing the version means editing only `gradle/libs.versions.toml` (and the pinned test's expected
version, commented to travel with the pin).

## Consequences

- The resolution, the catalog and the runtime driver agree; `gradle dependencyInsight` shows the version
  marked `(forced)`.
- A future sqlite-jdbc bump is a one-line catalog change; nothing renegotiates under it.
- `backend/deps.json` (the Gradle→Nix lockfile, ADR 0014/0016) is unchanged when the forced version equals
  the version that already resolved.
- This strict pinning is opt-in per dependency: most catalog entries keep ordinary conflict resolution, and
  the pattern is applied only where a transitive override would be harmful or surprising.