# CLAUDE.md

Start with `AGENTS.md`. It is the canonical repo guide.

The short version:

- This repo is the Android Kotlin/Gradle implementation of the APITrace network recording SDK.
- Modules: `api-trace-core`, `api-trace-debug`, `api-trace-noop`.
- Keep public capture behavior semantically aligned with the iOS counterpart.
- Both platforms export `startedAt` as ISO 8601 with millisecond precision; the Kotlin model stores epoch ms internally but the wire format is unified.
- Update `README.md` when public API, export shape, or integration steps change.
- Validate with Gradle assemble if available; report environment blockers explicitly.
