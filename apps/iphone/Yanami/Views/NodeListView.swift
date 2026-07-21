import SwiftUI

struct NodeListView: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        NavigationStack {
            List {
                if store.activeServer == nil {
                    EmptyStateView(title: "No Active Server", systemImage: "server.rack", message: "Select or add a Komari instance first.")
                } else {
                    Section {
                        NodeSummaryView()
                    }

                    if !store.groups.isEmpty {
                        Section("Groups") {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack {
                                    FilterChip(title: "All", isSelected: store.selectedGroup == nil) {
                                        store.selectedGroup = nil
                                    }
                                    ForEach(store.groups, id: \.self) { group in
                                        FilterChip(title: group, isSelected: store.selectedGroup == group) {
                                            store.selectedGroup = group
                                        }
                                    }
                                }
                                .padding(.vertical, 4)
                            }
                        }
                    }

                    Section("Nodes") {
                        if store.isLoadingNodes {
                            ProgressView("Loading nodes")
                        } else if store.filteredNodes.isEmpty {
                            EmptyStateView(title: "No Nodes", systemImage: "desktopcomputer", message: "No nodes match the current filters.")
                        } else {
                            ForEach(store.filteredNodes) { node in
                                NavigationLink {
                                    NodeDetailView(nodeId: node.uuid)
                                } label: {
                                    NodeRowView(node: node)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle(store.activeServer?.name ?? "Nodes")
            .searchable(text: $store.searchQuery, prompt: nodeSearchPrompt)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    HStack {
                        if let server = store.activeServer, server.authType != .guest {
                            NavigationLink {
                                AdminNavigationWrapper(server: server)
                            } label: {
                                Image(systemName: "server.rack")
                            }
                        }
                        
                        Picker("Status", selection: $store.statusFilter) {
                            ForEach(StatusFilter.allCases) { filter in
                                Text(filter.title).tag(filter)
                            }
                        }
                        .pickerStyle(.segmented)
                        .frame(width: 180)
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        Task { await store.loadNodes(mode: .refresh) }
                    } label: {
                        if store.isRefreshingNodes {
                            ProgressView()
                        } else {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                }
            }
            .refreshable {
                await store.loadNodes(mode: .refresh)
            }
            .task {
                if store.activeServer != nil && store.nodes.isEmpty {
                    await store.loadNodes(mode: .initial)
                }
            }
        }
    }

    private var nodeSearchPrompt: Text {
        if store.settings.maskIpEnabled {
            Text("Search node, UUID, group")
        } else {
            Text("Search node, IP, UUID, group")
        }
    }
}

private struct NodeSummaryView: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                MetricView(title: "Total", value: "\(store.nodes.count)")
                MetricView(title: "Online", value: "\(store.onlineCount)")
                MetricView(title: "Offline", value: "\(store.offlineCount)")
            }

            LazyVGrid(
                columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3),
                alignment: .leading,
                spacing: 10
            ) {
                SummaryMetricView(
                    title: "Realtime Speed",
                    value: Formatters.rate(
                        saturatingNonNegativeSum([store.totalNetIn, store.totalNetOut])
                    ),
                    systemImage: "arrow.up.arrow.down"
                )
                SummaryMetricView(
                    title: "Upload Speed",
                    value: Formatters.rate(store.totalNetOut),
                    systemImage: "arrow.up"
                )
                SummaryMetricView(
                    title: "Download Speed",
                    value: Formatters.rate(store.totalNetIn),
                    systemImage: "arrow.down"
                )
                SummaryMetricView(
                    title: "Latest Traffic",
                    value: Formatters.bytes(
                        saturatingNonNegativeSum([store.totalTrafficUp, store.totalTrafficDown])
                    ),
                    systemImage: "chart.bar"
                )
                SummaryMetricView(
                    title: "Upload Traffic",
                    value: Formatters.bytes(store.totalTrafficUp),
                    systemImage: "arrow.up.circle"
                )
                SummaryMetricView(
                    title: "Download Traffic",
                    value: Formatters.bytes(store.totalTrafficDown),
                    systemImage: "arrow.down.circle"
                )
                SummaryMetricView(
                    title: "Total Usage",
                    value: Formatters.bytes(
                        saturatingNonNegativeSum([store.totalUsageUp, store.totalUsageDown])
                    ),
                    systemImage: "externaldrive"
                )
                SummaryMetricView(
                    title: "Upload Usage",
                    value: Formatters.bytes(store.totalUsageUp),
                    systemImage: "arrow.up.to.line"
                )
                SummaryMetricView(
                    title: "Download Usage",
                    value: Formatters.bytes(store.totalUsageDown),
                    systemImage: "arrow.down.to.line"
                )
            }

            if !store.statusMessage.isEmpty {
                Text(store.statusMessage)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

private struct SummaryMetricView: View {
    let title: LocalizedStringKey
    let value: String
    let systemImage: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label {
                Text(title)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            } icon: {
                Image(systemName: systemImage)
                    .imageScale(.small)
            }
            .font(.caption2)
            .foregroundStyle(.secondary)

            Text(value)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .monospacedDigit()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct NodeRowView: View {
    @EnvironmentObject private var store: AppStore
    let node: KomariNode

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Header: Region, Name, Badges
            HStack(spacing: 8) {
                Text(node.region.isEmpty ? "🌐" : node.region)
                    .font(.subheadline)
                
                Text(node.name)
                    .font(.subheadline.bold())
                    .lineLimit(1)
                
                Spacer()
                
                if node.isOnline {
                    UptimeBadge(uptime: node.uptime)
                }
                
                if let expiredAt = node.expiredAt, !expiredAt.isEmpty {
                    ExpiryBadge(dateString: expiredAt)
                }
                
                StatusBadge(isOnline: node.isOnline)
            }

            if !node.ipv4.isEmpty || !node.ipv6.isEmpty {
                VStack(alignment: .leading, spacing: 3) {
                    if !node.ipv4.isEmpty {
                        NodeAddressLine(
                            label: "IPv4",
                            value: displayedAddress(node.ipv4)
                        )
                    }
                    if !node.ipv6.isEmpty {
                        NodeAddressLine(
                            label: "IPv6",
                            value: displayedAddress(node.ipv6)
                        )
                    }
                }
            }

            if node.isOnline {
                // Circular Indicators
                HStack(alignment: .top) {
                    CircularUsageIndicator(
                        label: "CPU",
                        percent: node.cpuUsage,
                        detail: node.cpuCores > 0 ? "\(node.cpuCores) Core" : ""
                    )
                    Spacer()
                    CircularUsageIndicator(
                        label: "RAM",
                        percent: node.memTotal > 0 ? Double(node.memUsed) / Double(node.memTotal) * 100 : 0,
                        detail: Formatters.bytes(node.memTotal)
                    )
                    Spacer()
                    CircularUsageIndicator(
                        label: "DISK",
                        percent: node.diskTotal > 0 ? Double(node.diskUsed) / Double(node.diskTotal) * 100 : 0,
                        detail: Formatters.bytes(node.diskTotal)
                    )
                    
                    if let usage = node.trafficLimitUsage {
                        Spacer()
                        VStack(spacing: 4) {
                            ZStack {
                                Circle()
                                    .stroke(Color.gray.opacity(0.2), lineWidth: 5)
                                Circle()
                                    .trim(from: 0, to: CGFloat(min(max(usage.fraction, 0), 1)))
                                    .stroke(Color.purple, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                                    .rotationEffect(.degrees(-90))
                                
                                Text(Formatters.percent(usage.fraction * 100, digits: 0))
                                    .font(.system(size: 10, weight: .bold))
                            }
                            .frame(width: 44, height: 44)
                            Text("USAGE")
                                .font(.system(size: 8, weight: .medium))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .padding(.vertical, 4)

                VStack(spacing: 5) {
                    NodeTransferLine(
                        title: "Network Speed",
                        upload: Formatters.rate(node.netOut),
                        download: Formatters.rate(node.netIn)
                    )
                    NodeTransferLine(
                        title: "Latest Traffic",
                        upload: Formatters.bytes(node.trafficUp),
                        download: Formatters.bytes(node.trafficDown)
                    )
                    NodeTransferLine(
                        title: "Traffic Usage",
                        upload: Formatters.bytes(node.netTotalUp),
                        download: Formatters.bytes(node.netTotalDown)
                    )
                }
            }
        }
        .padding(.vertical, 8)
    }

    private func displayedAddress(_ address: String) -> String {
        store.settings.maskIpEnabled ? Formatters.maskIPAddress(address) : address
    }
}

private struct NodeAddressLine: View {
    let label: LocalizedStringKey
    let value: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(label)
                .foregroundStyle(.secondary)
            Text(value)
                .monospaced()
                .lineLimit(1)
                .minimumScaleFactor(0.65)
        }
        .font(.caption2)
    }
}

private struct NodeTransferLine: View {
    let title: LocalizedStringKey
    let upload: String
    let download: String

    var body: some View {
        HStack(spacing: 6) {
            Text(title)
                .foregroundStyle(.tertiary)
                .frame(minWidth: 82, alignment: .leading)
            Label(upload, systemImage: "arrow.up")
            Spacer(minLength: 4)
            Label(download, systemImage: "arrow.down")
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .monospacedDigit()
    }
}

private struct AdminNavigationWrapper: View {
    let server: ServerProfile
    @EnvironmentObject private var appStore: AppStore
    @State private var token: String?
    @State private var error: String?
    @State private var isResolvingToken = false
    @State private var didResolveInitialToken = false

    var body: some View {
        Group {
            if !appStore.isAuthenticationSnapshotCurrent(server) {
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle")
                        .foregroundColor(.red)
                    Text("Server configuration changed. Reopen the admin panel.")
                        .multilineTextAlignment(.center)
                }
                .padding()
            } else if isResolvingToken {
                ProgressView("Preparing admin panel...")
            } else if let token = token {
                AdminView(
                    store: AdminStore(
                        server: server,
                        token: token,
                        refreshPasswordSession: {
                            try await appStore.authenticationToken(
                                for: server,
                                forcePasswordLogin: true
                            )
                        }
                    )
                )
            } else if let error = error {
                VStack {
                    Image(systemName: "exclamationmark.triangle")
                        .foregroundColor(.red)
                    Text(error)
                        .padding()
                    Button("Retry") {
                        Task { await resolveToken(forcePasswordLogin: true) }
                    }
                }
            } else {
                ProgressView("Preparing admin panel...")
            }
        }
        .task {
            guard !didResolveInitialToken else { return }
            didResolveInitialToken = true
            await resolveToken()
        }
    }

    private func resolveToken(forcePasswordLogin: Bool = false) async {
        guard !isResolvingToken else { return }
        isResolvingToken = true
        defer { isResolvingToken = false }
        error = nil
        do {
            if server.authType == .guest {
                error = "Guest mode not supported"
            } else {
                token = try await appStore.authenticationToken(
                    for: server,
                    forcePasswordLogin: forcePasswordLogin
                )
            }
        } catch {
            token = nil
            self.error = error.localizedDescription
        }
    }
}
