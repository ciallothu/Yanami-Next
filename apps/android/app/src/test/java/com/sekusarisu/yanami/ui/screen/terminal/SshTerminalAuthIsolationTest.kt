package com.sekusarisu.yanami.ui.screen.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SshTerminalAuthIsolationTest {

    @Test
    fun terminalHasNoGlobalSessionConstructorDependency() {
        val constructor = SshTerminalViewModel::class.java.declaredConstructors.single()

        assertEquals(4, constructor.parameterTypes.size)
        assertFalse(constructor.parameterTypes.any { it.simpleName.contains("Session") })
    }
}
