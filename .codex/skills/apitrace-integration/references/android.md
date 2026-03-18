# Android Integration

## What the SDK Does

- `api-trace-core` provides the facade and exported record model.
- `api-trace-debug` adds an OkHttp network interceptor backend.
- `api-trace-noop` keeps the same bootstrap API but installs a no-op backend.

The Android debug backend only captures traffic that uses the `OkHttpClient.Builder` patched by `APITraceBootstrap.install(...)`.

## Integration Steps

1. Add the three modules from `git@github.com:ArjangConsulting/android-network-recorder.git` as submodule, vendored source, or published artifacts.
2. Find the real `OkHttpClient.Builder` used by the app's main network stack.
3. Install the bootstrap before that builder is finalized.
4. Build the client from the patched builder.
5. Call `APITrace.start()`.
6. Add a developer-only export action.

## Minimal Bootstrap

```kotlin
import com.apitrace.APITrace
import com.apitrace.APITraceBootstrap
import com.apitrace.APITraceCaptureMode
import com.apitrace.APITraceRedactor
import okhttp3.OkHttpClient

fun buildInstrumentedClient(): OkHttpClient {
    val builder = OkHttpClient.Builder()

    APITraceBootstrap.install(
        okHttpBuilder = builder,
        maxRecords = 500,
        redactor = APITraceRedactor(
            headerRules = mapOf(
                "Authorization" to APITraceCaptureMode.INCLUDES,
                "X-Trace-Id" to APITraceCaptureMode.EXACT,
            ),
            queryItemRules = mapOf(
                "page" to APITraceCaptureMode.EXACT,
                "token" to APITraceCaptureMode.INCLUDES,
            ),
        ),
    )

    APITrace.start()
    return builder.build()
}
```

## Dependency Shape

Typical host app dependency wiring:

```kotlin
dependencies {
    implementation(project(":api-trace-core"))
    debugImplementation(project(":api-trace-debug"))
    releaseImplementation(project(":api-trace-noop"))
}
```

If the SDK is published instead of vendored, swap `project(...)` entries for coordinates.

## Export Hook

Use:

```kotlin
val json = APITrace.exportJson(pretty = true)
```

Typical next step:

- write JSON to a temporary file
- share the file from a debug screen
- send it to an existing internal upload endpoint

## Patch Carefully

- Instrument the client builder that Retrofit or the app's HTTP layer actually uses.
- Do not add a second debug-only `OkHttpClient` unless the app will route traffic through it.
- Preserve existing interceptors and ordering unless there is a clear reason to change it.
- Keep release builds on `api-trace-noop` unless the user explicitly wants a non-debug rollout.
