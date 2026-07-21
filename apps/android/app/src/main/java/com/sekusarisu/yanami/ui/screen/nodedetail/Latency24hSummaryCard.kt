package com.sekusarisu.yanami.ui.screen.nodedetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sekusarisu.yanami.R
import com.sekusarisu.yanami.domain.model.PingTask
import java.util.Locale

/** Compact, node-scoped 24-hour statistics. Every task is rendered independently. */
@Composable
internal fun Latency24hSummaryCard(
        tasks: List<PingTask>,
        samplesByTaskId: Map<Int, List<Double>>,
        isLoading: Boolean,
        hasError: Boolean
) {
    Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                        text = stringResource(R.string.node_detail_latency_summary_24h),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                )
                Text(
                        text = stringResource(R.string.node_detail_latency_per_task),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            when {
                isLoading -> {
                    Box(
                            modifier = Modifier.fillMaxWidth().height(72.dp),
                            contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
                hasError -> LatencySummaryMessage(
                        text = stringResource(R.string.node_detail_latency_unavailable),
                        isError = true
                )
                tasks.isEmpty() -> LatencySummaryMessage(
                        text = stringResource(R.string.node_detail_latency_no_tasks)
                )
                else -> tasks.forEachIndexed { index, task ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                    LatencyTaskSummary(
                            task = task,
                            samples = samplesByTaskId[task.id].orEmpty()
                    )
                }
            }
        }
    }
}

@Composable
private fun LatencyTaskSummary(task: PingTask, samples: List<Double>) {
    val hideLatencyStatistics = task.loss == 100.0 || task.total == 0
    val averageText =
            if (hideLatencyStatistics) "—" else task.avg?.let(::formatLatency) ?: "—"
    val latestText =
            if (hideLatencyStatistics) "—" else task.latest?.let(::formatLatency) ?: "—"
    val lossText = task.loss?.let(::formatPercent) ?: "—"
    val minText = if (hideLatencyStatistics) "—" else task.min?.let(::formatLatency) ?: "—"
    val p50Text = if (hideLatencyStatistics) "—" else task.p50?.let(::formatLatency) ?: "—"
    val p99Text = if (hideLatencyStatistics) "—" else task.p99?.let(::formatLatency) ?: "—"
    val maxText = if (hideLatencyStatistics) "—" else task.max?.let(::formatLatency) ?: "—"
    val jitterText =
            if (hideLatencyStatistics) "—"
            else task.jitterRatio?.let(::formatRatio) ?: "—"
    val sampleCountText =
            task.total?.let { total ->
                pluralStringResource(R.plurals.node_detail_latency_samples, total, total)
            } ?: "—"
    val semanticsDescription =
            stringResource(
                    R.string.node_detail_latency_accessibility,
                    task.name,
                    averageText,
                    latestText,
                    lossText,
                    sampleCountText,
                    minText,
                    p50Text,
                    p99Text,
                    maxText,
                    jitterText
            )

    Column(
            modifier =
                    Modifier.fillMaxWidth().clearAndSetSemantics {
                        contentDescription = semanticsDescription
                    }
    ) {
        Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                    text = task.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                        text = sampleCountText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                        text =
                                stringResource(
                                        when {
                                            !task.statisticsAreAvailable ->
                                                    R.string.node_detail_latency_unavailable
                                            task.statisticsAreServerCalculated ->
                                                    R.string.node_detail_server_calculated
                                            else -> R.string.node_detail_records_fallback
                                        }
                                ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                )
            }
        }

        if (task.total == 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                    text = stringResource(R.string.node_detail_latency_no_samples),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        Spacer(modifier = Modifier.height(10.dp))
        LatencyMetricTiles(
                averageText = averageText,
                latestText = latestText,
                lossPercent = task.loss,
                lossText = lossText,
                jitterText = jitterText
        )

        LatencyRecentSamples(samples)

        Spacer(modifier = Modifier.height(10.dp))
        LatencyStatsGrid(minText, p50Text, p99Text, maxText)
    }
}

@Composable
private fun LatencyRecentSamples(samples: List<Double>) {
    val recentSamples =
            remember(samples) {
                samples.asSequence()
                        .filter(Double::isFinite)
                        .toList()
                        .takeLast(MAX_VISIBLE_LATENCY_SAMPLES)
            }
    if (recentSamples.isEmpty()) return

    val sampleCountText =
            pluralStringResource(
                    R.plurals.node_detail_latency_recent_samples,
                    recentSamples.size,
                    recentSamples.size
            )
    val successColor = MaterialTheme.colorScheme.primary
    val lossColor = MaterialTheme.colorScheme.error
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val maxSuccessfulLatency =
            remember(recentSamples) {
                recentSamples.filter { it >= 0.0 }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
            }

    Spacer(modifier = Modifier.height(10.dp))
    Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
                text = sampleCountText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
                text = stringResource(R.string.node_detail_latency_sample_legend),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
    Canvas(modifier = Modifier.fillMaxWidth().height(38.dp)) {
        val gap = 2.dp.toPx()
        val barWidth =
                ((size.width - gap * (recentSamples.size - 1)) / recentSamples.size)
                        .coerceAtLeast(1.dp.toPx())
        val radius = (barWidth / 2f).coerceAtMost(3.dp.toPx())
        recentSamples.forEachIndexed { index, value ->
            val left = index * (barWidth + gap)
            drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(left, 0f),
                    size = Size(barWidth, size.height),
                    cornerRadius = CornerRadius(radius, radius)
            )
            val isLoss = value < 0.0
            val fraction =
                    if (isLoss) 1f
                    else (value / maxSuccessfulLatency).toFloat().coerceIn(0.12f, 1f)
            val barHeight = size.height * fraction
            drawRoundRect(
                    color = if (isLoss) lossColor else successColor,
                    topLeft = Offset(left, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(radius, radius)
            )
        }
    }
}

@Composable
private fun LatencyMetricTiles(
        averageText: String,
        latestText: String,
        lossPercent: Double?,
        lossText: String,
        jitterText: String
) {
    val averageLabel = stringResource(R.string.node_detail_latency_average)
    val latestLabel = stringResource(R.string.node_detail_latency_latest_value, latestText)
    val fluctuationLabel =
            stringResource(R.string.node_detail_latency_fluctuation_value, jitterText)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 300.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LatencyMetricTile(
                        label = averageLabel,
                        value = averageText,
                        supporting = latestLabel,
                        modifier = Modifier.fillMaxWidth()
                )
                PacketLossMetricTile(
                        lossPercent = lossPercent,
                        value = lossText,
                        supporting = fluctuationLabel,
                        modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LatencyMetricTile(
                        label = averageLabel,
                        value = averageText,
                        supporting = latestLabel,
                        modifier = Modifier.weight(1f)
                )
                PacketLossMetricTile(
                        lossPercent = lossPercent,
                        value = lossText,
                        supporting = fluctuationLabel,
                        modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun LatencyStatsGrid(
        minText: String,
        p50Text: String,
        p99Text: String,
        maxText: String
) {
    val minLabel = stringResource(R.string.node_detail_latency_min)
    val maxLabel = stringResource(R.string.node_detail_latency_max)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 300.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    LatencyStat(minLabel, minText, Modifier.weight(1f))
                    LatencyStat("P50", p50Text, Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    LatencyStat("P99", p99Text, Modifier.weight(1f))
                    LatencyStat(maxLabel, maxText, Modifier.weight(1f))
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                LatencyStat(minLabel, minText, Modifier.weight(1f))
                LatencyStat("P50", p50Text, Modifier.weight(1f))
                LatencyStat("P99", p99Text, Modifier.weight(1f))
                LatencyStat(maxLabel, maxText, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LatencyMetricTile(
        label: String,
        value: String,
        supporting: String,
        modifier: Modifier = Modifier
) {
    Surface(
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                    text = supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PacketLossMetricTile(
        lossPercent: Double?,
        value: String,
        supporting: String,
        modifier: Modifier = Modifier
) {
    val safeLoss = lossPercent?.takeIf { it.isFinite() && it in 0.0..100.0 }
    Surface(
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                    text = stringResource(R.string.node_detail_packet_loss),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (safeLoss != null) {
                LinearProgressIndicator(
                        progress = { (safeLoss / 100.0).toFloat() },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = MaterialTheme.colorScheme.error,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                    text = supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LatencyStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1
        )
    }
}

@Composable
private fun LatencySummaryMessage(text: String, isError: Boolean = false) {
    Box(
            modifier = Modifier.fillMaxWidth().height(72.dp),
            contentAlignment = Alignment.Center
    ) {
        Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color =
                        if (isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatLatency(value: Double): String =
        String.format(Locale.getDefault(), "%.0f ms", value.coerceAtLeast(0.0))

private fun formatPercent(value: Double): String =
        String.format(Locale.getDefault(), "%.1f%%", value.coerceIn(0.0, 100.0))

private fun formatRatio(value: Double): String =
        String.format(Locale.getDefault(), "%.2f×", value.coerceAtLeast(0.0))

private const val MAX_VISIBLE_LATENCY_SAMPLES = 32
