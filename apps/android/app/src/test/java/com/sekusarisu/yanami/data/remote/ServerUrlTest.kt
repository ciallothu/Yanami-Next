package com.sekusarisu.yanami.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerUrlTest {
    @Test fun normalizesAValidKomariBaseUrl() {
        assertEquals(
                "https://example.com/komari",
                normalizeServerBaseUrl(" https://example.com/komari/ ")
        )
    }

    @Test fun rejectsCredentialAndRedirectConfusionComponents() {
        listOf(
                        "https://user:pass@example.com",
                        "https://example.com?next=https://evil.test",
                        "https://example.com/#fragment",
                        "http://example.com",
                        "file:///tmp/socket",
                        "https://"
                )
                .forEach { value ->
                    assertThrows(IllegalArgumentException::class.java) {
                        normalizeServerBaseUrl(value)
                    }
                }
    }
}
