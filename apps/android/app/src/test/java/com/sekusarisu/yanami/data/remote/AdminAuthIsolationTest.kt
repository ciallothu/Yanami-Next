package com.sekusarisu.yanami.data.remote

import com.sekusarisu.yanami.domain.model.AuthType
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminAuthIsolationTest {

    @Test
    fun adminClientMethodsRequireExplicitAuthTypeAndHeaders() {
        assertExplicitAuthContext(
                KomariAdminClientService::class.java,
                listOf(
                        "listClients",
                        "getClient",
                        "addClient",
                        "updateClient",
                        "deleteClient",
                        "getClientToken",
                        "reorderClients"
                )
        )
    }

    @Test
    fun adminPingMethodsRequireExplicitAuthTypeAndHeaders() {
        assertExplicitAuthContext(
                KomariAdminPingService::class.java,
                listOf(
                        "listPingTasks",
                        "addPingTask",
                        "updatePingTasks",
                        "deletePingTasks",
                        "reorderPingTasks"
                )
        )
    }

    private fun assertExplicitAuthContext(serviceClass: Class<*>, methodNames: List<String>) {
        methodNames.forEach { methodName ->
            val method = serviceClass.declaredMethods.singleOrNull { it.name == methodName }
            assertNotNull("Missing admin method $methodName", method)
            val parameterTypes = requireNotNull(method).parameterTypes.toList()
            assertTrue("$methodName must require AuthType", parameterTypes.contains(AuthType::class.java))
            assertTrue("$methodName must require custom headers", parameterTypes.contains(List::class.java))
        }
    }
}
