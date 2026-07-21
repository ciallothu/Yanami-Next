import Foundation
import LocalAuthentication

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
    @Published private(set) var credentialAccessPhase: CredentialAccessPhase = .locked
    @Published private(set) var credentialAccessError: String?
    @Published private(set) var twoFactorPrompt: TwoFactorPrompt?
    @Published private(set) var isBiometricLockAuthorizationInProgress = false

    private let profileStore = ProfileStore()
    private var refreshTask: Task<Void, Never>?
    private var fullNodeRequestGeneration: UInt64 = 0
    private var statusRequestGeneration: UInt64 = 0
    private var nodeRequestSequence: UInt64 = 0
    private var lastAppliedPresenceSequence: UInt64 = 0
    private var nodeDetailGeneration: UInt64 = 0
    private var nodeDetailPingRequestGeneration: UInt64 = 0
    private var lastNodeDetailPingRefreshAt: Date?
    private var credentialGeneration: UInt64 = 0
    private var biometricLockAuthorizationGeneration: UInt64 = 0
    private var pendingTwoFactorChallenge: PendingTwoFactorChallenge?

    private static let nodeDetailPingRefreshInterval: TimeInterval = 30

    var isCredentialAccessUnlocked: Bool {
        credentialAccessPhase == .unlocked
    }

    var activeServer: ServerProfile? {
        guard let activeServerId else { return nil }
        return servers.first { $0.id == activeServerId }
    }

    var groups: [String] {
        nodes.map(\.group).filter { !$0.isEmpty }.uniqueSorted()
    }

    var filteredNodes: [KomariNode] {
        nodes.filter { node in
            let matchesUnmaskedIp = nodeIPAddressMatchesSearch(
                query: searchQuery,
                ipv4: node.ipv4,
                ipv6: node.ipv6,
                maskIpEnabled: settings.maskIpEnabled
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
    var totalNetIn: Int64 { saturatingNonNegativeSum(nodes.filter(\.isOnline).map(\.netIn)) }
    var totalNetOut: Int64 { saturatingNonNegativeSum(nodes.filter(\.isOnline).map(\.netOut)) }
    private var allNodeTrafficUsage: NodeTrafficUsageTotals {
        aggregateNodeTrafficUsage(nodes.map { (upload: $0.netTotalUp, download: $0.netTotalDown) })
    }
    var totalUsageUp: Int64 { allNodeTrafficUsage.upload }
    var totalUsageDown: Int64 { allNodeTrafficUsage.download }

    init() {
        do {
            let bootstrap = try profileStore.loadBootstrap()
            credentialAccessPhase = initialCredentialAccessPhase(
                bootstrapLoaded: true,
                lockEnabled: bootstrap.lockEnabled
            )
            if credentialAccessPhase == .unlocked {
                let persisted = try profileStore.loadUnprotectedState()
                // A marker/state mismatch can only be an interrupted disable from an older
                // build. The protected flag remains authoritative and therefore fails closed.
                if persisted.settings.biometricEnabled {
                    var lockedSettings = AppSettings()
                    lockedSettings.biometricEnabled = true
                    settings = lockedSettings
                    credentialAccessPhase = .locked
                } else {
                    restoreProtectedState(persisted)
                    startAutoRefresh()
                }
            } else {
                var lockedSettings = AppSettings()
                lockedSettings.biometricEnabled = true
                settings = lockedSettings
            }
        } catch {
            // Bootstrap corruption, access failure, and migration failure must all present the
            // lock screen without retaining a profile or starting network work.
            profileStore.clearCredentialSession()
            var lockedSettings = AppSettings()
            lockedSettings.biometricEnabled = true
            settings = lockedSettings
            credentialAccessPhase = .locked
            credentialAccessError = error.localizedDescription
        }
    }

    deinit {
        refreshTask?.cancel()
        profileStore.clearCredentialSession()
    }

    @discardableResult
    func persist() -> Bool {
        guard isCredentialAccessUnlocked else { return false }
        do {
            try profileStore.save(
                PersistedAppState(
                    servers: servers,
                    activeServerId: activeServerId,
                    settings: settings
                )
            )
            return true
        } catch {
            statusMessage = "Failed to save app data: \(error.localizedDescription)"
            return false
        }
    }

    /// Called only after LocalAuthentication succeeds. Protected profiles are deliberately not
    /// read or retained before this transition when the Keychain lock marker is enabled.
    @discardableResult
    func unlockCredentialAccess(using authenticationContext: LAContext) -> Bool {
        guard credentialAccessPhase == .locked else { return true }
        do {
            let persisted = try profileStore.loadProtectedState(
                authenticationContext: authenticationContext
            )
            if shouldCompleteInterruptedLockDisable(
                credentialPhase: credentialAccessPhase,
                persistedLockEnabled: persisted.settings.biometricEnabled
            ) {
                // Finish a disable that crashed after marker=false while the envelope was still
                // sealed. Authentication has succeeded, so plaintext publication is authorized.
                try profileStore.save(
                    persisted,
                    authorizationContext: authenticationContext
                )
            }
            credentialGeneration &+= 1
            restoreProtectedState(persisted)
            credentialAccessPhase = .unlocked
            credentialAccessError = nil
            startAutoRefresh()
            return true
        } catch {
            credentialAccessError = error.localizedDescription
            lockCredentialAccess(force: true)
            return false
        }
    }

    /// Invalidates every request lease before clearing credentials and server-owned UI state.
    /// In-flight responses may finish at the transport layer, but cannot publish, retry, log in,
    /// or persist authentication state after this returns.
    func lockCredentialAccess() {
        lockCredentialAccess(force: false)
    }

    private func lockCredentialAccess(force: Bool) {
        guard force || settings.biometricEnabled else { return }
        biometricLockAuthorizationGeneration &+= 1
        isBiometricLockAuthorizationInProgress = false
        profileStore.clearCredentialSession()
        credentialGeneration &+= 1
        credentialAccessPhase = .locked
        refreshTask?.cancel()
        refreshTask = nil
        cancelPendingTwoFactorPrompt()
        resetNodeStateForServerTransition()
        servers.removeAll(keepingCapacity: false)
        activeServerId = nil
        searchQuery = ""
        statusFilter = .all
        var lockedSettings = AppSettings()
        lockedSettings.biometricEnabled = true
        settings = lockedSettings
    }

    func beginBiometricLockAuthorization() -> BiometricLockAuthorizationLease? {
        guard isCredentialAccessUnlocked,
              !isBiometricLockAuthorizationInProgress else {
            return nil
        }
        biometricLockAuthorizationGeneration &+= 1
        isBiometricLockAuthorizationInProgress = true
        return BiometricLockAuthorizationLease(
            generation: biometricLockAuthorizationGeneration
        )
    }

    func canApplyBiometricLockAuthorization(
        _ lease: BiometricLockAuthorizationLease
    ) -> Bool {
        isBiometricLockAuthorizationCurrent(
            lease,
            currentGeneration: biometricLockAuthorizationGeneration,
            authorizationInProgress: isBiometricLockAuthorizationInProgress,
            credentialPhase: credentialAccessPhase
        )
    }

    func endBiometricLockAuthorization(_ lease: BiometricLockAuthorizationLease) {
        guard lease.generation == biometricLockAuthorizationGeneration else { return }
        isBiometricLockAuthorizationInProgress = false
    }

    func cancelBiometricLockAuthorization() {
        biometricLockAuthorizationGeneration &+= 1
        isBiometricLockAuthorizationInProgress = false
    }

    private func restoreProtectedState(_ persisted: PersistedAppState) {
        servers = persisted.servers
        activeServerId = persisted.activeServerId ?? persisted.servers.first?.id
        settings = persisted.settings
    }

    func addServer(_ prepared: PreparedServerProfile) {
        guard isCredentialAccessUnlocked else { return }
        let server = prepared.profile
        var normalized = server
        normalized.baseURL = normalized.normalizedBaseURL
        guard normalized.validatedBaseURL != nil else {
            statusMessage = "Server URL is invalid"
            return
        }
        normalized.customHeaders = normalized.sanitizedCustomHeaders
        servers.append(normalized)
        activateServer(normalized.id)
    }

    func updateServer(_ prepared: PreparedServerProfile) {
        guard isCredentialAccessUnlocked else { return }
        let server = prepared.profile
        var normalized = server
        normalized.baseURL = normalized.normalizedBaseURL
        guard normalized.validatedBaseURL != nil else {
            statusMessage = "Server URL is invalid"
            return
        }
        normalized.customHeaders = normalized.sanitizedCustomHeaders
        guard let index = servers.firstIndex(where: { $0.id == normalized.id }) else {
            statusMessage = "Server no longer exists"
            return
        }
        let current = servers[index]
        let authenticationIdentityChanged =
            authenticationIdentity(for: current) != authenticationIdentity(for: normalized)
        if !authenticationIdentityChanged {
            // A form may have captured an older token before an automatic re-login completed.
            // Non-authentication edits must preserve the latest stored session and 2FA capability.
            normalized.sessionToken = current.sessionToken
            normalized.requires2FA = current.requires2FA
        } else {
            // Bearer sessions are scoped to the complete endpoint/credential identity.
            // ServerForm supplies a freshly authenticated token for the new identity; an empty
            // value remains the safe default for programmatic callers that did not validate it.
            invalidateCredentialRequestsForProfileMutation()
        }
        servers[index] = normalized
        if activeServerId == nil || activeServerId == normalized.id {
            activateServer(normalized.id)
        } else {
            persist()
        }
    }

    func deleteServer(_ server: ServerProfile) {
        guard isCredentialAccessUnlocked else { return }
        guard servers.contains(where: { $0.id == server.id }) else { return }
        invalidateCredentialRequestsForProfileMutation()
        let deletedActiveServer = activeServerId == server.id
        servers.removeAll { $0.id == server.id }
        if deletedActiveServer {
            activateServer(servers.first?.id)
        } else {
            persist()
        }
    }

    func selectServer(_ server: ServerProfile) {
        guard isCredentialAccessUnlocked else { return }
        guard servers.contains(where: { $0.id == server.id }) else {
            statusMessage = "Server no longer exists"
            return
        }
        activateServer(server.id)
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

    func testConnection(_ server: ServerProfile, twoFaCode: String? = nil) async throws -> String {
        try await performAuthenticatedRequest(
            for: server,
            useStoredProfile: false,
            twoFaCode: twoFaCode,
            allowInteractiveTwoFactor: false
        ) { client, token in
            try await client.getVersion(token: token)
        }
    }

    /// Validates a new authentication identity without writing temporary test credentials or a
    /// session into Keychain. The returned profile may be committed synchronously by ServerForm.
    /// A display-only edit reuses the latest stored token rather than an older form snapshot.
    func prepareServerForSave(
        _ server: ServerProfile,
        twoFaCode: String? = nil
    ) async throws -> PreparedServerProfile {
        let lease = try makeCredentialRequestLease()
        var normalized = server
        normalized.baseURL = normalized.normalizedBaseURL
        guard normalized.validatedBaseURL != nil else {
            throw KomariClientError.invalidConfiguration("Server URL is invalid")
        }
        normalized.customHeaders = normalized.sanitizedCustomHeaders

        let existing = servers.first(where: { $0.id == normalized.id })
        let existingIdentity = existing.map { authenticationIdentity(for: $0) }
        let requestedIdentity = authenticationIdentity(for: normalized)
        if let existing, existingIdentity == requestedIdentity {
            normalized.sessionToken = existing.sessionToken
            normalized.requires2FA = existing.requires2FA
            return PreparedServerProfile(profile: normalized)
        }

        let client = KomariClient(profile: normalized)
        let token: String
        switch normalized.authType {
        case .guest:
            token = ""
            normalized.sessionToken = ""
            normalized.requires2FA = false
        case .apiKey:
            token = normalized.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !token.isEmpty else {
                throw KomariClientError.invalidConfiguration("API Key is required")
            }
            normalized.sessionToken = ""
            normalized.requires2FA = false
        case .password:
            let code = normalizedTwoFactorCode(twoFaCode)
            token = try await client.login(twoFaCode: code)
            try ensureCredentialRequestLease(lease)
            normalized.sessionToken = token
            normalized.requires2FA = code != nil
        }

        _ = try await client.getVersion(token: token)
        try ensureCredentialRequestLease(lease)
        if let existingIdentity {
            guard authenticationSnapshotMatchesCurrentIdentity(
                snapshot: existingIdentity,
                current: currentAuthenticationIdentity(for: normalized.id)
            ) else {
                throw staleAuthenticationSnapshotError()
            }
        } else if servers.contains(where: { $0.id == normalized.id }) {
            throw staleAuthenticationSnapshotError()
        }
        return PreparedServerProfile(profile: normalized)
    }

    func loadNodes(mode: NodeLoadMode = .refresh) async {
        guard isCredentialAccessUnlocked else { return }
        guard let server = activeServer else {
            statusMessage = "Add or select a Komari instance first"
            return
        }
        fullNodeRequestGeneration &+= 1
        let requestGeneration = fullNodeRequestGeneration
        nodeRequestSequence &+= 1
        let requestSequence = nodeRequestSequence
        if mode == .initial {
            isLoadingNodes = true
            isRefreshingNodes = false
        } else {
            isLoadingNodes = false
            isRefreshingNodes = true
        }
        defer {
            if requestGeneration == fullNodeRequestGeneration {
                isLoadingNodes = false
                isRefreshingNodes = false
            }
        }

        do {
            let fetched = try await performAuthenticatedRequest(for: server) { client, token in
                try await client.getNodes(token: token)
            }
            guard requestGeneration == fullNodeRequestGeneration,
                  activeServerId == server.id else { return }
            let mayApplyPresence = shouldApplyPresence(
                requestSequence: requestSequence,
                lastAppliedSequence: lastAppliedPresenceSequence
            )
            let previousNodes = Dictionary(uniqueKeysWithValues: nodes.map { ($0.uuid, $0) })
            nodes = fetched.map { fetchedNode in
                guard let previous = previousNodes[fetchedNode.uuid] else {
                    var newlyDiscovered = fetchedNode
                    if !mayApplyPresence {
                        // The only available presence for this new UUID belongs to an older
                        // request. Start fail-closed until the next status response confirms it.
                        newlyDiscovered.isOnline = false
                    }
                    return newlyDiscovered
                }
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
                // Presence is independent of the report timestamp. A newer-started status/full
                // request wins even when this response arrives later.
                if !mayApplyPresence {
                    reconciled.isOnline = previous.isOnline
                }
                return reconciled
            }
            if mayApplyPresence {
                lastAppliedPresenceSequence = requestSequence
            }
            statusMessage = "Loaded \(fetched.count) node(s)"
            if let selectedNodeId {
                await loadNodeDetail(uuid: selectedNodeId, preserveRecords: true)
            }
        } catch {
            guard requestGeneration == fullNodeRequestGeneration,
                  activeServerId == server.id else { return }
            statusMessage = error.localizedDescription
        }
    }

    func refreshStatusesOnly() async {
        guard isCredentialAccessUnlocked else { return }
        guard let server = activeServer, !nodes.isEmpty else { return }
        statusRequestGeneration &+= 1
        let requestGeneration = statusRequestGeneration
        nodeRequestSequence &+= 1
        let requestSequence = nodeRequestSequence
        do {
            let statuses = try await performAuthenticatedRequest(for: server) { client, token in
                try await client.getLatestStatuses(token: token)
            }
            guard requestGeneration == statusRequestGeneration,
                  activeServerId == server.id else { return }
            let previousPresence = Dictionary(
                uniqueKeysWithValues: nodes.map { ($0.uuid, $0.isOnline) }
            )
            var mergedNodes = KomariClient(profile: server).mergeStatuses(
                nodes: nodes,
                statuses: statuses
            )
            let mayApplyPresence = shouldApplyPresence(
                requestSequence: requestSequence,
                lastAppliedSequence: lastAppliedPresenceSequence
            )
            if mayApplyPresence {
                lastAppliedPresenceSequence = requestSequence
            } else {
                mergedNodes = mergedNodes.map { node in
                    var reconciled = node
                    if let wasOnline = previousPresence[node.uuid] {
                        reconciled.isOnline = wasOnline
                    }
                    return reconciled
                }
            }
            nodes = mergedNodes
            if let selectedNodeId,
               let selected = nodes.first(where: { $0.uuid == selectedNodeId }) {
                nodeDetail.node = selected
                if nodeDetail.loadHours == 0 {
                    appendRealtimeLoadRecord(from: selected)
                }
            }
        } catch {
            guard requestGeneration == statusRequestGeneration,
                  activeServerId == server.id else { return }
            statusMessage = error.localizedDescription
        }
    }

    func loadNodeDetail(uuid: String, preserveRecords: Bool = false) async {
        guard isCredentialAccessUnlocked else { return }
        let uuid = uuid.trimmingCharacters(in: .whitespacesAndNewlines)
        nodeDetailGeneration &+= 1
        let requestGeneration = nodeDetailGeneration
        guard !uuid.isEmpty else {
            selectedNodeId = nil
            nodeDetail.node = nil
            nodeDetail.error = "UUID is required"
            nodeDetail.isLoading = false
            nodeDetail.isLoadingPing24Hours = false
            return
        }

        guard let server = activeServer else {
            selectedNodeId = nil
            nodeDetail.node = nil
            nodeDetail.error = "No active server"
            nodeDetail.isLoading = false
            nodeDetail.isLoadingPing24Hours = false
            return
        }

        let changedNode = selectedNodeId != uuid ||
            nodeDetail.node?.uuid.trimmingCharacters(in: .whitespacesAndNewlines) != uuid
        selectedNodeId = uuid
        nodeDetail.isLoading = true
        nodeDetail.error = nil
        nodeDetail.ping24HourError = nil
        nodeDetail.isLoadingPing24Hours = changedNode || nodeDetail.ping24HourTasks.isEmpty
        if changedNode {
            nodeDetail.node = nil
            nodeDetail.loadRecords = []
            nodeDetail.pingRecords = []
            nodeDetail.pingTasks = []
            nodeDetail.ping24HourRecords = []
            nodeDetail.ping24HourTasks = []
        } else if !preserveRecords {
            nodeDetail.loadRecords = []
            nodeDetail.pingRecords = []
            nodeDetail.pingTasks = []
        }

        let request = NodeDetailRequestIdentity(
            generation: requestGeneration,
            serverID: server.id,
            nodeUUID: uuid,
            loadHours: nodeDetail.loadHours,
            pingHours: nodeDetail.pingHours
        )

        do {
            var availableNodes = nodes
            if availableNodes.isEmpty {
                let fetchedNodes = try await performAuthenticatedRequest(for: server) { client, token in
                    try await client.getNodes(token: token)
                }
                guard isCurrentDetailRequest(request) else { return }
                availableNodes = fetchedNodes
            }
            guard isCurrentDetailRequest(request) else { return }
            nodeDetail.node = availableNodes.first {
                $0.uuid.trimmingCharacters(in: .whitespacesAndNewlines) == uuid
            }
            nodeDetail.isLoading = false

            // Load records independently
            do {
                let loadRecords: [LoadRecord]
                if request.loadHours == 0 {
                    loadRecords = try await performAuthenticatedRequest(for: server) { client, token in
                        try await client.getRecentStatus(token: token, uuid: uuid)
                    }
                } else {
                    loadRecords = try await performAuthenticatedRequest(for: server) { client, token in
                        try await client.getLoadRecords(
                            token: token,
                            uuid: uuid,
                            hours: request.loadHours
                        )
                    }
                }
                guard isCurrentDetailRequest(request) else { return }
                nodeDetail.loadRecords = loadRecords
            } catch {
                guard isCurrentDetailRequest(request) else { return }
                if let clientError = error as? KomariClientError,
                   case .requires2FA = clientError {
                    throw clientError
                }
                if request.loadHours == 0, let node = nodeDetail.node {
                    appendRealtimeLoadRecord(from: node)
                } else {
                    nodeDetail.error = "Load records: \(error.localizedDescription)"
                }
            }

            await refreshPingRecords(request: request, server: server)
        } catch {
            guard isCurrentDetailRequest(request) else { return }
            nodeDetail.isLoading = false
            nodeDetail.isLoadingPing24Hours = false
            nodeDetail.error = error.localizedDescription
        }
    }

    /// Refreshes the fixed 24-hour summary and the interactive Ping range together. A separate
    /// generation prevents an older periodic request from overwriting a newer pull-to-refresh or
    /// range change, while the node-detail identity guards server/node/range transitions.
    private func refreshPingRecords(
        request: NodeDetailRequestIdentity,
        server: ServerProfile
    ) async {
        nodeDetailPingRequestGeneration &+= 1
        let pingRequestGeneration = nodeDetailPingRequestGeneration
        lastNodeDetailPingRefreshAt = Date()

        func isCurrentPingRequest() -> Bool {
            pingRequestGeneration == nodeDetailPingRequestGeneration &&
                isCurrentDetailRequest(request)
        }

        do {
            let ping24Hours = try await performAuthenticatedRequest(for: server) { client, token in
                try await client.getPingRecords(
                    token: token,
                    uuid: request.nodeUUID,
                    hours: 24
                )
            }
            guard isCurrentPingRequest() else { return }
            nodeDetail.ping24HourTasks = ping24Hours.0
            nodeDetail.ping24HourRecords = ping24Hours.1
            nodeDetail.ping24HourError = nil
            nodeDetail.isLoadingPing24Hours = false
            if request.pingHours == 24 {
                nodeDetail.pingTasks = ping24Hours.0
                nodeDetail.pingRecords = ping24Hours.1
            }
        } catch {
            guard isCurrentPingRequest() else { return }
            nodeDetail.isLoadingPing24Hours = false
            nodeDetail.ping24HourError = error.localizedDescription
        }

        guard request.pingHours != 24, isCurrentPingRequest() else { return }
        do {
            let ping = try await performAuthenticatedRequest(for: server) { client, token in
                try await client.getPingRecords(
                    token: token,
                    uuid: request.nodeUUID,
                    hours: request.pingHours
                )
            }
            guard isCurrentPingRequest() else { return }
            nodeDetail.pingTasks = ping.0
            nodeDetail.pingRecords = ping.1
            updateNodeDetailPingError(nil)
        } catch {
            guard isCurrentPingRequest() else { return }
            updateNodeDetailPingError(error.localizedDescription)
        }
    }

    private func updateNodeDetailPingError(_ message: String?) {
        var lines = (nodeDetail.error ?? "")
            .split(separator: "\n", omittingEmptySubsequences: true)
            .map(String.init)
            .filter { !$0.hasPrefix("Ping records:") }
        if let message, !message.isEmpty {
            lines.append("Ping records: \(String(message.prefix(512)))")
        }
        nodeDetail.error = lines.isEmpty ? nil : lines.joined(separator: "\n")
    }

    func refreshNodeDetailPingRecordsIfDue() async {
        guard isCredentialAccessUnlocked,
              !nodeDetail.isLoading,
              let server = activeServer,
              let selectedNodeId else { return }
        let now = Date()
        if !shouldRefreshNodePingHistory(
            lastRefreshAt: lastNodeDetailPingRefreshAt,
            now: now,
            minimumInterval: Self.nodeDetailPingRefreshInterval
        ) {
            return
        }
        let request = NodeDetailRequestIdentity(
            generation: nodeDetailGeneration,
            serverID: server.id,
            nodeUUID: selectedNodeId,
            loadHours: nodeDetail.loadHours,
            pingHours: nodeDetail.pingHours
        )
        await refreshPingRecords(request: request, server: server)
    }

    func setLoadHours(_ hours: Int) {
        guard nodeDetail.loadHours != hours else { return }
        nodeDetail.loadHours = hours
        nodeDetailGeneration &+= 1
        nodeDetail.isLoading = false
        guard let selectedNodeId else { return }
        Task { await loadNodeDetail(uuid: selectedNodeId) }
    }

    func setPingHours(_ hours: Int) {
        guard nodeDetail.pingHours != hours else { return }
        nodeDetail.pingHours = hours
        nodeDetailGeneration &+= 1
        nodeDetail.isLoading = false
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

    @discardableResult
    func updateSettings(
        _ settings: AppSettings,
        authorizationContext: LAContext? = nil
    ) -> Bool {
        guard isCredentialAccessUnlocked else { return false }
        let previous = self.settings
        do {
            try profileStore.save(
                PersistedAppState(
                    servers: servers,
                    activeServerId: activeServerId,
                    settings: settings
                ),
                authorizationContext: authorizationContext
            )
        } catch {
            statusMessage = "Failed to save app data: \(error.localizedDescription)"
            self.settings = previous
            if previous.biometricEnabled != settings.biometricEnabled {
                // A transition may have durably published its first fail-closed write. Clear
                // every in-memory credential and let the next authenticated read reconcile it.
                lockCredentialAccess(force: true)
            }
            return false
        }
        self.settings = settings
        if !previous.biometricEnabled && settings.biometricEnabled {
            // Do not leave a refresh window between publishing the setting and RootView reacting.
            lockCredentialAccess(force: true)
        } else {
            startAutoRefresh()
        }
        return true
    }

    func startAutoRefresh() {
        refreshTask?.cancel()
        guard isCredentialAccessUnlocked, settings.autoRefreshEnabled else { return }
        refreshTask = Task { [weak self] in
            while !Task.isCancelled {
                let interval = self?.settings.refreshIntervalSeconds ?? 2
                do {
                    try await Task.sleep(
                        nanoseconds: UInt64(max(interval, 1) * 1_000_000_000)
                    )
                } catch {
                    return
                }
                guard let self, self.isCredentialAccessUnlocked else { return }
                await self.refreshStatusesOnly()
            }
        }
    }

    /// Resolves credentials for views that open authenticated sub-features.
    /// `forcePasswordLogin` intentionally affects password sessions only.
    func authenticationToken(
        for server: ServerProfile,
        forcePasswordLogin: Bool = false,
        twoFaCode: String? = nil
    ) async throws -> String {
        let lease = try makeCredentialRequestLease()
        let requestedIdentity = authenticationIdentity(for: server)
        guard let profile = servers.first(where: { $0.id == server.id }),
              authenticationSnapshotMatchesCurrentIdentity(
                snapshot: requestedIdentity,
                current: authenticationIdentity(for: profile)
              ) else {
            throw staleAuthenticationSnapshotError()
        }
        let client = KomariClient(profile: profile)
        let token = try await resolveToken(
            for: profile,
            client: client,
            forcePasswordLogin: forcePasswordLogin,
            persistSession: true,
            lease: lease,
            twoFaCode: twoFaCode,
            allowInteractiveTwoFactor: true
        )
        try ensureCredentialRequestLease(lease)
        guard authenticationSnapshotMatchesCurrentIdentity(
            snapshot: requestedIdentity,
            current: currentAuthenticationIdentity(for: server.id)
        ) else {
            throw staleAuthenticationSnapshotError()
        }
        return token
    }

    /// Used by long-lived views before they reuse a client built from a captured profile. Session
    /// token rotation is intentionally excluded; endpoint, authentication, headers, credentials,
    /// and TLS policy are not.
    func isAuthenticationSnapshotCurrent(_ server: ServerProfile) -> Bool {
        isCredentialAccessUnlocked && authenticationSnapshotMatchesCurrentIdentity(
            snapshot: authenticationIdentity(for: server),
            current: currentAuthenticationIdentity(for: server.id)
        )
    }

    private func performAuthenticatedRequest<T>(
        for server: ServerProfile,
        useStoredProfile: Bool = true,
        twoFaCode: String? = nil,
        allowInteractiveTwoFactor: Bool = true,
        operation: (KomariClient, String) async throws -> T
    ) async throws -> T {
        let lease = try makeCredentialRequestLease()
        let profile: ServerProfile
        if useStoredProfile {
            let requestedIdentity = authenticationIdentity(for: server)
            guard let current = servers.first(where: { $0.id == server.id }),
                  authenticationSnapshotMatchesCurrentIdentity(
                    snapshot: requestedIdentity,
                    current: authenticationIdentity(for: current)
                  ) else {
                throw staleAuthenticationSnapshotError()
            }
            profile = current
        } else {
            profile = server
        }
        try ensureCredentialRequestLease(lease)
        let client = KomariClient(profile: profile)
        let startedAuthenticationIdentity = authenticationIdentity(for: profile)
        let token = try await resolveToken(
            for: profile,
            client: client,
            forcePasswordLogin: !useStoredProfile,
            persistSession: useStoredProfile,
            lease: lease,
            twoFaCode: twoFaCode,
            allowInteractiveTwoFactor: allowInteractiveTwoFactor
        )

        do {
            try ensureCredentialRequestLease(lease)
            let value = try await operation(client, token)
            try ensureCredentialRequestLease(lease)
            return value
        } catch {
            try ensureCredentialRequestLease(lease)
            guard profile.authType == .password,
                  let clientError = error as? KomariClientError,
                  clientError.isAuthenticationFailure else {
                throw error
            }

            if useStoredProfile {
                guard shouldPersistAuthenticationResult(
                    startedWith: startedAuthenticationIdentity,
                    current: currentAuthenticationIdentity(for: profile.id)
                ) else {
                    throw CancellationError()
                }
            }

            // Exactly one automatic refresh and one operation retry. Password login itself may
            // suspend for an interactive 2FA code, but never retries the protected operation more
            // than once.
            let refreshedProfile: ServerProfile
            if useStoredProfile {
                guard let current = servers.first(where: { $0.id == profile.id }),
                      authenticationSnapshotMatchesCurrentIdentity(
                        snapshot: startedAuthenticationIdentity,
                        current: authenticationIdentity(for: current)
                      ) else {
                    throw staleAuthenticationSnapshotError()
                }
                refreshedProfile = current
            } else {
                refreshedProfile = profile
            }
            try ensureCredentialRequestLease(lease)
            let refreshedClient = KomariClient(profile: refreshedProfile)
            let refreshedToken = try await resolveToken(
                for: refreshedProfile,
                client: refreshedClient,
                forcePasswordLogin: true,
                persistSession: useStoredProfile,
                lease: lease,
                twoFaCode: twoFaCode,
                allowInteractiveTwoFactor: allowInteractiveTwoFactor
            )
            try ensureCredentialRequestLease(lease)
            let value = try await operation(refreshedClient, refreshedToken)
            try ensureCredentialRequestLease(lease)
            return value
        }
    }

    private func resolveToken(
        for server: ServerProfile,
        client: KomariClient,
        forcePasswordLogin: Bool = false,
        persistSession: Bool,
        lease: CredentialRequestLease,
        twoFaCode: String?,
        allowInteractiveTwoFactor: Bool
    ) async throws -> String {
        try ensureCredentialRequestLease(lease)
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
                if persistSession, normalizedTwoFactorCode(twoFaCode) != nil {
                    markRequiresTwoFactor(
                        expectedIdentity: authenticationIdentity(for: server)
                    )
                }
                return server.sessionToken
            }
            let startedAuthenticationIdentity = authenticationIdentity(for: server)
            if forcePasswordLogin, persistSession {
                clearSessionToken(expectedIdentity: startedAuthenticationIdentity)
            }
            var submittedCode = normalizedTwoFactorCode(twoFaCode)
            let permitsInteractiveChallenge = allowInteractiveTwoFactor && submittedCode == nil
            var retryMessage: String?
            let token: String
            while true {
                try ensureCredentialRequestLease(lease)
                do {
                    token = try await client.login(twoFaCode: submittedCode)
                    break
                } catch let error as KomariClientError {
                    let needsCode: Bool
                    switch error {
                    case .requires2FA:
                        needsCode = true
                        retryMessage = nil
                    case .invalidTwoFactorCode:
                        needsCode = true
                        retryMessage = error.localizedDescription
                    default:
                        throw error
                    }
                    guard needsCode else { throw error }
                    if persistSession {
                        markRequiresTwoFactor(expectedIdentity: startedAuthenticationIdentity)
                    }
                    guard permitsInteractiveChallenge else {
                        throw error
                    }
                    submittedCode = try await requestTwoFactorCode(
                        for: server,
                        lease: lease,
                        message: retryMessage
                    )
                }
            }
            try ensureCredentialRequestLease(lease)
            if persistSession {
                guard shouldPersistAuthenticationResult(
                    startedWith: startedAuthenticationIdentity,
                    current: currentAuthenticationIdentity(for: server.id)
                ) else {
                    throw CancellationError()
                }
                updateSessionToken(
                    token,
                    expectedIdentity: startedAuthenticationIdentity,
                    requiresTwoFactor: submittedCode != nil
                )
            }
            return token
        }
    }

    private func clearSessionToken(expectedIdentity: ServerAuthenticationIdentity) {
        guard let index = servers.firstIndex(where: { $0.id == expectedIdentity.serverID }),
              authenticationIdentity(for: servers[index]) == expectedIdentity,
              !servers[index].sessionToken.isEmpty else { return }
        servers[index].sessionToken = ""
        persist()
    }

    private func updateSessionToken(
        _ token: String,
        expectedIdentity: ServerAuthenticationIdentity,
        requiresTwoFactor: Bool
    ) {
        guard let index = servers.firstIndex(where: { $0.id == expectedIdentity.serverID }),
              authenticationIdentity(for: servers[index]) == expectedIdentity else { return }
        servers[index].sessionToken = token
        servers[index].requires2FA = requiresTwoFactor
        persist()
    }

    private func markRequiresTwoFactor(expectedIdentity: ServerAuthenticationIdentity) {
        guard let index = servers.firstIndex(where: { $0.id == expectedIdentity.serverID }),
              authenticationIdentity(for: servers[index]) == expectedIdentity,
              !servers[index].requires2FA else { return }
        servers[index].requires2FA = true
        persist()
    }

    private func makeCredentialRequestLease() throws -> CredentialRequestLease {
        let lease = CredentialRequestLease(generation: credentialGeneration)
        try ensureCredentialRequestLease(lease)
        return lease
    }

    private func invalidateCredentialRequestsForProfileMutation() {
        credentialGeneration &+= 1
        cancelPendingTwoFactorPrompt()
    }

    private func ensureCredentialRequestLease(_ lease: CredentialRequestLease) throws {
        guard !Task.isCancelled,
              isCredentialRequestLeaseCurrent(
                lease,
                generation: credentialGeneration,
                phase: credentialAccessPhase
              ) else {
            throw CancellationError()
        }
    }

    private func normalizedTwoFactorCode(_ code: String?) -> String? {
        guard let value = code?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty else { return nil }
        return String(value.prefix(64))
    }

    private func requestTwoFactorCode(
        for server: ServerProfile,
        lease: CredentialRequestLease,
        message: String?
    ) async throws -> String {
        try ensureCredentialRequestLease(lease)
        guard canBeginTwoFactorChallenge(
            phase: credentialAccessPhase,
            hasPendingChallenge: pendingTwoFactorChallenge != nil
        ) else {
            throw KomariClientError.invalidConfiguration(
                "Another two-factor authentication request is already waiting for a code"
            )
        }

        let promptID = UUID()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                guard !Task.isCancelled,
                      isCredentialRequestLeaseCurrent(
                        lease,
                        generation: credentialGeneration,
                        phase: credentialAccessPhase
                      ) else {
                    continuation.resume(throwing: CancellationError())
                    return
                }
                pendingTwoFactorChallenge = PendingTwoFactorChallenge(
                    promptID: promptID,
                    lease: lease,
                    continuation: continuation
                )
                twoFactorPrompt = TwoFactorPrompt(
                    id: promptID,
                    serverID: server.id,
                    serverName: String(server.name.prefix(128)),
                    message: message
                )
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                self?.cancelTwoFactorPrompt(id: promptID)
            }
        }
    }

    @discardableResult
    func submitTwoFactorCode(_ code: String, promptID: UUID) -> Bool {
        guard let pending = pendingTwoFactorChallenge,
              pending.promptID == promptID,
              isCredentialRequestLeaseCurrent(
                pending.lease,
                generation: credentialGeneration,
                phase: credentialAccessPhase
              ) else {
            cancelTwoFactorPrompt(id: promptID)
            return false
        }
        guard let normalized = normalizedTwoFactorCode(code) else {
            if let prompt = twoFactorPrompt, prompt.id == promptID {
                twoFactorPrompt = TwoFactorPrompt(
                    id: prompt.id,
                    serverID: prompt.serverID,
                    serverName: prompt.serverName,
                    message: "Enter your two-factor authentication code"
                )
            }
            return false
        }
        pendingTwoFactorChallenge = nil
        twoFactorPrompt = nil
        pending.continuation.resume(returning: normalized)
        return true
    }

    func cancelTwoFactorPrompt(id: UUID? = nil) {
        guard let pending = pendingTwoFactorChallenge,
              id == nil || id == pending.promptID else { return }
        pendingTwoFactorChallenge = nil
        twoFactorPrompt = nil
        pending.continuation.resume(throwing: CancellationError())
    }

    private func cancelPendingTwoFactorPrompt() {
        cancelTwoFactorPrompt()
    }

    private func currentAuthenticationIdentity(for serverID: UUID) -> ServerAuthenticationIdentity? {
        guard let server = servers.first(where: { $0.id == serverID }) else { return nil }
        return authenticationIdentity(for: server)
    }

    private func authenticationIdentity(for server: ServerProfile) -> ServerAuthenticationIdentity {
        ServerAuthenticationIdentity(
            serverID: server.id,
            normalizedBaseURL: server.normalizedBaseURL,
            authType: server.authType.rawValue,
            username: server.username,
            password: server.password,
            apiKey: server.apiKey,
            headers: server.sanitizedCustomHeaders.map {
                AuthenticationHeaderIdentity(name: $0.name, value: $0.value)
            },
            allowsInsecureTLS: server.allowInsecureTLS
        )
    }

    private func staleAuthenticationSnapshotError() -> KomariClientError {
        KomariClientError.invalidConfiguration(
            "Server configuration changed. Reopen this screen before authenticating."
        )
    }

    private func activateServer(_ serverID: UUID?) {
        activeServerId = serverID
        resetNodeStateForServerTransition()
        persist()
        guard let serverID else { return }
        Task { [weak self] in
            guard let self, self.activeServerId == serverID else { return }
            await self.loadNodes(mode: .initial)
        }
    }

    /// Clears all server-owned presentation state and invalidates every response that was started
    /// for the previous server or profile configuration.
    private func resetNodeStateForServerTransition() {
        fullNodeRequestGeneration &+= 1
        statusRequestGeneration &+= 1
        nodeRequestSequence &+= 1
        lastAppliedPresenceSequence = nodeRequestSequence
        nodeDetailGeneration &+= 1
        nodeDetailPingRequestGeneration &+= 1
        lastNodeDetailPingRefreshAt = nil
        isLoadingNodes = false
        isRefreshingNodes = false
        nodes = []
        selectedNodeId = nil
        nodeDetail = NodeDetailState()
        selectedGroup = nil
        statusMessage = ""
    }

    private func isCurrentDetailRequest(_ request: NodeDetailRequestIdentity) -> Bool {
        !Task.isCancelled && isCurrentNodeDetailRequest(
            request,
            generation: nodeDetailGeneration,
            activeServerID: activeServerId,
            selectedNodeUUID: selectedNodeId,
            loadHours: nodeDetail.loadHours,
            pingHours: nodeDetail.pingHours
        )
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

struct TwoFactorPrompt: Identifiable, Equatable {
    let id: UUID
    let serverID: UUID
    let serverName: String
    let message: String?
}

struct PreparedServerProfile {
    fileprivate let profile: ServerProfile
}

private struct PendingTwoFactorChallenge {
    let promptID: UUID
    let lease: CredentialRequestLease
    let continuation: CheckedContinuation<String, Error>
}

private extension Array where Element == String {
    func uniqueSorted() -> [String] {
        Array(Set(self)).sorted()
    }
}
