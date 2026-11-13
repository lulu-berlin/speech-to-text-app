# 107 — The backend rewritten in Rust, phase 8: app mode

Status: spec-approved
Depends on: [106](106-rust-backend-http-and-outputs.md)
Blocks: [108](108-rust-backend-switch.md)

## Goal

The service as the desktop app starts it: the platform data directories, the config file and its
secret, the durable port with its fallback, loopback-only binding, private file permissions, one
instance per user, `quit`, the tray supervisor's protocol, the clean stop on SIGTERM and stdin
close, and the readable errors when the config is unreadable.

## Evidence

`stt/service` (13 suites, 20 scenarios) plus the root `StopOnStdinCloseIntegrationSpec`: the
config/secret handling (`ServiceServerSpec`, `AtomicWriteSpec`, `ServiceDirsSpec`), the port
behavior (`DefaultPortIntegrationSpec`, `PortFallbackIntegrationSpec`), the private files
(`PrivateFilesIntegrationSpec`, `SecretHygieneIntegrationSpec`), the single instance
(`SingleServiceIntegrationSpec`), quit (`QuitIntegrationSpec`), clean stop
(`CleanStopIntegrationSpec`), durable state, the unreadable config, loopback only, and the tray
supervisor's 4 scenarios. The config file shape and the tray protocol are those item 66 defines
(`STT_CONFIG_DIR`, `config.json`, `secret.conf`, the CLI's launch/quit).

## Proposal

1. **Directories**: the same per-platform data/config directories as today, with the same
   environment overrides, so the app's existing installs keep working after the switch.
2. **Config**: `config.json` read at startup, `secret.conf` written mode `0600`, atomic writes,
   and unreadable config failing with the same message.
3. **Port**: bind a durable port, write it where the tray reads it, fall back to a free port
   without losing it across restarts, and refuse non-loopback hosts.
4. **Lifecycle**: one instance per user, `quit` via the route or the CLI, a clean stop on SIGTERM
   and on stdin close, and the tray supervisor's watch/restart behavior.

## Open questions

- **Protocol with the tray**: item 66's `stt-app quit`/launch protocol is the contract; confirm
  the Rust service speaks exactly it (a CLI call or an API route) and that the tray's
  supervision semantics (restart on failure, stop on quit) are the same.
- **`STT_CONFIG_DIR` in service mode**: the desktop app always sets it; the plain server does
  not. Which values the suites pin and which the packaging sets in item 108.
- **Windows specifics**: private file ACLs instead of `0600`, and the console/stdin-close
  behavior when the window closes.

## Test list

Draft. One line per suite; each scenario inside it is a TDD cycle against the annex's list.

- [ ] `StopOnStdinCloseIntegrationSpec.kt` — 2 scenarios (integration)
- [ ] `service/AtomicWriteSpec.kt` — 1 scenario
- [ ] `service/CleanStopIntegrationSpec.kt` — 1 scenario (integration)
- [ ] `service/DefaultPortIntegrationSpec.kt` — 1 scenario (integration)
- [ ] `service/DurableStateIntegrationSpec.kt` — 1 scenario (integration)
- [ ] `service/LoopbackOnlyIntegrationSpec.kt` — 1 scenario (integration)
- [ ] `service/PortFallbackIntegrationSpec.kt` — 2 scenarios (integration)
- [ ] `service/PrivateFilesIntegrationSpec.kt` — 1 scenario (integration)
- [ ] `service/QuitIntegrationSpec.kt` — 1 scenario (integration)
- [ ] `service/SecretHygieneIntegrationSpec.kt` — 1 scenario (integration)
- [ ] `service/ServiceDirsSpec.kt` — 1 scenario
- [ ] `service/ServiceServerSpec.kt` — 2 scenarios
- [ ] `service/SingleServiceIntegrationSpec.kt` — 1 scenario (integration)
- [ ] `service/TraySupervisorSpec.kt` — 4 scenarios
- [ ] `service/UnreadableConfigIntegrationSpec.kt` — 2 scenarios (integration)
- [ ] (must not break) The Kotlin suite stays green and unchanged.

## e2e stance

Exempt from ADR 0046's per-feature e2e test: no app behavior changes; the desktop journeys run
against the Rust backend in item 108.

## Verification and CI

Local verification as in every phase; the merge gate is the existing CI unchanged, with no Rust
job until item 108.

## Approval log

- 2026-11-10: filed as a draft with the rewrite's plan (item 100).
- 2026-11-10: the user approved the item and its test list; `spec-approved`.
