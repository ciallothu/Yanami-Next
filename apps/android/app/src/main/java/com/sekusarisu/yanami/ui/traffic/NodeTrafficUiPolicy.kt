package com.sekusarisu.yanami.ui.traffic

import com.sekusarisu.yanami.domain.model.Node

/** Explicit UI semantics for the three different network counter families Komari exposes. */
data class NodeTrafficUiValues(
        val uploadRateBytesPerSecond: Long,
        val downloadRateBytesPerSecond: Long,
        val cumulativeUploadBytes: Long,
        val cumulativeDownloadBytes: Long,
        val sampleUploadUsageBytes: Long,
        val sampleDownloadUsageBytes: Long
)

fun Node.toTrafficUiValues(): NodeTrafficUiValues =
        NodeTrafficUiValues(
                uploadRateBytesPerSecond = netOut.coerceAtLeast(0L),
                downloadRateBytesPerSecond = netIn.coerceAtLeast(0L),
                cumulativeUploadBytes = netTotalUp.coerceAtLeast(0L),
                cumulativeDownloadBytes = netTotalDown.coerceAtLeast(0L),
                sampleUploadUsageBytes = trafficUp.coerceAtLeast(0L),
                sampleDownloadUsageBytes = trafficDown.coerceAtLeast(0L)
        )
