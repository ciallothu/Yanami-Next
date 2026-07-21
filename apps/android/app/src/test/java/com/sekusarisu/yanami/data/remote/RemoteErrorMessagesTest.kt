package com.sekusarisu.yanami.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteErrorMessagesTest {

    @Test
    fun structuredMessagesAreControlFreeAndBounded() {
        val message = "bad\u0000\u001b[31m\nrequest " + "x".repeat(500)

        val sanitized = safeRemoteErrorMessage(message, 400)

        assertFalse(sanitized.any(Char::isISOControl))
        assertTrue(sanitized.length <= MAX_REMOTE_ERROR_MESSAGE_CHARS)
    }

    @Test
    fun blankAndInvalidResponsesUseFixedHttpStatusMessages() {
        assertEquals("HTTP 403", safeRemoteErrorMessage("\u0000\n", 403))
        val invalid = invalidRemoteResponseMessage(502)
        assertTrue(invalid.startsWith("HTTP 502:"))
        assertFalse(invalid.contains("<html>"))
    }

    @Test
    fun adminExceptionEnforcesSanitizationAtTheBoundary() {
        val exception = AdminApiException(500, "failure\r\n" + "z".repeat(300))

        val message = requireNotNull(exception.message)
        assertFalse(message.any(Char::isISOControl))
        assertTrue(message.length <= MAX_REMOTE_ERROR_MESSAGE_CHARS)
    }
}
