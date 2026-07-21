package com.sekusarisu.yanami.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestGenerationTest {
    @Test fun onlyTheActiveGenerationMayApplyACompletion() {
        assertFalse(isCurrentRequestGeneration(completed = 41, active = 42))
        assertTrue(isCurrentRequestGeneration(completed = 42, active = 42))
        assertFalse(isCurrentRequestGeneration(completed = 43, active = 42))
    }
}
