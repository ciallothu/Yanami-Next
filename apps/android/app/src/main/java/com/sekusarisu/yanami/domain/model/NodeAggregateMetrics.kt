package com.sekusarisu.yanami.domain.model

/**
 * Aggregate metrics used by the node overview and widget.
 *
 * Speeds and per-refresh sample usage describe the current online fleet. Cumulative traffic usage is
 * retained by Komari for offline nodes too, so it must include every visible node.
 */
data class NodeAggregateMetrics(
        val onlineCount: Int,
        val offlineCount: Int,
        val netIn: Long,
        val netOut: Long,
        val trafficUsageUp: Long,
        val trafficUsageDown: Long,
        val sampleUsageUp: Long,
        val sampleUsageDown: Long
)

fun aggregateNodeMetrics(nodes: List<Node>): NodeAggregateMetrics {
    val onlineNodes = nodes.filter { it.isOnline }
    return NodeAggregateMetrics(
            onlineCount = onlineNodes.size,
            offlineCount = nodes.size - onlineNodes.size,
            netIn = onlineNodes.saturatingNonNegativeSum { it.netIn },
            netOut = onlineNodes.saturatingNonNegativeSum { it.netOut },
            trafficUsageUp = nodes.saturatingNonNegativeSum { it.netTotalUp },
            trafficUsageDown = nodes.saturatingNonNegativeSum { it.netTotalDown },
            sampleUsageUp = onlineNodes.saturatingNonNegativeSum { it.trafficUp },
            sampleUsageDown = onlineNodes.saturatingNonNegativeSum { it.trafficDown }
    )
}

private inline fun <T> Iterable<T>.saturatingNonNegativeSum(selector: (T) -> Long): Long {
    var total = 0L
    for (item in this) {
        total = saturatingNonNegativeAdd(total, selector(item))
        if (total == Long.MAX_VALUE) return total
    }
    return total
}
