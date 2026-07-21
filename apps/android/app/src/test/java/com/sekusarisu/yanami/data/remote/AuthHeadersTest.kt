package com.sekusarisu.yanami.data.remote

import com.sekusarisu.yanami.domain.model.AuthType
import com.sekusarisu.yanami.domain.model.CustomHeader
import io.ktor.client.request.HttpRequestBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthHeadersTest {
    @Test fun buildsExactlyOneHeaderForEachAuthenticationMode() {
        assertEquals("session_token=secret", buildAuthHeader("secret", AuthType.PASSWORD)?.value)
        assertEquals("Bearer secret", buildAuthHeader("secret", AuthType.API_KEY)?.value)
        assertNull(buildAuthHeader("secret", AuthType.GUEST))
    }

    @Test fun customHeadersCannotOverrideTransportOrAuthenticationBoundaries() {
        listOf(
                        "Authorization",
                        "Cookie",
                        "Host",
                        "Origin",
                        "Connection",
                        "Content-Length",
                        "X-2FA-Code",
                        "X-Two-Factor-Code"
                )
                .forEach { assertFalse(it, isAllowedCustomHeaderName(it)) }
        assertTrue(isAllowedCustomHeaderName("CF-Access-Client-Id"))
        listOf("X Bad", "X:\u0000Bad", "X\r\nInjected", "X-测试")
                .forEach { assertFalse(it, isAllowedCustomHeaderName(it)) }
        assertFalse(isAllowedCustomHeaderName("X".repeat(129)))
        assertTrue(isAllowedCustomHeaderName("X-Custom_1"))
        assertFalse(isAllowedCustomHeaderValue("line1\r\nInjected: value"))
        assertFalse(isAllowedCustomHeaderValue("x".repeat(8_193)))
        assertTrue(isAllowedCustomHeaderValue("client-value"))
    }

    @Test fun adminAuthIncludesCustomHeadersAndExactlyOneExplicitCredential() {
        val request = HttpRequestBuilder()

        request.applyAdminAuth(
                sessionToken = "server-a-token",
                authType = AuthType.PASSWORD,
                customHeaders =
                        listOf(
                                CustomHeader("CF-Access-Client-Id", "client-a"),
                                CustomHeader("Cookie", "attacker-cookie")
                        )
        )

        assertEquals("client-a", request.headers["CF-Access-Client-Id"])
        assertEquals("session_token=server-a-token", request.headers["Cookie"])
    }

    @Test fun sensitiveTerminalHeaderIsExactAndLimitedToPasswordProfilesThatRequireIt() {
        val header =
                buildSensitiveTwoFactorHeader(
                        authType = AuthType.PASSWORD,
                        requiresTwoFactor = true,
                        oneTimeCode = " 123456 "
                )

        assertEquals("X-2FA-Code", header?.name)
        assertEquals("123456", header?.value)
        assertNull(
                buildSensitiveTwoFactorHeader(
                        authType = AuthType.PASSWORD,
                        requiresTwoFactor = false,
                        oneTimeCode = "123456"
                )
        )
        assertNull(
                buildSensitiveTwoFactorHeader(
                        authType = AuthType.API_KEY,
                        requiresTwoFactor = true,
                        oneTimeCode = null
                )
        )
        assertNull(
                buildSensitiveTwoFactorHeader(
                        authType = AuthType.GUEST,
                        requiresTwoFactor = true,
                        oneTimeCode = "123456"
                )
        )
    }

    @Test fun sensitivePasswordHandshakeFailsClosedWithoutFreshAsciiTotp() {
        listOf<String?>(null, "", "12345", "1234567", "１２３４５６", "12345\n")
                .forEach { code ->
                    var rejected = false
                    try {
                        buildSensitiveTwoFactorHeader(
                                authType = AuthType.PASSWORD,
                                requiresTwoFactor = true,
                                oneTimeCode = code
                        )
                    } catch (_: IllegalArgumentException) {
                        rejected = true
                    }
                    assertTrue("Expected sensitive code to be rejected: $code", rejected)
                }
    }
}
