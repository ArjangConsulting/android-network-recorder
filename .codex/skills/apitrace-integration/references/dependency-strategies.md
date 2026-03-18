# Dependency Strategies

Use the smallest viable dependency strategy for the host project.

## Android

Preferred order:

1. Published Maven artifact if available
2. Git submodule from `git@github.com:ArjangConsulting/android-network-recorder.git`
3. Vendored source as included Gradle modules
4. Direct file copy only if the host repo cannot use submodules or another shared-source approach

When integrating source directly, bring in these modules:

- `api-trace-core`
- `api-trace-debug`
- `api-trace-noop`

The host app's Gradle setup must already provide Android and Kotlin plugin management. This SDK repo does not include a reusable published plugin.

## Choose Based On Context

- If the host repo is a monorepo or adjacent local repo setup, prefer submodule wiring.
- If multiple apps need the feature, prefer a published Maven artifact.
- Do not fabricate package coordinates that do not exist. Use submodule or vendored integration.
