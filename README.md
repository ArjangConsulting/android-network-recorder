# api-trace (Android)

Debug-focused API interception starter modules for Kotlin/Android.

## Modules

- `api-trace-core`: facade + models
- `api-trace-debug`: OkHttp interceptor backend + debug bootstrap
- `api-trace-noop`: release-safe bootstrap with same API signature

## Install

Release artifacts are published to Maven Central under `io.github.arjangconsulting`:

```kotlin
dependencies {
    implementation("io.github.arjangconsulting:api-trace-core:<version>")
    debugImplementation("io.github.arjangconsulting:api-trace-debug:<version>")
    releaseImplementation("io.github.arjangconsulting:api-trace-noop:<version>")
}
```

This keeps app code identical across build types.

Publishing is triggered by pushing a bare SemVer tag such as `2.0.0`. The repository must
define `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `MAVEN_SIGNING_KEY`, and
`MAVEN_SIGNING_PASSWORD` Actions secrets before the first release.

Release Please uses the built-in `GITHUB_TOKEN`. The organization allows Actions to create
pull requests, while the workflow grants only the write permissions needed for releases.
Automatic releases stay within the current major version and increment the minor version.
An intentional major release requires an explicit `Release-As: X.0.0` commit footer.

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

## Write a HAR File For Test Harnesses

UI test harnesses can collect a run's traffic without app-specific export code. Call
`APITraceHarFile.enableFromLaunchIntent(application)` in `Application.onCreate` next to
`APITraceBootstrap.install`. When the first activity is launched with the `APITRACE_HAR_FILE`
extra set to `1` (for example `--es APITRACE_HAR_FILE 1`), the captured history is written to
`APITraceHarFile.defaultFile(context)` (`apitrace.har` in the app's external cache directory)
after every recorded exchange. `APITrace.clear()` rewrites it empty.

- Copy it with `adb pull /sdcard/Android/data/<package>/cache/apitrace.har`; no root or
  debuggable flag is needed. Other apps can't read it and backups skip it.
- `APITraceHarFile.enable(file)` writes to any file directly; `enable(null)` stops.
- Writes are coalesced and best effort; a failed write is logged (tag `APITrace`) and never affects
  the app's own requests.
- `api-trace-noop` has the same `APITraceHarFile` API and never writes a file.

## Public API Surface

- `APITrace.install(backend)`
- `APITrace.start()` — enables capture; no requests are recorded before this is called.
- `APITrace.stop()` — disables capture; already-buffered records are kept, but no new requests are recorded until `start()` is called again.
- `APITrace.clear()`
- `APITrace.records()`
- `APITrace.exportJson(pretty)`
- `APITrace.exportHar(pretty)`
- `APITrace.exportHar(records, pretty)` (export a given list of records)
- `APITraceHarFile.enable(file)`, `APITraceHarFile.enableFromLaunchIntent(application)`, `APITraceHarFile.defaultFile(context)`
- `APITraceConsoleFormatter(maxBodyCharacters).format(record)`
- `APITraceRedactor(headerRules, queryItemRules, responseHeaderRules, replacement, redact)`
- `APITraceBootstrap.install(okHttpBuilder, maxRecords, redactor, maxBodyBytes, captureRequestBodies, captureResponseBodies, allowInNonDebuggableBuilds)` (from debug/noop module)

All public symbols are documented with KDoc in `api-trace-core` and public bootstrap classes.

## Readable Console Output

`APITraceConsoleFormatter` turns an already-sanitized record into an indented request and
response/failure block. It returns a string so the host app can use its preferred logger:

```kotlin
val formatter = APITraceConsoleFormatter(maxBodyCharacters = 10_000)

APITrace.records().forEach { record ->
    Timber.tag("API").d(formatter.format(record))
}
```

The formatter never accepts raw OkHttp requests or responses. It formats the stored
`APITraceRecord`, so URLs and headers have already passed through the configured redaction policy.
Text bodies containing a JSON object or array are pretty-printed, then truncated to
`maxBodyCharacters`. Empty and whitespace-only bodies are omitted. Binary bodies are described
without printing their base64 data.

## Stored Data Format

Each record is one full exchange (request + response/failure):

```json
{
  "id": "...",
  "startedAt": "2026-03-05T03:27:10.123Z",
  "durationMs": 84,
  "method": "GET",
  "url": "https://api.example.test/v1/users?page=1&token=%5Bredacted%5D",
  "endpoint": "/v1/users",
  "request": {
    "headers": {
      "Authorization": {
        "mode": "includes",
        "values": ["[redacted]"]
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
        "values": ["[redacted]"]
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

### Credential Redaction

Both request and response headers and all URL query parameters are captured. Built-in
credential names and HAR-import extras keep only presence: `[redacted]` in values and
`%5Bredacted%5D` in URLs and form data. Names match exactly after lowercasing and removing
`-` and `_`; for example, `api_key`, `apiKey` and `API-KEY` match, while `sort_key` does
not. A JWT under an unlisted name keeps its header and payload but loses its signature.
Cookie headers are redacted; HAR cookie entries retain the name and a placeholder value.

Pass extra names and exceptions to `APITraceRedactor(redact = ...)`. When loading a shared
`redact` block, map `headers`, `query_parameters`, and `except` to `headers`,
`queryParameters`, and `except`; the nested object has the same two name lists. Query
names also apply to form fields and nested JSON keys. Exceptions keep the original value,
including a JWT. Attempts to except `Authorization`, `Proxy-Authorization`, `Cookie`, or
`Set-Cookie` are ignored and log a warning. Use exceptions only for values safe to share.

```kotlin
APITraceBootstrap.install(
    okHttpBuilder = okHttpBuilder,
    captureRequestBodies = true,
    captureResponseBodies = true,
    redactor = APITraceRedactor(
        redact = APITraceRedactionConfiguration(
            headers = listOf("X-App-Device"),
            queryParameters = listOf("device_ref"),
            except = APITraceRedactionConfiguration.Exceptions(
                headers = listOf("X-CSRF-Token"),
                queryParameters = listOf("token"),
            ),
        ),
    ),
)
```

Existing `headerRules`, `queryItemRules`, and `responseHeaderRules` can still add
redactions with `INCLUDES`. An `EXACT` rule no longer overrides the credential policy;
use `except` for an explicit override. The legacy `replacement` argument is accepted for
source compatibility; capture always writes the HAR-compatible marker. OkHttp lowercases
the stored header names.

## Notes

- Debug module installs an **application** interceptor, so captured response bodies are
  observed after OkHttp's transparent gzip decoding. Redirects are followed inside OkHttp,
  so one record covers the whole call and reflects the final response.
- Body capture is off by default. When enabled, only complete UTF-8 JSON and form-encoded
  payloads within `maxBodyBytes` (default 64 KB) are kept, after redaction. Unsupported,
  malformed, and partial bodies are omitted. Request bodies must be repeatable; response
  capture skips unknown-length streams and protocol upgrades.
- Stored URLs omit user information and fragments. Query fields are sanitized in both
  the request URL and `queryString`, including in captured redirect URL headers.
- `start()` refuses to enable capture when the host app is not debuggable, as defense in
  depth against the debug module accidentally shipping in a release build. Internal
  non-debuggable QA builds can opt in with `allowInNonDebuggableBuilds = true`.
- Transport failures use a fixed message rather than storing free-form network errors.
- Treat exports as sensitive: unlisted secrets, excepted values, and JWT payload claims can remain.
- Capture only happens between `APITrace.start()` and `APITrace.stop()`; the interceptor still forwards every request when capture is disabled.
