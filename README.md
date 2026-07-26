# api-trace (Android)

Debug-focused API interception starter modules for Kotlin/Android.

## Modules

- `api-trace-core`: facade + models
- `api-trace-debug`: OkHttp interceptor backend + debug bootstrap
- `api-trace-noop`: release-safe bootstrap with same API signature

## Install

Add the modules from this repository into your Android build. You can include them as a Git submodule or vendored source:

```kotlin
dependencies {
    implementation(project(":api-trace-core"))
    debugImplementation(project(":api-trace-debug"))
    releaseImplementation(project(":api-trace-noop"))
}
```

This keeps app code identical across build types.

## Integrate In App Startup

```kotlin
import com.apitrace.APITrace
import com.apitrace.APITraceBootstrap
import okhttp3.OkHttpClient

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        val okHttpBuilder = OkHttpClient.Builder()
        APITraceBootstrap.install(okHttpBuilder, maxRecords = 500)

        // Use this client in Retrofit/your network stack.
        val client = okHttpBuilder.build()

        APITrace.start()
    }
}
```

## Public API Surface

- `APITrace.install(backend)`
- `APITrace.start()` — enables capture; no requests are recorded before this is called.
- `APITrace.stop()` — disables capture; already-buffered records are kept, but no new requests are recorded until `start()` is called again.
- `APITrace.clear()`
- `APITrace.records()`
- `APITrace.exportJson(pretty)`
- `APITrace.exportHar(pretty)`
- `APITraceRedactor(headerRules, queryItemRules, responseHeaderRules, replacement)`
- `APITraceBootstrap.install(okHttpBuilder, maxRecords, redactor, maxBodyBytes, captureRequestBodies, captureResponseBodies, allowInNonDebuggableBuilds)` (from debug/noop module)

All public symbols are documented with KDoc in `api-trace-core` and public bootstrap classes.

## Stored Data Format

Each record is one full exchange (request + response/failure):

```json
{
  "id": "...",
  "startedAt": "2026-03-05T03:27:10.123Z",
  "durationMs": 84,
  "method": "GET",
  "url": "https://api.example.com/v1/users?page=1&token=%3Cmocked%3E",
  "endpoint": "/v1/users",
  "request": {
    "headers": {
      "Authorization": {
        "mode": "includes",
        "values": ["<mocked>"]
      },
      "X-Trace-Id": {
        "mode": "exact",
        "values": ["abc-123"]
      }
    },
    "queryItems": {
      "page": {
        "mode": "exact",
        "values": ["1"]
      },
      "token": {
        "mode": "includes",
        "values": ["<mocked>"]
      }
    },
    "bodyText": null,
    "bodyBase64": null
  },
  "response": {
    "statusCode": 200,
    "headers": {
      "Content-Type": ["application/json"]
    },
    "bodyText": "{\"ok\":true}",
    "bodyBase64": null
  },
  "errorMessage": null
}
```

The Kotlin model stores `startedAtEpochMs` (epoch milliseconds); exports serialize it as
ISO 8601 `startedAt` with millisecond precision to match the iOS SDK's wire format.

### Header Behavior

- Request headers are opt-in via `headerRules`.
- `EXACT` preserves the original value.
- `INCLUDES` stores only presence semantics using the configured replacement value.
- Response headers are captured by default, except credential-bearing and redirect headers
  (`Set-Cookie`, `Set-Cookie2`, `Authorization`, `Proxy-Authenticate`, `WWW-Authenticate`,
  `Location`, `Content-Location`, `Refresh`), which are replaced with the replacement value.
  Custom `responseHeaderRules` extend these defaults. Override a default with `EXACT`
  (`EXACT` opts a default back in; `INCLUDES` redacts additional headers).
- OkHttp's `toMultimap()` lowercases stored header names; rule matching is
  case-insensitive either way.

### Query Behavior

- Query items are opt-in via `queryItemRules`.
- Only configured query items remain in the stored `url`.
- Use `INCLUDES` for sensitive items such as `token` when the real value should not be persisted.

## Example Configuration

```kotlin
APITraceBootstrap.install(
    okHttpBuilder = okHttpBuilder,
    maxRecords = 500,
    maxBodyBytes = 64 * 1024,
    captureRequestBodies = true,
    captureResponseBodies = true,
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
```

## Notes

- Debug module installs an **application** interceptor, so captured response bodies are
  observed after OkHttp's transparent gzip decoding. Redirects are followed inside OkHttp,
  so one record covers the whole call and reflects the final response.
- Body capture is disabled by default because bodies are stored verbatim with no field-level
  redaction. Explicitly enable it only for endpoints whose payloads are safe to retain.
- Enabled response body capture uses `peekBody` with a size cap (`maxBodyBytes`, default 64 KB)
  and skips unknown-length streams, `text/event-stream`, and protocol upgrades. Request bodies
  are captured only when repeatable and no larger than the limit, avoiding unbounded buffering.
- Stored URLs always omit user credentials and fragments. Query items remain opt-in.
- `start()` refuses to enable capture when the host app is not debuggable, as defense in
  depth against the debug module accidentally shipping in a release build. Internal
  non-debuggable QA builds can opt in with `allowInNonDebuggableBuilds = true`.
- Error messages are sanitized: query strings in embedded URLs are stripped before storage.
- Records live in memory only; exports (`exportJson`/`exportHar`) still contain captured
  response data, so treat exported files as sensitive.
- Capture only happens between `APITrace.start()` and `APITrace.stop()`; the interceptor still forwards every request when capture is disabled.
