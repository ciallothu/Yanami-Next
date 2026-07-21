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
                    
                    if let usage = node.trafficLimitUsage {
                        ResourceMeter(
                            title: localized("Traffic Limit"),
                            value: usage.fraction,
                            label: "\(Formatters.bytes(usage.used)) / \(Formatters.bytes(node.trafficLimit)) (\(Formatters.percent(usage.fraction * 100, digits: 0)))"
                        )
                    }
                }

                Section("Network") {
                    DetailLine(
                        localized("Network Speed"),
                        "↑ \(Formatters.rate(node.netOut))  ↓ \(Formatters.rate(node.netIn))"
                    )
                    DetailLine(
                        localized("Latest Traffic"),
                        "↑ \(Formatters.bytes(node.trafficUp))  ↓ \(Formatters.bytes(node.trafficDown))"
                    )
                    DetailLine(
                        localized("Traffic Usage"),
                        "↑ \(Formatters.bytes(node.netTotalUp))  ↓ \(Formatters.bytes(node.netTotalDown))"
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
                        TrafficLoadChart(
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
                        ForEach(store.nodeDetail.pingTasks) { task in
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(task.name)
                                        .font(.subheadline.bold())
                                    Spacer()
                                    Text("\(Formatters.number(task.latest))ms / \(Formatters.percent(task.loss))")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                
                                let taskRecords = store.nodeDetail.pingRecords.filter { $0.taskId == task.id }
                                if !taskRecords.isEmpty {
                                    PingChart(
                                        records: taskRecords,
                                        animationEnabled: store.settings.chartAnimationEnabled
                                    )
                                        .frame(height: 100)
                                }
                            }
                            .padding(.vertical, 4)
                        }
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
    }

    private var detailRefreshTaskID: String {
        "\(nodeId)-\(store.nodeDetail.loadHours)"
    }

    private func displayedAddress(_ address: String) -> String {
        store.settings.maskIpEnabled ? Formatters.maskIPAddress(address) : address
    }

    private func localized(_ key: String) -> String {
        AppLocalization.string(key, language: store.settings.language)
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

private struct TrafficLoadChart: View {
    let records: [LoadRecord]
    let animationEnabled: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            LoadChartHeader(
                title: "Traffic per Sample",
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
                            y: .value("Download Traffic", Double(record.trafficDown)),
                            series: .value("Metric", "Download Traffic")
                        )
                        .foregroundStyle(.blue)

                        LineMark(
                            x: .value("Time", timestamp),
                            y: .value("Upload Traffic", Double(record.trafficUp)),
                            series: .value("Metric", "Upload Traffic")
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
    
    var body: some View {
        Chart {
            ForEach(records) { record in
                if let timestamp = parseISO8601(record.time) {
                    LineMark(
                        x: .value("Time", timestamp),
                        y: .value("Latency", record.value)
                    )
                    .foregroundStyle(.orange)
                }
            }
        }
        .chartXAxis(.hidden)
        .chartYAxis {
            AxisMarks(position: .leading)
        }
        .chartAnimation(animationEnabled, value: records)
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
