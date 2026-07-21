package com.sekusarisu.yanami.di

import io.ktor.client.plugins.logging.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkLoggingPolicyTest {
    @Test
    fun distributableClientLoggingIsDisabledForArbitraryCredentialHeaders() {
        assertEquals(LogLevel.NONE, DISTRIBUTABLE_HTTP_LOG_LEVEL)
    }
}
