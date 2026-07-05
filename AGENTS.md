# AGENTS.md

This repository contains the Android Kotlin/Gradle implementation of the APITrace network recording SDK.

This file is the canonical agent guide for the repo.

## Scope

- Gradle module structure, Kotlin implementation, and doc updates for the Android APITrace SDK.
- For the iOS counterpart, see `github.com/ArjangConsulting/ios-network-recorder`.
- For cross-platform orchestration, see `github.com/ArjangConsulting/mobile-network-recorder`.

## Repo Map

- `settings.gradle.kts`: included modules
- `build.gradle.kts`: top-level plugin versions
- `api-trace-core`: public facade, models, and redaction
- `api-trace-debug`: OkHttp backend and debug bootstrap
- `api-trace-noop`: release-safe no-op bootstrap
- `README.md`: Android integration guide

## Working Rules

- Treat `api-trace-core` as the stable Kotlin-facing contract.
- Prefer additive public API changes. Do not rename public symbols or JSON keys casually.
- `api-trace-debug` only captures traffic that uses the patched `OkHttpClient.Builder`.
- `api-trace-noop` must preserve the same bootstrap shape without recording traffic.
- Keep request metadata capture opt-in through `APITraceRedactor`.
- Preserve existing interceptor behavior and ordering unless there is a clear requirement to change it.
- If you change exported JSON behavior, consider the iOS counterpart before finalizing. Both platforms export `startedAt` as ISO 8601 with millisecond precision — keep the wire format identical.
- When public behavior or integration changes, update `README.md`.
- This repo ships an SDK, not a sample app. Avoid app-specific assumptions in code or docs.

## Validation

- Preferred command: `gradle :api-trace-core:assemble :api-trace-debug:assemble :api-trace-noop:assemble`
- This repo does not commit a Gradle wrapper.
- If Gradle is not available or fails to load, report the blocker explicitly instead of pretending the check passed.
- Android currently has no committed tests. If you add non-trivial Kotlin logic, prefer adding tests.

## Skills

- Use `.codex/skills/apitrace-integration/SKILL.md` for host-app integration guidance.
