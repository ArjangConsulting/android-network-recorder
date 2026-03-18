# GEMINI.md

Use `AGENTS.md` as the source of truth for this repository.

Key repo constraints:

- This is the Android Kotlin/Gradle SDK for API trace capture, not an app.
- Modules: `api-trace-core`, `api-trace-debug`, `api-trace-noop`.
- Shared behavior changes should be reviewed alongside the iOS counterpart.
- Keep public docs in sync with code changes.
- Validate with Gradle assemble if available.
