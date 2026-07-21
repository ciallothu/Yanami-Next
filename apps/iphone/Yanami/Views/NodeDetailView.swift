import SwiftUI
import Charts

struct NodeDetailView: View {
    @EnvironmentObject private var store: AppStore
    let nodeId: String

    var body: some View {
        List {
            if store.nodeDetail.isLoading && store.nodeDetail.node == nil {
                ProgressView("Loading detail")
            } else if let node = store.nodeDetail.node {
                Section {
                    NodeHeaderView(node: node)
                }

                if let error = store.nodeDetail.error {
                    Section {
                        Label {
                            Text(error)
                        } icon: {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundColor(.orange)
                        }
                        .font(.caption)
                    }
                }

                Section("Live Resources") {
                    HStack {
                        CircularUsageIndicator(
                            label: "CPU",
                            percent: node.cpuUsage,
                            detail: node.cpuCores > 0 ? "\(node.cpuCores) Core" : ""
                        )
                        Spacer()
                        CircularUsageIndicator(
                            label: "RAM",
                            percent: node.memTotal > 0 ? Double(node.memUsed) / Double(node.memTotal) * 100 : 0,
                            detail: "\(Formatters.bytes(node.memUsed)) / \(Formatters.bytes(node.memTotal))"
                        )
                        Spacer()
                        CircularUsageIndicator(
                            label: "DISK",
                            percent: node.diskTotal > 0 ? Double(node.diskUsed) / Double(node.diskTotal) * 100 : 0,
                            detail: "\(Formatters.bytes(node.diskUsed)) / \(Formatters.bytes(node.diskTotal))"
                        )
                    }
                    .padding(.vertical, 8)
                }

                Section("Network") {
                    DetailLine(
                        localized("Traffic"),
                        "↑ \(Formatters.rate(node.netOut))  ↓ \(Formatters.rate(node.netIn))"
                    )
                    DetailLine(
                        localized("Cumulative Usage"),
                        "↑ \(Formatters.bytes(node.netTotalUp))  ↓ \(Formatters.bytes(node.netTotalDown))"
                    )
                    if let usage = node.trafficLimitUsage {
                        QuotaUsageMeter(
                            used: usage.used,
                            limit: node.trafficLimit,
                            fraction: usage.fraction,
                            accountingMode: localized(trafficLimitAccountingLabelKey(node.trafficLimitType))
                        )
                    }
                }

                Section("Latency Statistics (24h)") {
                    Latency24HourSummary(
                        tasks: store.nodeDetail.ping24HourTasks,
                        records: store.nodeDetail.ping24HourRecords,
                        error: store.nodeDetail.ping24HourError,
                        isLoading: store.nodeDetail.isLoadingPing24Hours,
                        animationEnabled: store.settings.chartAnimationEnabled,
                        language: store.settings.language
                    )
                }

                Section("System") {
                    DetailLine("UUID", node.uuid)
                    if !node.ipv4.isEmpty {
                        DetailLine("IPv4", displayedAddress(node.ipv4))
                    }
                    if !node.ipv6.isEmpty {
                        DetailLine("IPv6", displayedAddress(node.ipv6))
                    }
                    DetailLine("CPU", [node.cpuName, node.cpuCores > 0 ? "\(node.cpuCores) cores" : ""].filter { !$0.isEmpty }.joined(separator: " / "))
                    DetailLine("Kernel", node.kernelVersion)
                    DetailLine("Virtualization", node.virtualization)
                    DetailLine("Arch", node.arch)
                    DetailLine("GPU", node.gpuName)
                    DetailLine("Uptime", Formatters.uptime(node.uptime))
                    DetailLine("Load", "\(Formatters.number(node.load1, digits: 2)) / \(Formatters.number(node.load5, digits: 2)) / \(Formatters.number(node.load15, digits: 2))")
                    DetailLine("Connections", "TCP \(node.connectionsTcp), UDP \(node.connectionsUdp)")
                    DetailLine("Processes", "\(node.process)")
                    if !node.statusTime.isEmpty {
                        DetailLine(localized("Last Update"), node.statusTime)
                    }
                    if let expiredAt = node.expiredAt, !expiredAt.isEmpty {
                        DetailLine("Expires", expiredAt)
                    }
                }

                Section("Load Records") {
                    Picker("Range", selection: Binding(
                        get: { store.nodeDetail.loadHours },
                        set: { store.setLoadHours($0) }
                    )) {
                        Text("Realtime").tag(0)
                        Text("1h").tag(1)
                        Text("6h").tag(6)
                        Text("24h").tag(24)
                    }
                    .pickerStyle(.segmented)
                    
                    if !store.nodeDetail.loadRecords.isEmpty {
                        LoadPeakGrid(records: store.nodeDetail.loadRecords)
                        PercentLoadChart(
                            records: store.nodeDetail.loadRecords,
                            animationEnabled: store.settings.chartAnimationEnabled
                        )
                        NetworkLoadChart(
                            records: store.nodeDetail.loadRecords,
                            animationEnabled: store.settings.chartAnimationEnabled
                        )
                        UsagePerSampleChart(
                            records: store.nodeDetail.loadRecords,
                            animationEnabled: store.settings.chartAnimationEnabled
                        )
                        CountLoadChart(
                            records: store.nodeDetail.loadRecords,
                            animationEnabled: store.settings.chartAnimationEnabled
                        )
                    } else {
                        Text("No load records")
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .center)
                            .padding()
                    }
                }

                Section("Ping Monitoring") {
                    Picker("Range", selection: Binding(
                        get: { store.nodeDetail.pingHours },
                        set: { store.setPingHours($0) }
                    )) {
                        Text("1h").tag(1)
                        Text("6h").tag(6)
                        Text("24h").tag(24)
                    }
                    .pickerStyle(.segmented)
                    
                    if !store.nodeDetail.pingTasks.isEmpty {
                        let recordsByTaskID = Dictionary(
                            grouping: store.nodeDetail.pingRecords,
                            by: \.taskId
                        )
                        ForEach(store.nodeDetail.pingTasks) { task in
                            VStack(alignment: .leading, spacing: 4) {
                                let taskRecords = recordsByTaskID[task.id] ?? []
                                let taskStatus = pingTaskStatus(task: task, records: taskRecords)
                                HStack {
                                    Text(task.name)
                                        .font(.subheadline.bold())
                                    Spacer()
                                    Text(taskStatus)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .accessibilityLabel(Text("Latest latency and packet loss"))
                                        .accessibilityValue(Text(taskStatus))
                                }

                                if taskRecords.contains(where: { $0.value.isFinite && $0.value >= 0 }) {
                                    PingChart(
                                        records: taskRecords,
                                        animationEnabled: store.settings.chartAnimationEnabled
                                    )
                                        .frame(height: 100)
                                } else {
                                    Text("No successful ping samples")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .frame(maxWidth: .infinity, alignment: .center)
                                        .padding(.vertical, 8)
                                }
                            }
                            .padding(.vertical, 4)
                        }
                    } else if store.nodeDetail.pingHours == 24,
                              store.nodeDetail.ping24HourError != nil {
                        Label("Latency data unavailable", systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .center)
                            .padding()
                    } else {
                        Text("No ping tasks")
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .center)
                            .padding()
                    }
                }
            } else if let error = store.nodeDetail.error {
                EmptyStateView(title: "Load Failed", systemImage: "exclamationmark.triangle", message: error)
            }
        }
        .navigationTitle(store.nodeDetail.node?.name ?? "Node Detail")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                if let server = store.activeServer, let node = store.nodeDetail.node, node.isOnline, server.authType != .guest {
                    NavigationLink {
                        TerminalNavigationWrapper(uuid: node.uuid, server: server)
                    } label: {
                        Image(systemName: "terminal")
                    }
                }
            }
        }
        .refreshable {
            await store.loadNodeDetail(uuid: nodeId)
        }
        .task(id: detailRefreshTaskID) {
            await store.loadNodeDetail(uuid: nodeId)
            await runDetailRefreshLoop()
        }
        .task(id: pingRefreshTaskID) {
            await runPingRefreshLoop()
        }
    }

    private var detailRefreshTaskID: String {
        "\(nodeId)-\(store.nodeDetail.loadHours)"
    }

    private var pingRefreshTaskID: String {
        "\(nodeId)-\(store.activeServerId?.uuidString ?? "none")-ping"
    }

    private func displayedAddress(_ address: String) -> String {
        store.settings.maskIpEnabled ? Formatters.maskIPAddress(address) : address
    }

    private func localized(_ key: String) -> String {
        AppLocalization.string(key, language: store.settings.language)
    }

    private func trafficLimitAccountingLabelKey(_ type: String) -> String {
        switch trafficLimitAccountingMode(type: type) {
        case .total: return "Total (Upload + Download)"
        case .maximumDirection: return "Larger Direction"
        case .minimumDirection: return "Smaller Direction"
        case .upload: return "Upload Only"
        case .download: return "Download Only"
        }
    }

    private func pingTaskStatus(task: PingTask, records: [PingRecord]) -> String {
        let metrics = resolvePingLatencyMetrics(
            reportedSampleCount: task.sampleCount,
            reportedLatestMilliseconds: task.latest,
            reportedAverageMilliseconds: task.avg,
            reportedPacketLossPercent: task.loss,
            recordValues: records.map(\.value)
        )
        let latencyText = metrics.latestMilliseconds.map {
            "\(Formatters.number($0)) ms"
        } ?? localized("No latency sample")
        let lossText = metrics.packetLossPercent.map { Formatters.percent($0) } ?? "—"
        return "\(latencyText) / \(lossText)"
    }

    private func runDetailRefreshLoop() async {
        while !Task.isCancelled {
            let realtime = store.nodeDetail.loadHours == 0
            let seconds = realtime ? max(store.settings.refreshIntervalSeconds, 1) : max(store.settings.refreshIntervalSeconds, 30)
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await store.refreshNodeDetailRecords()
        }
    }

    private func runPingRefreshLoop() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 30_000_000_000)
            guard !Task.isCancelled else { return }
            await store.refreshNodeDetailPingRecordsIfDue()
        }
    }
}

private struct QuotaUsageMeter: View {
    let used: Int64
    let limit: Int64
    let fraction: Double
    let accountingMode: String

    private var percent: Double {
        fraction.isFinite ? max(fraction * 100, 0) : 0
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text("Usage / Limit")
                    .font(.subheadline.weight(.semibold))
                Spacer(minLength: 8)
                Text(accountingMode)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.trailing)
            }
            Text("\(Formatters.bytes(used)) / \(Formatters.bytes(limit))")
                .font(.body.monospacedDigit())
                .frame(maxWidth: .infinity, alignment: .trailing)
            ProgressView(value: min(max(fraction.isFinite ? fraction : 0, 0), 1))
                .tint(fraction > 1 ? .red : .blue)
            Text(Formatters.percent(percent, digits: 2))
                .font(.caption.monospacedDigit())
                .foregroundStyle(fraction > 1 ? .red : .secondary)
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Usage / Limit"))
        .accessibilityValue(
            Text("\(Formatters.bytes(used)) / \(Formatters.bytes(limit)), \(Formatters.percent(percent, digits: 2)), \(accountingMode)")
        )
    }
}

private struct Latency24HourSummary: View {
    let tasks: [PingTask]
    let records: [PingRecord]
    let error: String?
    let isLoading: Bool
    let animationEnabled: Bool
    let language: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let error, !error.isEmpty {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Latency data unavailable")
                            .font(.subheadline.weight(.semibold))
                        Text(error)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                }
                .accessibilityElement(children: .combine)
            }

            if isLoading && tasks.isEmpty {
                ProgressView("Loading 24-hour latency")
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 12)
            } else if tasks.isEmpty && error == nil {
                Label("No ping tasks assigned to this node", systemImage: "waveform.path.ecg")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 12)
            } else {
                let recordsByTaskID = Dictionary(grouping: records, by: \.taskId)
                ForEach(tasks) { task in
                    LatencyTaskSummaryCard(
                        task: task,
                        records: recordsByTaskID[task.id] ?? [],
                        animationEnabled: animationEnabled,
                        language: language
                    )
                }
            }
        }
    }
}

private struct LatencyTaskSummaryCard: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    let task: PingTask
    let records: [PingRecord]
    let animationEnabled: Bool
    let language: String
    private let metrics: ResolvedPingLatencyMetrics

    init(
        task: PingTask,
        records: [PingRecord],
        animationEnabled: Bool,
        language: String
    ) {
        self.task = task
        self.records = records
        self.animationEnabled = animationEnabled
        self.language = language
        self.metrics = resolvePingLatencyMetrics(
            reportedSampleCount: task.sampleCount,
            reportedLatestMilliseconds: task.latest,
            reportedAverageMilliseconds: task.avg,
            reportedPacketLossPercent: task.loss,
            recordValues: records.map(\.value)
        )
    }

    private var hasSuccessfulReportedSamples: Bool {
        metrics.hasReportedSuccessfulSamples
    }

    private var sampleCount: Int {
        metrics.sampleCount
    }

    private var averageLatency: Double? {
        metrics.averageMilliseconds
    }

    private var latestLatency: Double? {
        metrics.latestMilliseconds
    }

    private var packetLoss: Double? {
        metrics.packetLossPercent
    }

    private var fluctuation: Double? {
        guard hasSuccessfulReportedSamples,
              let ratio = task.p99P50Ratio,
              ratio.isFinite,
              ratio >= 0 else { return nil }
        return ratio
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    taskTitle
                    Spacer(minLength: 8)
                    sampleCountLabel
                }
                VStack(alignment: .leading, spacing: 3) {
                    taskTitle
                    sampleCountLabel
                }
            }

            if dynamicTypeSize.isAccessibilitySize {
                metricColumn
            } else {
                ViewThatFits(in: .horizontal) {
                    metricRow
                    metricColumn
                }
            }

            if records.isEmpty {
                Text("No ping samples in the last 24 hours")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 8)
            } else {
                LatencyHistoryStrip(
                    records: records,
                    animationEnabled: animationEnabled,
                    language: language
                )
            }

            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 112), alignment: .leading)],
                alignment: .leading,
                spacing: 6
            ) {
                if let latestLatency {
                    pingStatistic("Latest", "\(Formatters.number(latestLatency, digits: 1)) ms")
                }
                if hasSuccessfulReportedSamples,
                   let p50 = task.p50,
                   p50.isFinite,
                   p50 >= 0 {
                    pingStatistic("P50", "\(Formatters.number(p50, digits: 1)) ms")
                }
                if hasSuccessfulReportedSamples,
                   let p99 = task.p99,
                   p99.isFinite,
                   p99 >= 0 {
                    pingStatistic("P99", "\(Formatters.number(p99, digits: 1)) ms")
                }
                if let fluctuation {
                    pingStatistic("Fluctuation", Formatters.number(fluctuation, digits: 2))
                }
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(.secondary)
            .lineLimit(nil)
        }
        .padding(12)
        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .contain)
    }

    private var taskTitle: some View {
        Text(task.name)
            .font(.headline)
            .lineLimit(2)
    }

    private var sampleCountLabel: some View {
        HStack(spacing: 3) {
            Text("\(sampleCount)")
            Text("Samples")
        }
        .font(.caption.monospacedDigit())
        .foregroundStyle(.secondary)
    }

    private var metricRow: some View {
        HStack(spacing: 10) {
            averageLatencyTile
            packetLossTile
        }
    }

    private var metricColumn: some View {
        VStack(spacing: 10) {
            averageLatencyTile
            packetLossTile
        }
    }

    private var averageLatencyTile: some View {
        LatencyMetricTile(
            title: "Average Latency",
            value: averageLatency.map { "\(Formatters.number($0, digits: 1)) ms" } ?? "—",
            systemImage: "timer",
            color: .orange
        )
    }

    private var packetLossTile: some View {
        LatencyMetricTile(
            title: "Packet Loss",
            value: packetLoss.map { Formatters.percent($0) } ?? "—",
            systemImage: "exclamationmark.arrow.triangle.2.circlepath",
            color: .red
        )
    }

    private func pingStatistic(_ title: String, _ value: String) -> Text {
        Text(verbatim: "\(AppLocalization.string(title, language: language)) \(value)")
    }
}

private struct LatencyMetricTile: View {
    let title: LocalizedStringKey
    let value: String
    let systemImage: String
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(title, systemImage: systemImage)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.title3.weight(.semibold).monospacedDigit())
                .foregroundStyle(color)
                .lineLimit(1)
                .minimumScaleFactor(0.72)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(color.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(title))
        .accessibilityValue(Text(value))
    }
}

private struct LatencyHistoryStrip: View {
    let records: [PingRecord]
    let animationEnabled: Bool
    let language: String

    private var recentRecords: [PingRecord] {
        Array(records.suffix(32))
    }

    private var maximumLatency: Double {
        let maximum = recentRecords
            .map(\.value)
            .filter { $0.isFinite && $0 >= 0 }
            .max() ?? 1
        return max(maximum, 1)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Recent Samples")
                Spacer()
                Label("Packet Lost", systemImage: "xmark.circle.fill")
                    .foregroundStyle(.red)
            }
            .font(.caption)
            .foregroundStyle(.secondary)

            HStack(alignment: .bottom, spacing: 3) {
                ForEach(recentRecords) { record in
                    let isLost = !record.value.isFinite || record.value < 0
                    Capsule()
                        .fill(isLost ? Color.red : Color.orange)
                        .frame(maxWidth: .infinity)
                        .frame(
                            height: CGFloat(
                                isLost
                                    ? 32
                                    : max(7, 7 + 25 * min(record.value / maximumLatency, 1))
                            )
                        )
                }
            }
            .frame(height: 34, alignment: .bottom)
            .accessibilityHidden(true)
        }
        .chartAnimation(animationEnabled, value: recentRecords)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Recent Samples"))
        .accessibilityValue(Text(historyAccessibilityValue))
    }

    private var historyAccessibilityValue: String {
        let summary = summarizePingLatency(values: recentRecords.map(\.value))
        let loss = summary.packetLossPercent.map { Formatters.percent($0) } ?? "—"
        return "\(summary.sampleCount) \(AppLocalization.string("Samples", language: language)), \(summary.lostSampleCount) \(AppLocalization.string("Lost", language: language)), \(loss)"
    }
}

private struct LoadPeakGrid: View {
    let records: [LoadRecord]

    var body: some View {
        let peaks = LoadPeaks(records: records)
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 8)], spacing: 8) {
            PeakTile(title: "CPU Peak", value: "\(Formatters.number(peaks.cpu, digits: 1))%")
            PeakTile(title: "RAM Peak", value: "\(Formatters.number(peaks.ram, digits: 1))%")
            PeakTile(title: "Disk Peak", value: "\(Formatters.number(peaks.disk, digits: 1))%")
            PeakTile(title: "Down Peak", value: Formatters.rate(Int64(peaks.netIn)))
            PeakTile(title: "Up Peak", value: Formatters.rate(Int64(peaks.netOut)))
            PeakTile(title: "TCP Peak", value: "\(peaks.tcp)")
            PeakTile(title: "UDP Peak", value: "\(peaks.udp)")
            PeakTile(title: "Process Peak", value: "\(peaks.process)")
        }
        .padding(.vertical, 4)
    }
}

private struct PeakTile: View {
    let title: String
    let value: String
    
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.subheadline.bold())
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 8))
    }
}

private struct PercentLoadChart: View {
    let records: [LoadRecord]
    let animationEnabled: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            LoadChartHeader(
                title: "Usage Percent",
                systemImage: "gauge.medium",
                items: [
                    LoadChartLegendItem(title: "CPU", systemImage: "cpu", color: .blue),
                    LoadChartLegendItem(title: "RAM", systemImage: "memorychip", color: .green),
                    LoadChartLegendItem(title: "Disk", systemImage: "internaldrive", color: .purple)
                ]
            )

            Chart {
                ForEach(records) { record in
                    if let timestamp = parseISO8601(record.time) {
                        LineMark(
                            x: .value("Time", timestamp),
                            y: .value("CPU", record.cpu),
                            series: .value("Metric", "CPU")
                        )
                        .foregroundStyle(.blue)

                        LineMark(
                            x: .value("Time", timestamp),
                            y: .value("RAM", record.ramPercent),
                            series: .value("Metric", "RAM")
                        )
                        .foregroundStyle(.green)

                        LineMark(
                            x: .value("Time", timestamp),
                            y: .value("Disk", record.diskPercent),
                            series: .value("Metric", "Disk")
                        )
                        .foregroundStyle(.purple)
                    }
                }
            }
            .frame(height: 170)
            .chartLegend(.hidden)
            .chartYScale(domain: 0...100)
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 3)) { value in
                    AxisGridLine()
                    AxisTick()
                    AxisValueLabel {
                        if let date = value.as(Date.self) {
                            Text(formatLoadAxisDate(date))
                        }
                    }
                }
            }
            .chartYAxis {
                AxisMarks(position: .leading) { value in
                    AxisGridLine()
                    AxisTick()
                    AxisValueLabel {
                        if let percent = value.as(Double.self) {
                            Text("\(Int(percent))%")
                        }
                    }
                }
            }
            .chartAnimation(animationEnabled, value: records)
        }
        .padding(.vertical, 8)
    }
}

private struct NetworkLoadChart: View {
    let records: [LoadRecord]
    let animationEnabled: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            LoadChartHeader(
                title: "Network Rate",
                systemImage: "arrow.down.arrow.up",
                items: [
                    LoadChartLegendItem(title: "Download", systemImage: "arrow.down", color: .blue),
                    LoadChartLegendItem(title: "Upload", systemImage: "arrow.up", color: .orange)
                ]
            )

            Chart {
                ForEach(records) { record in
                    if let timestamp = parseISO8601(record.time) {
                        LineMark(
                            x: .value("Time", timestamp),
                            y: .value("Download", Double(record.netIn)),
                            series: .value("Metric", "Download")
                        )
                        .foregroundStyle(.blue)

                        LineMark(
                            x: .value("Time", timestamp),
                            y: .value("Upload", Double(record.netOut)),
                            series: .value("Metric", "Upload")
                        )
                        .foregroundStyle(.orange)
                    }
                }
            }
            .frame(height: 155)
            .chartLegend(.hidden)
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 3)) { value in
                    AxisGridLine()
                    AxisTick()
                    AxisValueLabel {
                        if let date = value.as(Date.self) {
                            Text(formatLoadAxisDate(date))
                        }
                    }
                }
            }
            .chartYAxis {
                AxisMarks(position: .leading) { value in
                    AxisGridLine()
                    AxisTick()
                    AxisValueLabel {
                        if let rate = value.as(Double.self) {
                            Text(Formatters.rate(Int64(rate)))
                        }
                    }
                }
            }
            .chartAnimation(animationEnabled, value: records)
        }
        .padding(.vertical, 8)
    }
}

private struct UsagePerSampleChart: View {
    let records: [LoadRecord]
    let animationEnabled: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            LoadChartHeader(
                title: "Usage per Sample",
                systemImage: "chart.bar.xaxis",
                items: [
                    LoadChartLegendItem(title: "Download", systemImage: "arrow.down", color: .blue),
                    LoadChartLegendItem(title: "Upload", systemImage: "arrow.up", color: .orange)
                ]
            )

            Chart {
                ForEach(records) { record in
                    if let timestamp = parseISO8601(record.time) {
                        LineMark(
                            x: .value("Time", timestamp),
                            y: .value("Download Usage", Double(record.trafficDown)),
                            series: .value("Metric", "Download Usage")
                        )
                        .foregroundStyle(.blue)

                        LineMark(
                            x: .value("Time", timestamp),
                            y: .value("Upload Usage", Double(record.trafficUp)),
                            series: .value("Metric", "Upload Usage")
                        )
                        .foregroundStyle(.orange)
                    }
                }
            }
            .frame(height: 155)
            .chartLegend(.hidden)
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 3)) { value in
                    AxisGridLine()
                    AxisTick()
                    AxisValueLabel {
                        if let date = value.as(Date.self) {
                            Text(formatLoadAxisDate(date))
                        }
                    }
                }
            }
            .chartYAxis {
                AxisMarks(position: .leading) { value in
                    AxisGridLine()
                    AxisTick()
                    AxisValueLabel {
                        if let bytes = value.as(Double.self) {
                            Text(Formatters.bytes(Int64(bytes)))
                        }
                    }
                }
            }
            .chartAnimation(animationEnabled, value: records)
        }
        .padding(.vertical, 8)
    }
}

private struct CountLoadChart: View {
    let records: [LoadRecord]
    let animationEnabled: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            LoadChartHeader(
                title: "Connections & Processes",
                systemImage: "number",
                items: [
                    LoadChartLegendItem(title: "TCP", systemImage: "network", color: .blue),
                    LoadChartLegendItem(title: "UDP", systemImage: "point.3.connected.trianglepath.dotted", color: .cyan),
                    LoadChartLegendItem(title: "Process", systemImage: "gearshape.2", color: .red)
                ]
            )

            Chart {
                ForEach(records) { record in
                    if let timestamp = parseISO8601(record.time) {
                        LineMark(
                            x: .value("Time", timestamp),
                            y: .value("TCP", Double(record.connections)),
                            series: .value("Metric", "TCP")
                        )
                        .foregroundStyle(.blue)

                        LineMark(
                            x: .value("Time", timestamp),
                            y: .value("UDP", Double(record.connectionsUdp)),
                            series: .value("Metric", "UDP")
                        )
                        .foregroundStyle(.cyan)

                        LineMark(
                            x: .value("Time", timestamp),
                            y: .value("Process", Double(record.process)),
                            series: .value("Metric", "Process")
                        )
                        .foregroundStyle(.red)
                    }
                }
            }
            .frame(height: 155)
            .chartLegend(.hidden)
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 3)) { value in
                    AxisGridLine()
                    AxisTick()
                    AxisValueLabel {
                        if let date = value.as(Date.self) {
                            Text(formatLoadAxisDate(date))
                        }
                    }
                }
            }
            .chartYAxis {
                AxisMarks(position: .leading) { value in
                    AxisGridLine()
                    AxisTick()
                    AxisValueLabel {
                        if let count = value.as(Double.self) {
                            Text("\(Int(count))")
                        }
                    }
                }
            }
            .chartAnimation(animationEnabled, value: records)
        }
        .padding(.vertical, 8)
    }
}

private struct LoadChartLegendItem {
    let title: String
    let systemImage: String
    let color: Color
}

private struct LoadChartHeader: View {
    let title: String
    let systemImage: String
    let items: [LoadChartLegendItem]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label {
                Text(LocalizedStringKey(title))
                    .font(.subheadline.bold())
            } icon: {
                Image(systemName: systemImage)
                    .foregroundStyle(.blue)
            }

            LazyVGrid(columns: [GridItem(.adaptive(minimum: 92), spacing: 8)], alignment: .leading, spacing: 6) {
                ForEach(items, id: \.title) { item in
                    Label {
                        Text(LocalizedStringKey(item.title))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    } icon: {
                        Image(systemName: item.systemImage)
                            .foregroundStyle(item.color)
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
            }
        }
    }
}

private struct LoadPeaks {
    let cpu: Double
    let ram: Double
    let disk: Double
    let netIn: Double
    let netOut: Double
    let tcp: Int
    let udp: Int
    let process: Int

    init(records: [LoadRecord]) {
        cpu = records.map(\.cpu).max() ?? 0
        ram = records.map(\.ramPercent).max() ?? 0
        disk = records.map(\.diskPercent).max() ?? 0
        netIn = Double(records.map(\.netIn).max() ?? 0)
        netOut = Double(records.map(\.netOut).max() ?? 0)
        tcp = records.map(\.connections).max() ?? 0
        udp = records.map(\.connectionsUdp).max() ?? 0
        process = records.map(\.process).max() ?? 0
    }
}

private struct PingChart: View {
    let records: [PingRecord]
    let animationEnabled: Bool

    private struct LatencyPoint: Identifiable {
        let record: PingRecord
        let segmentID: Int
        var id: UUID { record.id }
    }

    private var latencyPoints: [LatencyPoint] {
        let segmentIDs = pingLatencySegmentIdentifiers(values: records.map(\.value))
        return zip(records, segmentIDs).compactMap { record, segmentID in
            guard let segmentID else { return nil }
            return LatencyPoint(record: record, segmentID: segmentID)
        }
    }

    private var lostRecords: [PingRecord] {
        records.filter { !$0.value.isFinite || $0.value < 0 }
    }
    
    var body: some View {
        Chart {
            ForEach(latencyPoints) { point in
                if let timestamp = parseISO8601(point.record.time) {
                    LineMark(
                        x: .value("Time", timestamp),
                        y: .value("Latency", point.record.value),
                        series: .value("Latency Segment", point.segmentID)
                    )
                    .foregroundStyle(.orange)

                    PointMark(
                        x: .value("Time", timestamp),
                        y: .value("Latency", point.record.value)
                    )
                    .foregroundStyle(.orange.opacity(0.65))
                    .symbolSize(12)
                }
            }
            ForEach(lostRecords) { record in
                if let timestamp = parseISO8601(record.time) {
                    PointMark(
                        x: .value("Time", timestamp),
                        y: .value("Packet Lost", 0.0)
                    )
                    .foregroundStyle(.red)
                    .symbolSize(26)
                }
            }
        }
        .chartXAxis(.hidden)
        .chartYAxis {
            AxisMarks(position: .leading) { value in
                AxisGridLine()
                AxisTick()
                AxisValueLabel {
                    if let latency = value.as(Double.self) {
                        Text("\(Formatters.number(latency, digits: 0)) ms")
                    }
                }
            }
        }
        .chartAnimation(animationEnabled, value: records)
        .accessibilityLabel(Text("Ping latency history"))
    }
}

private func formatLoadAxisDate(_ date: Date) -> String {
    ChartDateFormatters.axis.string(from: date)
}

private func parseISO8601(_ string: String) -> Date? {
    ChartDateFormatters.isoFractional.date(from: string)
        ?? ChartDateFormatters.iso.date(from: string)
}

private enum ChartDateFormatters {
    static let isoFractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    static let iso: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    static let axis: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd HH:mm:ss"
        return formatter
    }()
}

private extension View {
    @ViewBuilder
    func chartAnimation<Value: Equatable>(_ enabled: Bool, value: Value) -> some View {
        if enabled {
            self.animation(.default, value: value)
        } else {
            self.transaction { transaction in
                transaction.animation = nil
            }
        }
    }
}


private struct NodeHeaderView: View {
    let node: KomariNode

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(node.name)
                    .font(.title3.bold())
                Spacer()
                StatusBadge(isOnline: node.isOnline)
            }
            Text([node.region, node.group, node.os].filter { !$0.isEmpty }.joined(separator: " / "))
                .foregroundStyle(.secondary)
            HStack {
                Label(Formatters.rate(node.netIn), systemImage: "arrow.down")
                Spacer()
                Label(Formatters.rate(node.netOut), systemImage: "arrow.up")
            }
            .font(.caption)
        }
    }
}
