package com.sekusarisu.yanami.data.local.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserPreferencesLockStateTest {

    @Test
    fun `lock is disabled only when both persisted components are absent`() {
        assertFalse(UserPreferences().biometricLockRequired)
        assertTrue(UserPreferences(biometricEnabled = true).biometricLockRequired)
        assertTrue(UserPreferences(biometricEnvelope = "v1.envelope").biometricLockRequired)
    }
}
