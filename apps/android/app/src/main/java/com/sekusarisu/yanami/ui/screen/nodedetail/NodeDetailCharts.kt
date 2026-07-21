package com.sekusarisu.yanami.ui.screen.nodedetail

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.sekusarisu.yanami.R
import com.sekusarisu.yanami.domain.model.PingTask
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun PingTaskChart(
        task: PingTask,
        values: List<Double>,
        times: List<String>,
        segments: List<NodeDetailContract.PingChartSegment> =
                listOf(
                        NodeDetailContract.PingChartSegment(
                                xValues = values.indices.map(Int::toDouble),
                                values = values
                        )
                ).filter { it.values.isNotEmpty() },
        allTimes: List<String> = times,
        packetLossXValues: List<Double> = emptyList(),
        totalSamples: Int = values.size,
        packetLossSamples: Int = 0,
        chartAnimationEnabled: Boolean = true
) {
    val pointCount = minOf(values.size, times.size)
    val chartValues = remember(values, times) { values.take(pointCount) }
    val chartTimes = remember(values, times) { times.take(pointCount) }
    val chartSegments =
            remember(segments) {
                segments.filter { segment ->
                    segment.xValues.size == segment.values.size &&
                            segment.values.isNotEmpty() &&
                            segment.values.all { it.isFinite() && it >= 0.0 }
                }
            }
    val chartAllTimes = remember(allTimes) { allTimes.toList() }
    val chartLossXValues = remember(packetLossXValues) { packetLossXValues.toList() }
    val lossMarkerY = remember(chartValues) { chartValues.minOrNull() ?: 0.0 }

    Column {
        Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                    text = task.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                    text =
                            (task.latest
                                    ?.takeIf {
                                        it >= 0.0 && task.loss?.let { loss -> loss < 100.0 } != false
                                    }
                                    ?.let { "%.0fms".format(it) }
                                    ?: "—") +
                                    " / " +
                                    (task.loss?.let { "%.1f%%".format(it) } ?: "—"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        if (chartSegments.isNotEmpty() || chartLossXValues.isNotEmpty()) {
            val modelProducer = remember { CartesianChartModelProducer() }
            LaunchedEffect(chartSegments, chartLossXValues, lossMarkerY) {
                modelProducer.runTransaction {
                    lineSeries {
                        chartSegments.forEach { segment ->
                            series(segment.xValues, segment.values)
                        }
                        if (chartLossXValues.isNotEmpty()) {
                            series(
                                    chartLossXValues,
                                    List(chartLossXValues.size) { lossMarkerY }
                            )
                        }
                    }
                }
            }

            val yAxisFormatter = remember {
                CartesianValueFormatter { _, value, _ -> "%.0fms".format(value) }
            }
            val xAxisFormatter =
                    remember(chartAllTimes) {
                        CartesianValueFormatter { _, value, _ ->
                            if (chartAllTimes.isEmpty()) return@CartesianValueFormatter ""
                            val index = value.toInt().coerceIn(chartAllTimes.indices)
                            parseTimeLabel(chartAllTimes[index])
                        }
                    }

            val pingLine = rememberPingLine(MaterialTheme.colorScheme.tertiary)
            val lossLine = rememberLossMarkerLine(MaterialTheme.colorScheme.error)
            val lineStyles =
                    remember(pingLine, lossLine, chartSegments.size, chartLossXValues) {
                        buildList {
                            repeat(chartSegments.size) { add(pingLine) }
                            if (chartLossXValues.isNotEmpty()) add(lossLine)
                        }
                    }
            CartesianChartHost(
                    chart =
                            rememberCartesianChart(
                                    rememberLineCartesianLayer(
                                            LineCartesianLayer.LineProvider.series(lineStyles)
                                    ),
                                    startAxis =
                                            VerticalAxis.rememberStart(
                                                    valueFormatter = yAxisFormatter
                                            ),
                                    bottomAxis =
                                            HorizontalAxis.rememberBottom(
                                                    valueFormatter = xAxisFormatter
                                            )
                            ),
                    modelProducer = modelProducer,
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    scrollState = rememberVicoScrollState(scrollEnabled = false),
                    zoomState =
                            rememberVicoZoomState(
                                    zoomEnabled = false,
                                    initialZoom = Zoom.Content
                            ),
                    animationSpec =
                            if (chartAnimationEnabled) {
                                tween(durationMillis = 300, easing = LinearEasing)
                            } else null,
                    animateIn = chartAnimationEnabled
            )
            if (chartLossXValues.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                        text = stringResource(R.string.node_detail_packet_loss_marker),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                )
            }
        } else {
            ChartEmptyState(
                    height = 100.dp,
                    message =
                            if (totalSamples > 0 && packetLossSamples == totalSamples) {
                                stringResource(R.string.node_detail_all_samples_lost)
                            } else {
                                stringResource(R.string.node_detail_insufficient_data)
                            }
            )
        }
    }
}

private fun parseTimeLabel(isoTime: String): String {
    return try {
        val instant = Instant.parse(isoTime)
        val localTime = instant.atZone(ZoneId.systemDefault()).toLocalTime()
        "%02d:%02d".format(localTime.hour, localTime.minute)
    } catch (_: Exception) {
        ""
    }
}

private fun formatChartSpeed(bytesPerSec: Double): String {
    return when {
        bytesPerSec >= 1_073_741_824 -> "%.1f GB/s".format(bytesPerSec / 1_073_741_824)
        bytesPerSec >= 1_048_576 -> "%.1f MB/s".format(bytesPerSec / 1_048_576)
        bytesPerSec >= 1024 -> "%.0f KB/s".format(bytesPerSec / 1024)
        else -> "%.0f B/s".format(bytesPerSec)
    }
}

private fun formatChartBytes(bytes: Double): String {
    return when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024)
        else -> "%.0f B".format(bytes)
    }
}

@Composable
internal fun ChartCard(
        title: String,
        data: List<Double>,
        times: List<String>,
        color: Color,
        suffix: String,
        chartAnimationEnabled: Boolean = true
) {
    val pointCount = minOf(data.size, times.size)
    val chartData = remember(data, times) { data.take(pointCount) }
    val chartTimes = remember(data, times) { times.take(pointCount) }
    val themedLine = rememberThemedLine(color)
    Column {
        Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                    text = chartData.lastOrNull()?.let { "%.0f".format(it) + suffix } ?: "—",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        if (chartData.size >= 2) {
            val modelProducer = remember { CartesianChartModelProducer() }
            LaunchedEffect(chartData) {
                modelProducer.runTransaction { lineSeries { series(chartData) } }
            }

            val yAxisFormatter =
                    remember(suffix) {
                        CartesianValueFormatter { _, value, _ -> "%.0f".format(value) + suffix }
                    }
            val xAxisFormatter =
                    remember(chartTimes) {
                        CartesianValueFormatter { _, value, _ ->
                            val index = value.toInt().coerceIn(chartTimes.indices)
                            parseTimeLabel(chartTimes[index])
                        }
                    }

            CartesianChartHost(
                    chart =
                            rememberCartesianChart(
                                    rememberLineCartesianLayer(
                                            LineCartesianLayer.LineProvider.series(themedLine)
                                    ),
                                    startAxis = VerticalAxis.rememberStart(valueFormatter = yAxisFormatter),
                                    bottomAxis =
                                            HorizontalAxis.rememberBottom(
                                                    valueFormatter = xAxisFormatter
                                            )
                            ),
                    modelProducer = modelProducer,
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    scrollState = rememberVicoScrollState(scrollEnabled = false),
                    zoomState =
                            rememberVicoZoomState(
                                    zoomEnabled = false,
                                    initialZoom = Zoom.Content
                            ),
                    animationSpec =
                            if (chartAnimationEnabled) {
                                tween(durationMillis = 300, easing = LinearEasing)
                            } else null,
                    animateIn = chartAnimationEnabled
            )
        } else {
            ChartEmptyState()
        }
    }
}

@Composable
internal fun ConnectionChartCard(
        title: String,
        tcpData: List<Int>,
        udpData: List<Int>,
        times: List<String>,
        suffix: String,
        chartAnimationEnabled: Boolean = true
) {
    val pointCount = minOf(tcpData.size, udpData.size, times.size)
    val chartTcpData = remember(tcpData, udpData, times) { tcpData.take(pointCount) }
    val chartUdpData = remember(tcpData, udpData, times) { udpData.take(pointCount) }
    val chartTimes = remember(tcpData, udpData, times) { times.take(pointCount) }
    val tcpLine = rememberThemedLine(MaterialTheme.colorScheme.primary)
    val udpLine = rememberThemedLine(MaterialTheme.colorScheme.tertiary)
    Column {
        Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append("TCP " + (chartTcpData.lastOrNull()?.toString() ?: "—") + suffix)
                        }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append(" / ")
                        }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.tertiary)) {
                            append("UDP " + (chartUdpData.lastOrNull()?.toString() ?: "—") + suffix)
                        }
                    },
                    style = MaterialTheme.typography.labelMedium
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        if (pointCount >= 2) {
            val modelProducer = remember { CartesianChartModelProducer() }
            LaunchedEffect(chartTcpData, chartUdpData) {
                modelProducer.runTransaction {
                    lineSeries {
                        series(chartTcpData)
                        series(chartUdpData)
                    }
                }
            }

            val yAxisFormatter =
                    remember(suffix) {
                        CartesianValueFormatter { _, value, _ -> "%.0f".format(value) + suffix }
                    }
            val xAxisFormatter =
                    remember(chartTimes) {
                        CartesianValueFormatter { _, value, _ ->
                            val index = value.toInt().coerceIn(chartTimes.indices)
                            parseTimeLabel(chartTimes[index])
                        }
                    }

            CartesianChartHost(
                    chart =
                            rememberCartesianChart(
                                    rememberLineCartesianLayer(
                                            LineCartesianLayer.LineProvider.series(tcpLine, udpLine)
                                    ),
                                    startAxis = VerticalAxis.rememberStart(valueFormatter = yAxisFormatter),
                                    bottomAxis =
                                            HorizontalAxis.rememberBottom(
                                                    valueFormatter = xAxisFormatter
                                            )
                            ),
                    modelProducer = modelProducer,
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    scrollState = rememberVicoScrollState(scrollEnabled = false),
                    zoomState =
                            rememberVicoZoomState(
                                    zoomEnabled = false,
                                    initialZoom = Zoom.Content
                            ),
                    animationSpec =
                            if (chartAnimationEnabled) {
                                tween(durationMillis = 300, easing = LinearEasing)
                            } else null,
                    animateIn = chartAnimationEnabled
            )
        } else {
            ChartEmptyState()
        }
    }
}

@Composable
internal fun NetworkChartCard(
        title: String,
        netInData: List<Double>,
        netOutData: List<Double>,
        times: List<String>,
        chartAnimationEnabled: Boolean = true,
        showAsSpeed: Boolean = true
) {
    val pointCount = minOf(netInData.size, netOutData.size, times.size)
    val chartNetInData = remember(netInData, netOutData, times) { netInData.take(pointCount) }
    val chartNetOutData = remember(netInData, netOutData, times) { netOutData.take(pointCount) }
    val chartTimes = remember(netInData, netOutData, times) { times.take(pointCount) }
    val formatter: (Double) -> String = if (showAsSpeed) ::formatChartSpeed else ::formatChartBytes
    val upLine = rememberThemedLine(MaterialTheme.colorScheme.primary)
    val downLine = rememberThemedLine(MaterialTheme.colorScheme.tertiary)
    Column {
        Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.tertiary)) {
                            append("↓ " + (chartNetInData.lastOrNull()?.let(formatter) ?: "—"))
                        }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append(" / ")
                        }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                            append("↑ " + (chartNetOutData.lastOrNull()?.let(formatter) ?: "—"))
                        }
                    },
                    style = MaterialTheme.typography.labelMedium
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        if (pointCount >= 2) {
            val modelProducer = remember { CartesianChartModelProducer() }
            LaunchedEffect(chartNetInData, chartNetOutData) {
                modelProducer.runTransaction {
                    lineSeries {
                        series(chartNetOutData)
                        series(chartNetInData)
                    }
                }
            }

            val yAxisFormatter = remember(showAsSpeed) {
                CartesianValueFormatter { _, value, _ -> formatter(value) }
            }
            val xAxisFormatter =
                    remember(chartTimes) {
                        CartesianValueFormatter { _, value, _ ->
                            val index = value.toInt().coerceIn(chartTimes.indices)
                            parseTimeLabel(chartTimes[index])
                        }
                    }

            CartesianChartHost(
                    chart =
                            rememberCartesianChart(
                                    rememberLineCartesianLayer(
                                            LineCartesianLayer.LineProvider.series(upLine, downLine)
                                    ),
                                    startAxis = VerticalAxis.rememberStart(valueFormatter = yAxisFormatter),
                                    bottomAxis =
                                            HorizontalAxis.rememberBottom(
                                                    valueFormatter = xAxisFormatter
                                            )
                            ),
                    modelProducer = modelProducer,
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    scrollState = rememberVicoScrollState(scrollEnabled = false),
                    zoomState =
                            rememberVicoZoomState(
                                    zoomEnabled = false,
                                    initialZoom = Zoom.Content
                            ),
                    animationSpec =
                            if (chartAnimationEnabled) {
                                tween(durationMillis = 300, easing = LinearEasing)
                            } else null,
                    animateIn = chartAnimationEnabled
            )
        } else {
            ChartEmptyState()
        }
    }
}

@Composable
private fun ChartEmptyState(
        height: androidx.compose.ui.unit.Dp = 80.dp,
        message: String? = null
) {
    Box(
            modifier = Modifier.fillMaxWidth().height(height),
            contentAlignment = Alignment.Center
    ) {
        Text(
                text = message ?: stringResource(R.string.node_detail_insufficient_data),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun rememberThemedLine(color: Color): LineCartesianLayer.Line =
        LineCartesianLayer.rememberLine(
                fill = LineCartesianLayer.LineFill.single(Fill(color)),
                areaFill =
                        LineCartesianLayer.AreaFill.single(
                                Fill(
                                        Brush.verticalGradient(
                                                listOf(color.copy(alpha = 0.32f), Color.Transparent)
                                        )
                                )
                        )
        )

@Composable
private fun rememberPingLine(color: Color): LineCartesianLayer.Line {
    val point = rememberShapeComponent(Fill(color), CircleShape)
    return LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(Fill(color)),
            areaFill = null,
            pointProvider =
                    LineCartesianLayer.PointProvider.single(
                            LineCartesianLayer.Point(point, size = 5.dp)
                    )
    )
}

@Composable
private fun rememberLossMarkerLine(color: Color): LineCartesianLayer.Line {
    val point = rememberShapeComponent(Fill(color), CircleShape)
    return LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 0.dp),
            areaFill = null,
            pointProvider =
                    LineCartesianLayer.PointProvider.single(
                            LineCartesianLayer.Point(point, size = 7.dp)
                    )
    )
}
