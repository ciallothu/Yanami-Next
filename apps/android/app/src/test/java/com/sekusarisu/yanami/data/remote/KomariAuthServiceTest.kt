package com.sekusarisu.yanami.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KomariAuthServiceTest {

    @Test
    fun loginFailureNeverReflectsUntrustedResponseBody() {
        val secretBody = "invalid password: super-secret-value; internal stack trace"

        val result = loginFailureResult(secretBody)

        assertTrue(result is LoginResult.Error)
        val message = (result as LoginResult.Error).message
        assertEquals(LOGIN_FAILED_MESSAGE, message)
        assertFalse(message.contains("super-secret-value"))
        assertTrue(message.length < 80)
    }

    @Test
    fun twoFactorHintUsesFixedMessageFromBoundedPrefix() {
        val result = loginFailureResult("TOTP code required; private-data=do-not-reflect")

        assertTrue(result is LoginResult.Requires2FA)
        assertEquals(TWO_FACTOR_REQUIRED_MESSAGE, (result as LoginResult.Requires2FA).message)

        val hintOutsideInspectionLimit =
                loginFailureResult("x".repeat(MAX_AUTH_RESPONSE_INSPECTION_CHARS) + " TOTP")
        assertTrue(hintOutsideInspectionLimit is LoginResult.Error)
    }
}
