package com.sekusarisu.yanami.ui.screen.terminal

import com.sekusarisu.yanami.domain.model.AuthType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSensitiveAuthPolicyTest {
    @Test fun knownPasswordTwoFactorProfilePromptsBeforeHandshake() {
        assertTrue(
                requiresTerminalSensitiveTwoFactor(
                        authType = AuthType.PASSWORD,
                        profileRequiresTwoFactor = true,
                        passwordAuthenticationWasRejected = false
                )
        )
    }

    @Test fun rejectedLegacyPasswordProfilePromptsOnRetry() {
        assertFalse(
                requiresTerminalSensitiveTwoFactor(
                        authType = AuthType.PASSWORD,
                        profileRequiresTwoFactor = false,
                        passwordAuthenticationWasRejected = false
                )
        )
        assertTrue(
                shouldRememberTerminalTwoFactorHint(
                        authType = AuthType.PASSWORD,
                        isAuthenticationFailure = true
                )
        )
        assertTrue(
                requiresTerminalSensitiveTwoFactor(
                        authType = AuthType.PASSWORD,
                        profileRequiresTwoFactor = false,
                        passwordAuthenticationWasRejected = true
                )
        )
    }

    @Test fun apiKeyAndGuestNeverPromptForSensitiveTwoFactor() {
        listOf(AuthType.API_KEY, AuthType.GUEST).forEach { authType ->
            assertFalse(
                    requiresTerminalSensitiveTwoFactor(
                            authType = authType,
                            profileRequiresTwoFactor = true,
                            passwordAuthenticationWasRejected = true
                    )
            )
            assertFalse(
                    shouldRememberTerminalTwoFactorHint(
                            authType = authType,
                            isAuthenticationFailure = true
                    )
            )
        }
    }
}
