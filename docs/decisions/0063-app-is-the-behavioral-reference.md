# 0063 — The app is the behavioral reference; the Python oracle is retired

Status: accepted, supersedes [0002](0002-no-python-oracle-only.md) · Date: 2026-10-29

## Context

The app was ported from a Python CLI, `transcribe.py`, whose behavior was the parity target ([0002](0002-no-python-oracle-only.md)). The port is complete, and the app has since deliberately diverged where the oracle was wrong or its output was unwanted: txt prints the stored speaker names ([0034](0034-speaker-labels-are-stored-names.md)), segment text is trimmed ([0030](0030-trimmed-segment-text-in-all-outputs.md)), and the one-speaker rule replaces the oracle's count ([0027](0027-auto-speaker-count-allows-one-speaker.md)). Parity has therefore been partial for a long time, and the oracle is no longer maintained. A behavior that lives only in a frozen Python file that the build never runs cannot be verified, and a live document that names it as the reference keeps sending readers to it.

## Decision

- `docs/oracle/transcribe.py` is retired and removed; the repository holds no Python file.
- The app's code, its tests, its specs and the `docs/api` examples are the behavioral authority. What the app does is the behavior — for example the stored speaker names in txt (`[Speaker 1]`, not the oracle's `[speaker 1]`) — and no further parity work is done.
- The no-Python rule is unchanged: the app, its build and its tests contain no Python. There is no exception any more, because there is no oracle.
- Historical records keep their text: ADRs and specs that describe the oracle as the reference it then was stay as written.

## Consequences

- New behavior is specified in a roadmap item or spec and pinned by tests whose expected values come from hand calculation, the spec, or the app's current behavior.
- Where the app and the old oracle differ, the app's behavior stands ([0034](0034-speaker-labels-are-stored-names.md), [0030](0030-trimmed-segment-text-in-all-outputs.md), [0027](0027-auto-speaker-count-allows-one-speaker.md)).
- `docs/check-oracle-retired.ts` keeps the retirement true: no Python file, no `docs/oracle/`, and no live document naming the oracle as the reference.
