# Copilot Instructions

Use `AGENTS.md` as the full repository guide. The essentials are:

- This repo is an Android Kotlin/Gradle SDK for API trace capture.
- Modules: `api-trace-core` (facade + models), `api-trace-debug` (OkHttp interceptor backend), `api-trace-noop` (release-safe no-op).
- Request header and query capture is opt-in via `APITraceRedactor`.
- Validate with `gradle :api-trace-core:assemble :api-trace-debug:assemble :api-trace-noop:assemble` if Gradle is available.
- Update `README.md` when public behavior or integration steps change.
- Keep the iOS counterpart (`ios-network-recorder`) semantically aligned for shared behavior changes.
