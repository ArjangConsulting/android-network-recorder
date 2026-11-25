package com.apitrace

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class APITraceCredentialRedactionTest {
    private val headers = listOf(
        "Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie",
        "X-Authorization", "X-Api-Key", "Api-Key", "X-Auth-Token",
        "X-Access-Token", "X-Refresh-Token", "X-Id-Token", "X-CSRF-Token",
        "X-XSRF-Token", "X-Amz-Security-Token", "X-Goog-Api-Key", "X-Firebase-AppCheck",
        "Ocp-Apim-Subscription-Key", "x-forwarded-for", "www-authenticate",
    )
    private val parameters = listOf(
        "access_token", "refresh_token", "id_token", "id_token_hint",
        "client_secret", "client_assertion", "assertion", "code_verifier",
        "device_code", "subject_token", "actor_token", "token",
        "password", "api_key", "SAMLResponse", "X-Amz-Signature",
        "X-Amz-Security-Token", "X-Goog-Signature", "code", "state",
        "auth", "key", "sig", "signature",
        "client_id", "code_challenge", "challenge", "email",
        "authenticity_token", "samlrequest",
    )

    @Test
    fun everyCredentialHeaderIsRedactedInBothDirections() {
        val redactor = APITraceRedactor.DEFAULT
        headers.forEach { name ->
            val original = mapOf(name to listOf("example-secret", "another-example-secret"))
            val expected = listOf("[redacted]", "[redacted]")
            assertEquals(name, APITraceCapturedField(APITraceCaptureMode.INCLUDES, expected), redactor.redact(original)[name])
            assertEquals(name, expected, redactor.redactResponseHeaders(original)[name])
        }
    }

    @Test
    fun everyCredentialParameterIsRedactedInUrlHarFormAndNestedJson() {
        val redactor = APITraceRedactor.DEFAULT
        parameters.forEach { name ->
            val captured = redactor.redact("https://api.example.test/?$name=example-secret".toHttpUrl())
            assertEquals("https://api.example.test/?$name=%5Bredacted%5D", captured.url)
            val record = APITraceRecord(
                durationMs = 0, method = "GET", url = captured.url, endpoint = "/",
                request = APITraceRecord.RequestData(queryItems = captured.queryItems),
            )
            val request = JSONObject(APITrace.harString(listOf(record))).getJSONObject("log")
                .getJSONArray("entries").getJSONObject(0).getJSONObject("request")
            val query = request.getJSONArray("queryString").getJSONObject(0)
            assertEquals(name, query.getString("name"))
            assertEquals("[redacted]", query.getString("value"))

            val form = redactor.redactBody(
                "$name=example-secret&author=pilot".toByteArray(), "application/x-www-form-urlencoded",
            )
            assertEquals("$name=%5Bredacted%5D&author=pilot", form)

            val body = """{"items":[{"$name":{"nested":"example-secret"},"author":"pilot"}]}"""
            val json = redactor.redactBody(body.toByteArray(), "application/problem+json; charset=utf-8")
            val item = JSONObject(requireNotNull(json)).getJSONArray("items").getJSONObject(0)
            assertEquals("[redacted]", item.getString(name))
            assertEquals("pilot", item.getString("author"))
        }
    }

    @Test
    fun exactNormalizedNamesConfigurationAndJwtRules() {
        val redactor = APITraceRedactor(
            redact = APITraceRedactionConfiguration(
                headers = listOf("X-App-Device"),
                queryParameters = listOf("device_ref"),
                except = APITraceRedactionConfiguration.Exceptions(
                    headers = listOf("X-Csrf-Token", "AUTHORIZATION", "proxy_authorization", "cookie", "set_cookie"),
                    queryParameters = listOf("token", "email"),
                ),
            ),
        )
        val jwt = "eyJhbGci.eyJzdWIi.example-signature"
        val trimmed = "eyJhbGci.eyJzdWIi.redacted"
        val ordinary = "https://monkeys.eyewear.example.test/"
        val values = mapOf(
            "Authorization" to jwt, "Proxy-Authorization" to jwt, "Cookie" to jwt, "Set-Cookie" to jwt,
            "X_CSRF_token" to jwt, "X-APP_DEVICE" to "example-secret", "X_API_key" to "example-secret",
            "X-Session-Ref" to ordinary, "X-JWT" to "Bearer $jwt",
        )
        val headers = redactor.redact(values.mapValues { listOf(it.value) })
        listOf("Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie", "X-APP_DEVICE", "X_API_key").forEach {
            assertEquals(listOf("[redacted]"), headers[it]?.values)
        }
        assertEquals(listOf(jwt), headers["X_CSRF_token"]?.values)
        assertEquals(listOf(ordinary), headers["X-Session-Ref"]?.values)
        assertEquals(listOf("Bearer $trimmed"), headers["X-JWT"]?.values)

        val url = ("https://api.example.test/?api%5Fkey=secret&apiKey=secret&API-KEY=secret&deviceRef=secret" +
            "&token=$jwt&ref=$jwt&keyword=term&sort_key=date&author=pilot").toHttpUrl()
        val query = redactor.redact(url).queryItems
        listOf("api_key", "apiKey", "API-KEY", "deviceRef").forEach {
            assertEquals(listOf("[redacted]"), query[it]?.values)
        }
        assertEquals(listOf(jwt), query["token"]?.values)
        assertEquals(listOf(trimmed), query["ref"]?.values)
        assertEquals(listOf("term"), query["keyword"]?.values)
        assertEquals(listOf("date"), query["sort_key"]?.values)
        assertEquals(listOf("pilot"), query["author"]?.values)
        assertEquals(
            listOf("/next?state=%5Bredacted%5D&token=$jwt&ref=$trimmed"),
            redactor.redactResponseHeaders(mapOf("Location" to listOf("/next?state=secret&token=$jwt&ref=$jwt#secret")))["Location"],
        )

        val form = "ref=$jwt&token=$jwt&device-ref=secret&email=user%40example.com"
        assertEquals(
            "ref=$trimmed&token=$jwt&device-ref=%5Bredacted%5D&email=user%40example.com",
            redactor.redactBody(form.toByteArray(), "application/x-www-form-urlencoded"),
        )
        val body = """{"items":[{"token":"$jwt","ref":"$jwt","device_ref":42},"$jwt"]}"""
        val json = redactor.redactBody(body.toByteArray(), "application/json")
        val items = JSONObject(requireNotNull(json)).getJSONArray("items")
        assertEquals(jwt, items.getJSONObject(0).getString("token"))
        assertEquals(trimmed, items.getJSONObject(0).getString("ref"))
        assertEquals("[redacted]", items.getJSONObject(0).getString("device_ref"))
        assertEquals(trimmed, items.getString(1))
    }

    @Test
    fun uninspectableBodyDataNeverFallsBackToRawTextOrBase64() {
        val redactor = APITraceRedactor.DEFAULT
        assertNull(redactor.redactBody("""{"token":"example-secret"""".toByteArray(), "application/json"))
        assertNull(redactor.redactBody("token=example-secret".toByteArray(), "application/octet-stream"))
        assertNull(redactor.redactBody("bad%zz=example-secret".toByteArray(), "application/x-www-form-urlencoded"))
    }
}
