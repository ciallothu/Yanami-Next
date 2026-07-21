package com.sekusarisu.yanami.ui.screen.server

import com.sekusarisu.yanami.domain.model.AuthType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerTwoFactorPolicyTest {
    @Test
    fun explicitSuccessfulPasswordTotpPersistsTwoFactorCapability() {
        assertTrue(
                requiresTwoFactorAfterSuccessfulAuthentication(
                        authType = AuthType.PASSWORD,
                        submittedCode = " 123456 ",
                        previouslyRequired = false
                )
        )
    }

    @Test
    fun capabilityIsNeitherClearedForKnownPasswordProfileNorAppliedToApiKey() {
        assertTrue(
                requiresTwoFactorAfterSuccessfulAuthentication(
                        authType = AuthType.PASSWORD,
                        submittedCode = null,
                        previouslyRequired = true
                )
        )
        assertFalse(
                requiresTwoFactorAfterSuccessfulAuthentication(
                        authType = AuthType.API_KEY,
                        submittedCode = "123456",
                        previouslyRequired = true
                )
        )
    }
}
