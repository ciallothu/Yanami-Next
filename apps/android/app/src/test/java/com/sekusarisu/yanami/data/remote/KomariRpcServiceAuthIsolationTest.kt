package com.sekusarisu.yanami.data.remote

import com.sekusarisu.yanami.domain.model.AuthType
import io.ktor.client.HttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KomariRpcServiceAuthIsolationTest {

    @Test
    fun rpcServiceHasNoGlobalSessionDependency() {
        val constructor = KomariRpcService::class.java.declaredConstructors.single()

        assertArrayEquals(arrayOf(HttpClient::class.java), constructor.parameterTypes)
    }

    @Test
    fun oneShotRpcMethodsRequireExplicitAuthTypeAndHeaders() {
        listOf(
                        "getNodes",
                        "getNodesLatestStatus",
                        "getVersion",
                        "getNodeRecentStatus",
                        "getNodePingRecords"
                )
                .forEach { methodName ->
                    val method =
                            KomariRpcService::class.java.declaredMethods.singleOrNull {
                                it.name == methodName
                            }
                    assertNotNull("Missing RPC method $methodName", method)
                    val parameterTypes = requireNotNull(method).parameterTypes.toList()
                    assertTrue(parameterTypes.contains(AuthType::class.java))
                    assertTrue(parameterTypes.contains(List::class.java))
                }
    }
}
