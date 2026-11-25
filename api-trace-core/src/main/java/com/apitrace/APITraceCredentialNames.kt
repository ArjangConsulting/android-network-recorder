package com.apitrace

import java.util.logging.Logger

/** App overrides for built-in redaction, using the fields of a `redact` block. */
data class APITraceRedactionConfiguration(
    val headers: List<String> = emptyList(),
    val queryParameters: List<String> = emptyList(),
    val except: Exceptions = Exceptions(),
) {
    data class Exceptions(
        val headers: List<String> = emptyList(),
        val queryParameters: List<String> = emptyList(),
    )
}

/**
 * Built-in lists and JWT pattern from moqserver's
 * `server/Sources/MoqCore/Validation/CredentialNames.swift` at `6719593`.
 */
internal object APITraceCredentialNames {
    val headers = listOf(
        "Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie",
        "X-Authorization", "X-Api-Key", "Api-Key", "X-Auth-Token",
        "X-Access-Token", "X-Refresh-Token", "X-Id-Token", "X-CSRF-Token",
        "X-XSRF-Token", "X-Amz-Security-Token", "X-Goog-Api-Key", "X-Firebase-AppCheck",
        "Ocp-Apim-Subscription-Key",
    )
    val queryParameters = listOf(
        "access_token", "refresh_token", "id_token", "id_token_hint",
        "client_secret", "client_assertion", "assertion", "code_verifier",
        "device_code", "subject_token", "actor_token", "token",
        "password", "api_key", "SAMLResponse", "X-Amz-Signature",
        "X-Amz-Security-Token", "X-Goog-Signature",
    )
    val protectedHeaders = listOf(
        "Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie",
    )

    /** HAR-only lists from `server/Sources/MoqImport/HARImporter.swift` at the same commit. */
    val importOnlyHeaders = listOf(
        "x-forwarded-for", "www-authenticate",
    )
    val importOnlyParameters = listOf(
        "code", "state", "auth", "key",
        "sig", "signature", "client_id", "code_challenge",
        "challenge", "email", "authenticity_token", "samlrequest",
    )

    val jwtPattern = Regex("""(?<![A-Za-z0-9_-])(eyJ[A-Za-z0-9_-]{4,}=*)\.(eyJ[A-Za-z0-9_-]{4,}=*)\.[A-Za-z0-9\-_.+/=]*""")
    const val MARKER = "[redacted]"

    fun normalize(name: String): String = name.lowercase().filter { it != '-' && it != '_' }
}

internal class APITraceCredentialPolicy(configuration: APITraceRedactionConfiguration) {
    private val protectedHeaders = APITraceCredentialNames.protectedHeaders.map(APITraceCredentialNames::normalize).toSet()
    private val requested = configuration.except.headers.map(APITraceCredentialNames::normalize).toSet()

    init {
        if (requested.any { it in protectedHeaders }) {
            Logger.getLogger("com.apitrace.Redaction").warning(
                "Ignoring redaction exception for a protected credential header",
            )
        }
    }

    val exceptHeaders = requested - protectedHeaders
    val exceptQueryParameters = configuration.except.queryParameters.map(APITraceCredentialNames::normalize).toSet()
    val headers = (APITraceCredentialNames.headers + APITraceCredentialNames.importOnlyHeaders + configuration.headers)
        .map(APITraceCredentialNames::normalize).toSet() - exceptHeaders
    val queryParameters =
        (APITraceCredentialNames.queryParameters + APITraceCredentialNames.importOnlyParameters + configuration.queryParameters)
            .map(APITraceCredentialNames::normalize).toSet() - exceptQueryParameters
}
