---
name: apitrace-integration
description: Integrate the APITrace Android network recorder into an Android app. Use when asked to add sanitized request/response capture, debug-only tracing, or trace export/share flows in a host project.
---

# APITrace Integration Skill (Android)

Use this skill when an app team wants to add the Android network recording feature into their project.

This skill is for host app integration. It is not the SDK maintenance guide.

## Use when

- adding API trace capture to an Android app
- wiring a debug-only export/share flow for captured traffic
- integrating with moqserver ingestion or any other tool that consumes exported traces

## Resolve Up Front

- dependency strategy: submodule source, vendored source, or published Maven artifact
- real network stack in the app: OkHttp, Retrofit, or wrappers around them
- developer surface for export: debug screen, share sheet, hidden action, file export, or upload flow

## Workflow

1. Inspect where the host app creates its main `OkHttpClient.Builder`.
2. Choose how the SDK will be added. Read `references/dependency-strategies.md` if needed.
3. Add `api-trace-core` plus `api-trace-debug`/`api-trace-noop` so release behavior stays safe.
4. Install the bootstrap before the builder is finalized.
5. Build the client from the patched builder.
6. Call `APITrace.start()`.
7. Configure an explicit redaction allowlist. Request capture is opt-in by design.
8. Add a developer-only export surface using `APITrace.exportJson(pretty)`.
9. Run one real request and inspect the exported payload for redaction correctness.
10. Update the host project's docs or debug menu labeling if the feature is discoverable by developers.

## Platform References

- Android integration: `references/android.md`
- Rollout checklist: `references/host-checklist.md`
- Dependency choices: `references/dependency-strategies.md`

## Guardrails

- Keep release builds on the no-op path unless the user explicitly wants production capture.
- Do not capture all request headers or query items by default.
- Instrument the app's real network path, not a new unused client.
- Preserve existing auth, retries, caching, logging, and certificate pinning behavior.
- If the app already has a debug menu or diagnostics screen, extend it instead of adding a second developer surface.
- Do not invent a backend upload protocol. Export JSON and connect it to the app's existing share or upload flow.

## Completion Output

When finishing the task in a host repo, report:

- where bootstrap/install happens
- which client/session path is actually instrumented
- which headers and query items are allowlisted
- how a developer exports traces
- what was validated and what is still manual
