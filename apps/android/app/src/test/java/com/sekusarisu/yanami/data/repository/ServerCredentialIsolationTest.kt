package com.sekusarisu.yanami.data.repository

import com.sekusarisu.yanami.domain.model.AuthType
import com.sekusarisu.yanami.domain.model.CustomHeader
import com.sekusarisu.yanami.domain.model.ServerInstance
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCredentialIsolationTest {

    @Test
    fun concurrentResolutionAlwaysUsesTheSuppliedServerSnapshot() {
        val serverA = passwordServer(id = 1, token = "token-a")
        val serverB = passwordServer(id = 2, token = "token-b")
        val executor = Executors.newFixedThreadPool(4)

        try {
            val calls =
                    (0 until 200).map { index ->
                        Callable {
                            serverBoundStoredCredential(if (index % 2 == 0) serverA else serverB)
                        }
                    }
            val resolved = executor.invokeAll(calls).map { it.get() }

            resolved.forEachIndexed { index, token ->
                assertEquals(if (index % 2 == 0) "token-a" else "token-b", token)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun eachAuthModeResolvesOnlyItsOwnCredential() {
        val apiServer =
                passwordServer(id = 3, token = "stale-cookie").copy(
                        authType = AuthType.API_KEY,
                        apiKey = "server-api-key"
                )
        val guestServer =
                passwordServer(id = 4, token = "must-not-be-used").copy(
                        authType = AuthType.GUEST,
                        apiKey = "must-not-be-used"
                )

        assertEquals("server-api-key", serverBoundStoredCredential(apiServer))
        assertEquals("", serverBoundStoredCredential(guestServer))
    }

    @Test
    fun authenticationIdentityCoversOriginHeadersCredentialsAndSessionToken() {
        val original =
                passwordServer(id = 7, token = "token-a").copy(
                        customHeaders =
                                listOf(
                                        CustomHeader("CF-Access-Client-Id", "client"),
                                        CustomHeader("X-Tenant", "alpha")
                                )
                )
        val metadataOnly =
                original.copy(
                        name = "Renamed",
                        isActive = true,
                        requires2fa = true,
                        createdAt = original.createdAt + 1
                )
        val reorderedHeaderCase =
                original.copy(
                        customHeaders =
                                listOf(
                                        CustomHeader("x-tenant", "alpha"),
                                        CustomHeader("cf-access-client-id", "client")
                                )
                )

        assertTrue(sameServerAuthenticationConfiguration(original, metadataOnly))
        assertTrue(sameServerAuthenticationConfiguration(original, reorderedHeaderCase))
        assertTrue(sameServerAuthenticationState(original, metadataOnly))

        assertFalse(
                sameServerAuthenticationConfiguration(
                        original,
                        original.copy(baseUrl = "https://other.example")
                )
        )
        assertFalse(
                sameServerAuthenticationConfiguration(
                        original,
                        original.copy(password = "replacement")
                )
        )
        assertFalse(
                sameServerAuthenticationConfiguration(
                        original,
                        original.copy(
                                customHeaders =
                                        listOf(
                                                CustomHeader("CF-Access-Client-Id", "client"),
                                                CustomHeader("X-Tenant", "beta")
                                        )
                        )
                )
        )
        assertFalse(
                sameServerAuthenticationState(
                        original,
                        original.copy(sessionToken = "token-b")
                )
        )
    }

    @Test
    fun ordinaryUpdatePreservesDatabaseNewestTokenOnlyForSameAuthenticationIdentity() {
        val stored = passwordServer(id = 8, token = "database-newest-token")
        val staleUiUpdate = stored.copy(name = "New display name", sessionToken = "stale-token")

        val metadataResult = mergeOrdinaryServerUpdate(stored, staleUiUpdate)
        assertEquals("New display name", metadataResult.name)
        assertEquals("database-newest-token", metadataResult.sessionToken)

        assertNull(
                mergeOrdinaryServerUpdate(
                                stored,
                                staleUiUpdate.copy(baseUrl = "https://replacement.example")
                        )
                        .sessionToken
        )
        assertNull(
                mergeOrdinaryServerUpdate(
                                stored,
                                staleUiUpdate.copy(password = "replacement-password")
                        )
                        .sessionToken
        )
        assertNull(
                mergeOrdinaryServerUpdate(
                                stored,
                                staleUiUpdate.copy(
                                        customHeaders = listOf(CustomHeader("X-Origin", "new"))
                                )
                        )
                        .sessionToken
        )
        assertNull(
                mergeOrdinaryServerUpdate(
                                stored,
                                staleUiUpdate.copy(
                                        authType = AuthType.API_KEY,
                                        apiKey = "api-key"
                                )
                        )
                        .sessionToken
        )
    }

    @Test
    fun staleEditMayNotClearTokenWrittenAfterItsAuthenticationBaseline() {
        val editBaseline = passwordServer(id = 11, token = "old-token")
        val storedAfterConcurrentLogin =
                editBaseline.copy(
                        username = "concurrent-user",
                        password = "concurrent-password",
                        sessionToken = "new-token"
                )
        val staleAuthenticationEdit =
                editBaseline.copy(
                        baseUrl = "https://replacement.example",
                        sessionToken = null
                )

        assertFalse(
                canApplyOrdinaryServerUpdate(
                        stored = storedAfterConcurrentLogin,
                        requested = staleAuthenticationEdit,
                        expectedAuthentication = editBaseline
                )
        )

        val metadataOnlyFromStaleToken =
                storedAfterConcurrentLogin.copy(
                        name = "Safe rename",
                        sessionToken = "old-token"
                )
        assertTrue(
                canApplyOrdinaryServerUpdate(
                        stored = storedAfterConcurrentLogin,
                        requested = metadataOnlyFromStaleToken,
                        expectedAuthentication = editBaseline
                )
        )
        assertEquals(
                "new-token",
                mergeOrdinaryServerUpdate(storedAfterConcurrentLogin, metadataOnlyFromStaleToken)
                        .sessionToken
        )
    }

    @Test
    fun explicitLoginAtomicallyCombinesLatestProfileWithNewCredentialsAndToken() {
        val storedAtCompletion =
                passwordServer(id = 9, token = "old-token").copy(
                        name = "Concurrent rename",
                        isActive = true,
                        customHeaders = listOf(CustomHeader("X-Tenant", "alpha"))
                )
        val loginTarget =
                storedAtCompletion.copy(
                        name = "Stale display name",
                        username = "new-user",
                        password = "new-password",
                        sessionToken = "stale-token",
                        requires2fa = true,
                        isActive = false
                )

        val merged =
                mergeSuccessfulExplicitLogin(
                        storedAtCompletion,
                        loginTarget,
                        "fresh-token"
                )

        assertEquals("Concurrent rename", merged.name)
        assertTrue(merged.isActive)
        assertEquals("new-user", merged.username)
        assertEquals("new-password", merged.password)
        assertEquals("fresh-token", merged.sessionToken)
        assertTrue(merged.requires2fa)
    }

    @Test
    fun twoFactorHintUpdateRequiresFullAuthenticationIdentityButAllowsTokenRefresh() {
        val expected = passwordServer(id = 12, token = "old-token")

        assertTrue(
                canUpdateRequiresTwoFactorHint(
                        stored = expected.copy(sessionToken = "new-token"),
                        expectedAuthentication = expected
                )
        )
        assertFalse(
                canUpdateRequiresTwoFactorHint(
                        stored = expected.copy(baseUrl = "https://other.example"),
                        expectedAuthentication = expected
                )
        )
        assertFalse(
                canUpdateRequiresTwoFactorHint(
                        stored = expected.copy(password = "replacement"),
                        expectedAuthentication = expected
                )
        )
        assertFalse(
                canUpdateRequiresTwoFactorHint(
                        stored =
                                expected.copy(
                                        customHeaders = listOf(CustomHeader("X-Tenant", "other"))
                                ),
                        expectedAuthentication = expected
                )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun explicitLoginCannotMoveCredentialsToAnotherOrigin() {
        val stored = passwordServer(id = 10, token = "old-token")
        mergeSuccessfulExplicitLogin(
                stored,
                stored.copy(baseUrl = "https://attacker.example"),
                "fresh-token"
        )
    }

    private fun passwordServer(id: Long, token: String): ServerInstance {
        return ServerInstance(
                id = id,
                name = "server-$id",
                baseUrl = "https://server-$id.example",
                username = "user",
                password = "password",
                sessionToken = token,
                authType = AuthType.PASSWORD
        )
    }
}
