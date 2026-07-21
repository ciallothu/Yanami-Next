package com.sekusarisu.yanami.ui.screen.nodelist

import com.sekusarisu.yanami.domain.model.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeListFilterTest {
    @Test fun maskedIpCannotBeUsedAsSearchOracle() {
        val node = sampleNode()

        assertEquals(
                listOf(node),
                filterNodes(
                        nodes = listOf(node),
                        searchQuery = "203.0.113.42",
                        selectedGroup = null,
                        statusFilter = NodeListContract.StatusFilter.ALL,
                        maskIpAddresses = false
                )
        )
        assertTrue(
                filterNodes(
                                nodes = listOf(node),
                                searchQuery = "203.0.113.42",
                                selectedGroup = null,
                                statusFilter = NodeListContract.StatusFilter.ALL,
                                maskIpAddresses = true
                        )
                        .isEmpty()
        )
        assertTrue(
                filterNodes(
                                nodes = listOf(node),
                                searchQuery = "2001:db8::42",
                                selectedGroup = null,
                                statusFilter = NodeListContract.StatusFilter.ALL,
                                maskIpAddresses = true
                        )
                        .isEmpty()
        )
    }

    private fun sampleNode() = Node(
            uuid = "node",
            name = "Node",
            region = "",
            group = "",
            isOnline = true,
            cpuUsage = 0.0,
            memUsed = 0,
            memTotal = 0,
            swapUsed = 0,
            swapTotal = 0,
            diskUsed = 0,
            diskTotal = 0,
            netIn = 0,
            netOut = 0,
            netTotalUp = 0,
            netTotalDown = 0,
            uptime = 0,
            os = "",
            cpuName = "",
            cpuCores = 0,
            weight = 0,
            load1 = 0.0,
            load5 = 0.0,
            load15 = 0.0,
            process = 0,
            connectionsTcp = 0,
            connectionsUdp = 0,
            ipv4 = "203.0.113.42",
            ipv6 = "2001:db8::42"
    )
}
