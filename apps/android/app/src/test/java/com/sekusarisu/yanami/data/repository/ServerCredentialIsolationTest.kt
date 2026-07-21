package com.sekusarisu.yanami.data.repository

import com.sekusarisu.yanami.domain.model.AuthType
import com.sekusarisu.yanami.domain.model.ServerInstance
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
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
