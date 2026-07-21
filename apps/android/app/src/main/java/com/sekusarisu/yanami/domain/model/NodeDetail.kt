package com.sekusarisu.yanami.domain.model

/**
 * 领域模型 — 负载历史记录
 *
 * 对应 RPC `common:getRecords` (type=load) 的单条记录。 百分比字段由 Repository 层计算得出。
 */
data class LoadRecord(
        val time: String, // ISO 8601
        val cpu: Double, // %
        val ramPercent: Double, // 已用/总量 百分比
        val diskPercent: Double, // 已用/总量 百分比
        val netIn: Long, // bytes/s
        val netOut: Long, // bytes/s
        val load: Double,
        val process: Int,
        val connections: Int,
        val connectionsUdp: Int = 0,
        val netTotalUp: Long = 0,
        val netTotalDown: Long = 0,
        /** Bytes transferred during this server sampling interval. */
        val trafficUp: Long = 0,
        val trafficDown: Long = 0
)

/**
 * 领域模型 — Ping 历史记录
 *
 * 对应 RPC `common:getRecords` (type=ping) 的单条记录。 通过 taskName 关联具体的检测任务名称。
 */
data class PingRecord(
        val taskId: Int,
        val taskName: String,
        val time: String, // ISO 8601
        /** Round-trip latency in milliseconds; a negative value is a packet-loss sample. */
        val value: Double
)

/**
 * 领域模型 — Ping 检测任务
 *
 * 对应 RPC `common:getRecords` (type=ping) 的 basic_info。
 */
data class PingTask(
        val id: Int,
        val name: String,
        val interval: Int,
        val latest: Double?,
        val min: Double?,
        val max: Double?,
        val avg: Double?,
        /** Packet-loss percentage, or null when neither Komari nor real records can provide it. */
        val loss: Double?,
        val p50: Double?,
        val p99: Double?,
        /** Number of attempts in the requested server-side window, including packet loss. */
        val total: Int? = null,
        /** Komari's server-side `(P99 - P50) / adjusted P50` fluctuation ratio. */
        val jitterRatio: Double? = null,
        /** True when this complete summary came directly from Komari. */
        val statisticsAreServerCalculated: Boolean = true,
        /** False when neither a complete server summary nor real records are available. */
        val statisticsAreAvailable: Boolean = true
)

/** A node-scoped Ping response. Komari filters [tasks] to tasks bound to the requested node. */
data class NodePingHistory(
        val tasks: List<PingTask> = emptyList(),
        val records: List<PingRecord> = emptyList()
)
