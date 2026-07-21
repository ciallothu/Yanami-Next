package com.sekusarisu.yanami.data.remote

import com.sekusarisu.yanami.domain.model.AuthType
import com.sekusarisu.yanami.domain.model.CustomHeader
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header

internal const val SESSION_COOKIE_NAME = "session_token"

private val RESERVED_CUSTOM_HEADERS =
        setOf(
                "authorization",
                "cookie",
                "host",
                "content-length",
                "connection",
                "upgrade",
                "origin"
        )

internal data class AuthHeader(val name: String, val value: String)

internal fun buildAuthHeader(sessionToken: String, authType: AuthType): AuthHeader? {
    if (sessionToken.isBlank()) return null
    return when (authType) {
        AuthType.API_KEY -> AuthHeader("Authorization", "Bearer $sessionToken")
        AuthType.PASSWORD -> AuthHeader("Cookie", buildSessionCookie(sessionToken))
        AuthType.GUEST -> null
    }
}

internal fun buildSessionCookie(sessionToken: String): String =
        "$SESSION_COOKIE_NAME=$sessionToken"

internal fun HttpRequestBuilder.applyAuth(sessionToken: String, authType: AuthType) {
    val authHeader = buildAuthHeader(sessionToken, authType) ?: return
    header(authHeader.name, authHeader.value)
}

internal fun HttpRequestBuilder.applyCustomHeaders(customHeaders: List<CustomHeader>) {
    customHeaders.forEach { customHeader ->
        val name = customHeader.name.trim()
        val value = customHeader.value.trim()
        if (isAllowedCustomHeaderName(name) && isAllowedCustomHeaderValue(value)) {
            header(name, value)
        }
    }
}

internal fun HttpRequestBuilder.applyAdminAuth(
        sessionToken: String,
        authType: AuthType,
        customHeaders: List<CustomHeader>
) {
    if (authType == AuthType.GUEST) {
        throw IllegalStateException("游客模式不支持管理操作")
    }
    applyCustomHeaders(customHeaders.toList())
    applyAuth(sessionToken, authType)
}

internal fun isAllowedCustomHeaderName(name: String): Boolean =
        name.length in 1..128 &&
                name.lowercase() !in RESERVED_CUSTOM_HEADERS &&
                name.all { char ->
                    char in 'a'..'z' ||
                            char in 'A'..'Z' ||
                            char in '0'..'9' ||
                            char in "!#\$%&'*+-.^_`|~"
                }

internal fun isAllowedCustomHeaderValue(value: String): Boolean =
        value.isNotEmpty() &&
                value.length <= 8_192 &&
                value.none { it == '\r' || it == '\n' || it == '\u0000' }
