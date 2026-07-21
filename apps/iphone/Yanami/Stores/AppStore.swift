import Foundation

@MainActor
final class AppStore: ObservableObject {
    @Published var servers: [ServerProfile] = []
    @Published var activeServerId: UUID?
    @Published var settings = AppSettings()

    @Published var nodes: [KomariNode] = []
    @Published var searchQuery = ""
    @Published var selectedGroup: String?
    @Published var statusFilter = StatusFilter.all
    @Published var statusMessage = ""
    @Published var isLoadingNodes = false
    @Published var isRefreshingNodes = false
    @Published var selectedNodeId: String?
    @Published var nodeDetail = NodeDetailState()

    private let profileStore = ProfileStore()
    private var refreshTask: Task<Void, Never>?

    var activeServer: ServerProfile? {
        guard let activeServerId else { return nil }
        return servers.first { $0.id == activeServerId }
    }

    var groups: [String] {
        nodes.map(\.group).filter { !$0.isEmpty }.uniqueSorted()
    }

    var filteredNodes: [KomariNode] {
        nodes.filter { node in
            let matchesUnmaskedIp = !settings.maskIpEnabled && (
                node.ipv4.localizedCaseInsensitiveContains(searchQuery) ||
                node.ipv6.localizedCaseInsensitiveContains(searchQuery)
            )
            let matchesSearch =
                searchQuery.isEmpty ||
                node.name.localizedCaseInsensitiveContains(searchQuery) ||
                node.uuid.localizedCaseInsensitiveContains(searchQuery) ||
                node.region.localizedCaseInsensitiveContains(searchQuery) ||
                node.group.localizedCaseInsensitiveContains(searchQuery) ||
                matchesUnmaskedIp
            let matchesGroup = selectedGroup == nil || node.group == selectedGroup
            let matchesStatus: Bool
            switch statusFilter {
            case .all: matchesStatus = true
            case .online: matchesStatus = node.isOnline
            case .offline: matchesStatus = !node.isOnline
            }
            return matchesSearch && matchesGroup && matchesStatus
        }
    }

    var onlineCount: Int { nodes.filter(\.isOnline).count }
    var offlineCount: Int { nodes.count - onlineCount }
    var totalNetIn: Int64 { nodes.filter(\.isOnline).reduce(0) { $0 + $1.netIn } }
    var totalNetOut: Int64 { nodes.filter(\.isOnline).reduce(0) { $0 + $1.netOut } }
    var totalTrafficUp: Int64 { nodes.filter(\.isOnline).reduce(0) { $0 + $1.trafficUp } }
    var totalTrafficDown: Int64 { nodes.filter(\.isOnline).reduce(0) { $0 + $1.trafficDown } }
    var totalUsageUp: Int64 { nodes.filter(\.isOnline).reduce(0) { $0 + $1.netTotalUp } }
    var totalUsageDown: Int64 { nodes.filter(\.isOnline).reduce(0) { $0 + $1.netTotalDown } }

    init() {
        let persisted = profileStore.load()
        servers = persisted.servers
        activeServerId = persisted.activeServerId ?? persisted.servers.first?.id
        settings = persisted.settings
        startAutoRefresh()
    }

    deinit {
        refreshTask?.cancel()
    }

    func persist() {
        do {
            try profileStore.save(
                PersistedAppState(
                    servers: servers,
                    activeServerId: activeServerId,
                    settings: settings
                )
            )
        } catch {
            statusMessage = "Failed to save app data: \(error.localizedDescription)"
        }
    }

    func addServer(_ server: ServerProfile) {
        var normalized = server
        normalized.baseURL = normalized.normalizedBaseURL
        guard normalized.validatedBaseURL != nil else {
            statusMessage = "Server URL is invalid"
            return
        }
        normalized.customHeaders = normalized.sanitizedCustomHeaders
        servers.append(normalized)
        activeServerId = normalized.id
        persist()
    }

    func updateServer(_ server: ServerProfile) {
        var normalized = server
        normalized.baseURL = normalized.normalizedBaseURL
        guard normalized.validatedBaseURL != nil else {
            statusMessage = "Server URL is invalid"
            return
        }
        normalized.customHeaders = normalized.sanitizedCustomHeaders
        if let index = servers.firstIndex(where: { $0.id == normalized.id }) {
            servers[index] = normalized
        }
        if activeServerId == nil {
            activeServerId = normalized.id
        }
        persist()
    }

    func deleteServer(_ server: ServerProfile) {
        servers.removeAll { $0.id == server.id }
        if activeServerId == server.id {
            activeServerId = servers.first?.id
            nodes = []
            selectedNodeId = nil
            nodeDetail = NodeDetailState()
        }
        persist()
    }

    func selectServer(_ server: ServerProfile) {
        activeServerId = server.id
        nodes = []
        selectedNodeId = nil
        nodeDetail = NodeDetailState()
        persist()
        Task {
            await loadNodes(mode: .initial)
        }
    }

    func addCloudflareHeaders(to server: inout ServerProfile) {
        let existing = Set(server.customHeaders.map { $0.name.lowercased() })
        if !existing.contains("cf-access-client-id") {
            server.customHeaders.append(CustomHeader(name: "CF-Access-Client-Id", value: ""))
        }
        if !existing.contains("cf-access-client-secret") {
            server.customHeaders.append(CustomHeader(name: "CF-Access-Client-Secret", value: ""))
        }
    }

    func testConnection(_ server: ServerProfile) async throws -> String {
        try await performAuthenticatedRequest(for: server, useStoredProfile: false) { client, token in
            try await client.getVersion(token: token)
        }
    }

    func loadNodes(mode: NodeLoadMode = .refresh) async {
        guard let server = activeServer else {
            statusMessage = "Add or select a Komari instance first"
            return
        }
        if mode == .initial {
            isLoadingNodes = true
        } else {
            isRefreshingNodes = true
        }
        defer {
            isLoadingNodes = false
            isRefreshingNodes = false
        }

        do {
            let fetched = try await performAuthenticatedRequest(for: server) { client, token in
                try await client.getNodes(token: token)
            }
            let previousNodes = Dictionary(uniqueKeysWithValues: nodes.map { ($0.uuid, $0) })
            nodes = fetched.map { fetchedNode in
                guard let previous = previousNodes[fetchedNode.uuid] else { return fetchedNode }
                var reconciled = fetchedNode
                if shouldAcceptStatusSample(
                    current: previous,
                    incomingTime: fetchedNode.statusTime,
                    incomingUptime: fetchedNode.uptime,
                    incomingTotalUp: fetchedNode.netTotalUp,
                    incomingTotalDown: fetchedNode.netTotalDown
                ) {
                    reconciled.trafficUp = resetAwareTrafficDelta(
                        previous: previous.netTotalUp,
                        current: fetchedNode.netTotalUp
                    )
                    reconciled.trafficDown = resetAwareTrafficDelta(
                        previous: previous.netTotalDown,
                        current: fetchedNode.netTotalDown
                    )
                } else {
                    reconciled.isOnline = previous.isOnline
                    reconciled.statusTime = previous.statusTime
                    reconciled.cpuUsage = previous.cpuUsage
                    reconciled.memUsed = previous.memUsed
                    reconciled.swapUsed = previous.swapUsed
                    reconciled.diskUsed = previous.diskUsed
                    reconciled.netIn = previous.netIn
                    reconciled.netOut = previous.netOut
                    reconciled.netTotalUp = previous.netTotalUp
                    reconciled.netTotalDown = previous.netTotalDown
                    reconciled.trafficUp = previous.trafficUp
                    reconciled.trafficDown = previous.trafficDown
                    reconciled.uptime = previous.uptime
                    reconciled.load1 = previous.load1
                    reconciled.load5 = previous.load5
                    reconciled.load15 = previous.load15
                    reconciled.process = previous.process
                    reconciled.connectionsTcp = previous.connectionsTcp
                    reconciled.connectionsUdp = previous.connectionsUdp
                }
                return reconciled
            }
            statusMessage = "Loaded \(fetched.count) node(s)"
            if let selectedNodeId {
                await loadNodeDetail(uuid: selectedNodeId, preserveRecords: true)
            }
        } catch {
            statusMessage = error.localizedDescription
        }
    }

    func refreshStatusesOnly() async {
        guard let server = activeServer, !nodes.isEmpty else { return }
        do {
            let statuses = try await performAuthenticatedRequest(for: server) { client, token in
                try await client.getLatestStatuses(token: token)
            }
            nodes = KomariClient(profile: server).mergeStatuses(nodes: nodes, statuses: statuses)
            if let selectedNodeId,
               let selected = nodes.first(where: { $0.uuid == selectedNodeId }) {
                nodeDetail.node = selected
                if nodeDetail.loadHours == 0 {
                    appendRealtimeLoadRecord(from: selected)
                }
            }
        } catch {
            statusMessage = error.localizedDescription
        }
    }

    func loadNodeDetail(uuid: String, preserveRecords: Bool = false) async {
        let uuid = uuid.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !uuid.isEmpty else {
            nodeDetail.error = "UUID is required"
            nodeDetail.isLoading = false
            return
        }
        selectedNodeId = uuid
        nodeDetail.isLoading = true
        nodeDetail.error = nil
        if !preserveRecords {
            nodeDetail.loadRecords = []
            nodeDetail.pingRecords = []
            nodeDetail.pingTasks = []
        }

        guard let server = activeServer else {
            nodeDetail.error = "No active server"
            nodeDetail.isLoading = false
            return
        }

        do {
            if nodes.isEmpty {
                nodes = try await performAuthenticatedRequest(for: server) { client, token in
                    try await client.getNodes(token: token)
                }
            }
            nodeDetail.node = nodes.first { $0.uuid.trimmingCharacters(in: .whitespacesAndNewlines) == uuid }
            nodeDetail.isLoading = false

            // Load records independently
            do {
                if nodeDetail.loadHours == 0 {
                    nodeDetail.loadRecords = try await performAuthenticatedRequest(for: server) { client, token in
                        try await client.getRecentStatus(token: token, uuid: uuid)
                    }
                } else {
                    let hours = nodeDetail.loadHours
                    nodeDetail.loadRecords = try await performAuthenticatedRequest(for: server) { client, token in
                        try await client.getLoadRecords(token: token, uuid: uuid, hours: hours)
                    }
                }
            } catch {
                if let clientError = error as? KomariClientError,
                   case .requires2FA = clientError {
                    throw clientError
                }
                if nodeDetail.loadHours == 0, let node = nodeDetail.node {
                    appendRealtimeLoadRecord(from: node)
                } else {
                    nodeDetail.error = "Load records: \(error.localizedDescription)"
                }
            }

            do {
                let hours = nodeDetail.pingHours
                let ping = try await performAuthenticatedRequest(for: server) { client, token in
                    try await client.getPingRecords(token: token, uuid: uuid, hours: hours)
                }
                nodeDetail.pingTasks = ping.0
                nodeDetail.pingRecords = ping.1
            } catch {
                let current = nodeDetail.error ?? ""
                nodeDetail.error = current.isEmpty ? "Ping records: \(error.localizedDescription)" : "\(current)\nPing records: \(error.localizedDescription)"
            }
        } catch {
            nodeDetail.isLoading = false
            nodeDetail.error = error.localizedDescription
        }
    }

    func setLoadHours(_ hours: Int) {
        nodeDetail.loadHours = hours
        guard let selectedNodeId else { return }
        Task { await loadNodeDetail(uuid: selectedNodeId) }
    }

    func setPingHours(_ hours: Int) {
        nodeDetail.pingHours = hours
        guard let selectedNodeId else { return }
        Task { await loadNodeDetail(uuid: selectedNodeId) }
    }

    func refreshNodeDetailRecords() async {
        guard let selectedNodeId else { return }
        if nodeDetail.loadHours == 0 {
            await refreshStatusesOnly()
        } else {
            await loadNodeDetail(uuid: selectedNodeId, preserveRecords: true)
        }
    }

    func updateSettings(_ settings: AppSettings) {
        self.settings = settings
        persist()
        startAutoRefresh()
    }

    func startAutoRefresh() {
        refreshTask?.cancel()
        guard settings.autoRefreshEnabled else { return }
        refreshTask = Task { [weak self] in
            while !Task.isCancelled {
                let interval = self?.settings.refreshIntervalSeconds ?? 2
                try? await Task.sleep(nanoseconds: UInt64(max(interval, 1) * 1_000_000_000))
                await self?.refreshStatusesOnly()
            }
        }
    }

    /// Resolves credentials for views that open authenticated sub-features.
    /// `forcePasswordLogin` intentionally affects password sessions only.
    func authenticationToken(
        for server: ServerProfile,
        forcePasswordLogin: Bool = false
    ) async throws -> String {
        let profile = currentProfile(for: server)
        let client = KomariClient(profile: profile)
        return try await resolveToken(
            for: profile,
            client: client,
            forcePasswordLogin: forcePasswordLogin
        )
    }

    private func performAuthenticatedRequest<T>(
        for server: ServerProfile,
        useStoredProfile: Bool = true,
        operation: (KomariClient, String) async throws -> T
    ) async throws -> T {
        let profile = useStoredProfile ? currentProfile(for: server) : server
        let client = KomariClient(profile: profile)
        let token = try await resolveToken(for: profile, client: client)

        do {
            return try await operation(client, token)
        } catch {
            guard profile.authType == .password,
                  let clientError = error as? KomariClientError,
                  clientError.isAuthenticationFailure else {
                throw error
            }

            // Exactly one automatic refresh and one retry. Authentication errors
            // from the retry (including 2FA requirements) propagate to the caller.
            let refreshedProfile = useStoredProfile ? currentProfile(for: profile) : profile
            let refreshedClient = KomariClient(profile: refreshedProfile)
            let refreshedToken = try await resolveToken(
                for: refreshedProfile,
                client: refreshedClient,
                forcePasswordLogin: true
            )
            return try await operation(refreshedClient, refreshedToken)
        }
    }

    private func resolveToken(
        for server: ServerProfile,
        client: KomariClient,
        forcePasswordLogin: Bool = false
    ) async throws -> String {
        switch server.authType {
        case .guest:
            return ""
        case .apiKey:
            let token = server.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
            if token.isEmpty {
                throw KomariClientError.invalidConfiguration("API Key is required")
            }
            return token
        case .password:
            if !forcePasswordLogin, !server.sessionToken.isEmpty {
                return server.sessionToken
            }
            if forcePasswordLogin {
                clearSessionToken(for: server.id)
            }
            let token = try await client.login()
            updateSessionToken(token, for: server.id)
            return token
        }
    }

    private func currentProfile(for server: ServerProfile) -> ServerProfile {
        servers.first(where: { $0.id == server.id }) ?? server
    }

    private func clearSessionToken(for serverId: UUID) {
        guard let index = servers.firstIndex(where: { $0.id == serverId }),
              !servers[index].sessionToken.isEmpty else { return }
        servers[index].sessionToken = ""
        persist()
    }

    private func updateSessionToken(_ token: String, for serverId: UUID) {
        guard let index = servers.firstIndex(where: { $0.id == serverId }) else { return }
        servers[index].sessionToken = token
        servers[index].requires2FA = false
        persist()
    }

    private func appendRealtimeLoadRecord(from node: KomariNode) {
        let now = Date()
        let reportedTime = node.statusTime.trimmingCharacters(in: .whitespacesAndNewlines)
        let recordTime = reportedTime.isEmpty ? ISO8601DateFormatter().string(from: now) : reportedTime
        if let last = nodeDetail.loadRecords.last {
            if last.time == recordTime { return }
            if reportedTime.isEmpty {
                if now.timeIntervalSince(parseLoadRecordDate(last.time)) < max(settings.refreshIntervalSeconds * 0.8, 0.8) {
                    return
                }
            } else {
                let sampleDate = parseLoadRecordDate(recordTime)
                let lastDate = parseLoadRecordDate(last.time)
                if sampleDate != .distantPast, lastDate != .distantPast, sampleDate <= lastDate {
                    return
                }
            }
        }
        let record = LoadRecord(
            time: recordTime,
            cpu: node.cpuUsage,
            ramPercent: node.memTotal > 0 ? Double(node.memUsed) / Double(node.memTotal) * 100 : 0,
            diskPercent: node.diskTotal > 0 ? Double(node.diskUsed) / Double(node.diskTotal) * 100 : 0,
            netIn: node.netIn,
            netOut: node.netOut,
            netTotalUp: node.netTotalUp,
            netTotalDown: node.netTotalDown,
            trafficUp: node.trafficUp,
            trafficDown: node.trafficDown,
            load: node.load1,
            process: node.process,
            connections: node.connectionsTcp,
            connectionsUdp: node.connectionsUdp
        )
        nodeDetail.loadRecords.append(record)
        if nodeDetail.loadRecords.count > 120 {
            nodeDetail.loadRecords.removeFirst(nodeDetail.loadRecords.count - 120)
        }
    }

    private func parseLoadRecordDate(_ string: String) -> Date {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: string) { return date }
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.date(from: string) ?? .distantPast
    }
}

enum NodeLoadMode {
    case initial
    case refresh
}

private extension Array where Element == String {
    func uniqueSorted() -> [String] {
        Array(Set(self)).sorted()
    }
}
