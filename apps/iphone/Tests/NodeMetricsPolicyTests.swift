import Foundation

@main
enum NodeMetricsPolicyTests {
    static func main() {
        expect(
            nodeIPAddressMatchesSearch(
                query: "203.0.113.42",
                ipv4: "203.0.113.42",
                ipv6: "2001:db8::42",
                maskIpEnabled: false
            ),
            "Visible IPv4 address should remain searchable"
        )
        expect(
            !nodeIPAddressMatchesSearch(
                query: "203.0.113.42",
                ipv4: "203.0.113.42",
                ipv6: "2001:db8::42",
                maskIpEnabled: true
            ),
            "Masked IPv4 address must not act as a search oracle"
        )
        expect(
            !nodeIPAddressMatchesSearch(
                query: "2001:db8::42",
                ipv4: "203.0.113.42",
                ipv6: "2001:db8::42",
                maskIpEnabled: true
            ),
            "Masked IPv6 address must not act as a search oracle"
        )

        let totals = aggregateNodeTrafficUsage([
            (upload: 100, download: 200),
            (upload: 300, download: 400)
        ])
        expect(
            totals == NodeTrafficUsageTotals(upload: 400, download: 600),
            "Cumulative usage must include counters from every node"
        )
        expect(
            saturatingNonNegativeSum([Int64.max, 1]) == Int64.max,
            "Untrusted aggregate counters must saturate instead of overflowing"
        )
        expect(
            saturatingNonNegativeSum([-1, 2]) == 2,
            "Negative remote counters must not reduce an aggregate"
        )
        expect(
            trafficLimitUsedBytes(
                upload: Int64.max,
                download: 1,
                type: "sum"
            ) == Int64.max,
            "Traffic-limit sum must saturate"
        )
        expect(
            trafficLimitUsedBytes(upload: -5, download: 9, type: "max") == 9 &&
                trafficLimitUsedBytes(upload: -5, download: 9, type: "min") == 0 &&
                trafficLimitUsedBytes(upload: -5, download: 9, type: "up") == 0 &&
                trafficLimitUsedBytes(upload: 9, download: -5, type: "down") == 0,
            "Every traffic-limit mode must clamp negative remote counters"
        )
        expect(
            trafficLimitAccountingMode(type: " sum ") == .total &&
                trafficLimitAccountingMode(type: "MAX") == .maximumDirection &&
                trafficLimitAccountingMode(type: "min") == .minimumDirection &&
                trafficLimitAccountingMode(type: "up") == .upload &&
                trafficLimitAccountingMode(type: "down") == .download,
            "Traffic-limit labels and calculations must use the same normalized accounting mode"
        )

        let pingSummary = summarizePingLatency(values: [120, -1, 180, .nan, 60])
        expect(
            pingSummary.sampleCount == 4 &&
                pingSummary.successfulSampleCount == 3 &&
                pingSummary.lostSampleCount == 1 &&
                pingSummary.latestMilliseconds == 60 &&
                pingSummary.averageMilliseconds == 120 &&
                pingSummary.minimumMilliseconds == 60 &&
                pingSummary.maximumMilliseconds == 180 &&
                pingSummary.packetLossPercent == 25,
            "Negative Komari ping values must count as packet loss and never as latency"
        )
        let allLostPingSummary = summarizePingLatency(values: [-1, -1])
        expect(
            allLostPingSummary.sampleCount == 2 &&
                allLostPingSummary.successfulSampleCount == 0 &&
                allLostPingSummary.latestMilliseconds == nil &&
                allLostPingSummary.averageMilliseconds == nil &&
                allLostPingSummary.packetLossPercent == 100,
            "An all-loss window must show 100% loss without inventing a zero-millisecond latency"
        )
        let emptyPingSummary = summarizePingLatency(values: [.infinity, -.infinity, .nan])
        expect(
            emptyPingSummary.sampleCount == 0 &&
                emptyPingSummary.packetLossPercent == nil,
            "A window without finite records must remain unavailable rather than display 0% loss"
        )
        let extremePingSummary = summarizePingLatency(
            values: [.greatestFiniteMagnitude, .greatestFiniteMagnitude]
        )
        expect(
            extremePingSummary.averageMilliseconds?.isFinite == true,
            "Hostile finite latency values must not overflow the average"
        )
        let resolvedPingMetrics = resolvePingLatencyMetrics(
            reportedSampleCount: 120,
            reportedLatestMilliseconds: 205,
            reportedAverageMilliseconds: 180,
            reportedPacketLossPercent: 2.5,
            recordValues: [50, -1]
        )
        expect(
            resolvedPingMetrics == ResolvedPingLatencyMetrics(
                sampleCount: 120,
                latestMilliseconds: 205,
                averageMilliseconds: 180,
                packetLossPercent: 2.5,
                hasReportedSuccessfulSamples: true
            ),
            "Full-window task statistics must take precedence over downsampled chart records"
        )
        let legacyPingMetrics = resolvePingLatencyMetrics(
            reportedSampleCount: 0,
            reportedLatestMilliseconds: -1,
            reportedAverageMilliseconds: 0,
            reportedPacketLossPercent: nil,
            recordValues: [40, -1, 80]
        )
        expect(
            legacyPingMetrics.sampleCount == 3 &&
                legacyPingMetrics.latestMilliseconds == 80 &&
                legacyPingMetrics.averageMilliseconds == 60 &&
                abs((legacyPingMetrics.packetLossPercent ?? 0) - (100.0 / 3.0)) < 0.000_001,
            "Older servers without task totals must fall back to their real record values"
        )
        let reportedAllLoss = resolvePingLatencyMetrics(
            reportedSampleCount: 10,
            reportedLatestMilliseconds: -1,
            reportedAverageMilliseconds: 0,
            reportedPacketLossPercent: 100,
            recordValues: Array(repeating: -1, count: 10)
        )
        expect(
            reportedAllLoss.latestMilliseconds == nil &&
                reportedAllLoss.averageMilliseconds == nil &&
                reportedAllLoss.packetLossPercent == 100 &&
                !reportedAllLoss.hasReportedSuccessfulSamples,
            "A reported all-loss task must not expose the server's zero placeholder as latency"
        )
        for invalidLoss in [-1.0, 100.1, .infinity, .nan] {
            let invalidReportedLoss = resolvePingLatencyMetrics(
                reportedSampleCount: 10,
                reportedLatestMilliseconds: 1,
                reportedAverageMilliseconds: 1,
                reportedPacketLossPercent: invalidLoss,
                recordValues: [20, -1, 40]
            )
            expect(
                invalidReportedLoss.sampleCount == 3 &&
                    invalidReportedLoss.latestMilliseconds == 40 &&
                    invalidReportedLoss.averageMilliseconds == 30 &&
                    abs((invalidReportedLoss.packetLossPercent ?? 0) - (100.0 / 3.0)) < 0.000_001 &&
                    !invalidReportedLoss.hasReportedSuccessfulSamples,
                "Invalid server loss statistics must fall back to real records instead of being clamped"
            )
        }
        let contradictoryAllLoss = resolvePingLatencyMetrics(
            reportedSampleCount: 10,
            reportedLatestMilliseconds: 20,
            reportedAverageMilliseconds: 20,
            reportedPacketLossPercent: 100,
            recordValues: [20, -1]
        )
        expect(
            contradictoryAllLoss.sampleCount == 10 &&
                contradictoryAllLoss.latestMilliseconds == nil &&
                contradictoryAllLoss.averageMilliseconds == nil &&
                contradictoryAllLoss.packetLossPercent == 100,
            "A valid full-window 100% loss statistic must not be contradicted by sampled latency records"
        )
        expect(
            pingLatencySegmentIdentifiers(values: [10, -1, 20]) == [0, nil, 1] &&
                pingLatencySegmentIdentifiers(values: [-1, 10, 20]) == [nil, 0, 0] &&
                pingLatencySegmentIdentifiers(values: [-1, -1]) == [nil, nil] &&
                pingLatencySegmentIdentifiers(values: [10, .nan, 20]) == [0, nil, 1],
            "Loss and invalid samples must split latency chart lines into real contiguous runs"
        )
        let pingRefreshNow = Date(timeIntervalSince1970: 1_000)
        expect(
            shouldRefreshNodePingHistory(
                lastRefreshAt: nil,
                now: pingRefreshNow,
                minimumInterval: 30
            ) &&
                !shouldRefreshNodePingHistory(
                    lastRefreshAt: pingRefreshNow.addingTimeInterval(-29),
                    now: pingRefreshNow,
                    minimumInterval: 30
                ) &&
                shouldRefreshNodePingHistory(
                    lastRefreshAt: pingRefreshNow.addingTimeInterval(-30),
                    now: pingRefreshNow,
                    minimumInterval: 30
                ) &&
                shouldRefreshNodePingHistory(
                    lastRefreshAt: pingRefreshNow.addingTimeInterval(10),
                    now: pingRefreshNow,
                    minimumInterval: 30
                ),
            "Ping history refreshes must be independently throttled without freezing after a clock correction"
        )

        let serverID = UUID()
        let detailRequest = NodeDetailRequestIdentity(
            generation: 7,
            serverID: serverID,
            nodeUUID: "node-a",
            loadHours: 6,
            pingHours: 24
        )
        expect(
            isCurrentNodeDetailRequest(
                detailRequest,
                generation: 7,
                activeServerID: serverID,
                selectedNodeUUID: "node-a",
                loadHours: 6,
                pingHours: 24
            ),
            "A matching detail request should be accepted"
        )
        expect(
            !isCurrentNodeDetailRequest(
                detailRequest,
                generation: 8,
                activeServerID: serverID,
                selectedNodeUUID: "node-a",
                loadHours: 6,
                pingHours: 24
            ) &&
                !isCurrentNodeDetailRequest(
                    detailRequest,
                    generation: 7,
                    activeServerID: UUID(),
                    selectedNodeUUID: "node-a",
                    loadHours: 6,
                    pingHours: 24
                ) &&
                !isCurrentNodeDetailRequest(
                    detailRequest,
                    generation: 7,
                    activeServerID: serverID,
                    selectedNodeUUID: "node-b",
                    loadHours: 6,
                    pingHours: 24
                ) &&
                !isCurrentNodeDetailRequest(
                    detailRequest,
                    generation: 7,
                    activeServerID: serverID,
                    selectedNodeUUID: "node-a",
                    loadHours: 1,
                    pingHours: 24
                ) &&
                !isCurrentNodeDetailRequest(
                    detailRequest,
                    generation: 7,
                    activeServerID: serverID,
                    selectedNodeUUID: "node-a",
                    loadHours: 6,
                    pingHours: 1
                ),
            "Every detail identity component must invalidate a stale response"
        )
        expect(
            safeTCPConnectionCount(total: 12, udp: 5) == 7 &&
                safeTCPConnectionCount(total: 5, udp: 12) == 0 &&
                safeTCPConnectionCount(total: -1, udp: 1) == 0,
            "TCP derivation must clamp malformed connection counts"
        )
        expect(
            safeTCPConnectionCount(total: Int.max, udp: Int.min) == Int.max &&
                safeTCPConnectionCount(total: Int.min, udp: Int.max) == 0,
            "TCP derivation must not overflow at integer boundaries"
        )
        expect(
            shouldApplyPresence(requestSequence: 12, lastAppliedSequence: 12) &&
                shouldApplyPresence(requestSequence: 13, lastAppliedSequence: 12) &&
                !shouldApplyPresence(requestSequence: 11, lastAppliedSequence: 12),
            "Presence must follow request start order rather than response arrival order"
        )

        let authenticationIdentity = ServerAuthenticationIdentity(
            serverID: serverID,
            normalizedBaseURL: "https://monitor.example",
            authType: "password",
            username: "operator",
            password: "first-secret",
            apiKey: "",
            headers: [AuthenticationHeaderIdentity(name: "X-Access", value: "one")],
            allowsInsecureTLS: false
        )
        expect(
            shouldPersistAuthenticationResult(
                startedWith: authenticationIdentity,
                current: authenticationIdentity
            ) &&
                !shouldPersistAuthenticationResult(
                    startedWith: authenticationIdentity,
                    current: ServerAuthenticationIdentity(
                        serverID: serverID,
                        normalizedBaseURL: "https://other.example",
                        authType: "password",
                        username: "operator",
                        password: "first-secret",
                        apiKey: "",
                        headers: [AuthenticationHeaderIdentity(name: "X-Access", value: "one")],
                        allowsInsecureTLS: false
                    )
                ) &&
                !shouldPersistAuthenticationResult(
                    startedWith: authenticationIdentity,
                    current: ServerAuthenticationIdentity(
                        serverID: serverID,
                        normalizedBaseURL: "https://monitor.example",
                        authType: "password",
                        username: "operator",
                        password: "rotated-secret",
                        apiKey: "",
                        headers: [AuthenticationHeaderIdentity(name: "X-Access", value: "one")],
                        allowsInsecureTLS: false
                    )
                ) &&
                !shouldPersistAuthenticationResult(
                    startedWith: authenticationIdentity,
                    current: nil
                ),
            "A login result must not persist after its endpoint or credential identity changes"
        )
        expect(
            authenticationSnapshotMatchesCurrentIdentity(
                snapshot: authenticationIdentity,
                current: authenticationIdentity
            ) &&
                !authenticationSnapshotMatchesCurrentIdentity(
                    snapshot: authenticationIdentity,
                    current: ServerAuthenticationIdentity(
                        serverID: serverID,
                        normalizedBaseURL: "https://monitor.example",
                        authType: "password",
                        username: "operator",
                        password: "first-secret",
                        apiKey: "",
                        headers: [AuthenticationHeaderIdentity(name: "X-Access", value: "two")],
                        allowsInsecureTLS: false
                    )
                ) &&
                !authenticationSnapshotMatchesCurrentIdentity(
                    snapshot: authenticationIdentity,
                    current: ServerAuthenticationIdentity(
                        serverID: serverID,
                        normalizedBaseURL: "https://monitor.example",
                        authType: "password",
                        username: "operator",
                        password: "first-secret",
                        apiKey: "",
                        headers: [AuthenticationHeaderIdentity(name: "X-Access", value: "one")],
                        allowsInsecureTLS: true
                    )
                ) &&
                !authenticationSnapshotMatchesCurrentIdentity(
                    snapshot: authenticationIdentity,
                    current: nil
                ),
            "A captured client may only reuse tokens from its complete authentication identity"
        )

        expect(
            makeTerminalSensitiveHeader(
                authType: "password",
                requiresTwoFactor: true,
                oneTimeCode: " 123456 "
            ) == TerminalSensitiveHeader(name: "X-2FA-Code", value: "123456"),
            "Sensitive password terminal handshakes must use Komari's exact one-time header"
        )
        expect(
            isReservedTerminalSensitiveHeaderName("X-2FA-Code") &&
                isReservedTerminalSensitiveHeaderName("x-two-factor-code") &&
                !isReservedTerminalSensitiveHeaderName("X-Access-Client-Id"),
            "Persisted custom headers must not override either supported sensitive 2FA header"
        )
        expect(
            makeTerminalSensitiveHeader(
                authType: "apiKey",
                requiresTwoFactor: true,
                oneTimeCode: nil
            ) == nil &&
                makeTerminalSensitiveHeader(
                    authType: "guest",
                    requiresTwoFactor: true,
                    oneTimeCode: "123456"
                ) == nil,
            "API keys are exempt from sensitive 2FA and guests must not receive the header"
        )
        expect(
            makeTerminalSensitiveHeader(
                authType: "password",
                requiresTwoFactor: true,
                oneTimeCode: nil
            ) == nil &&
                !isValidTerminalTwoFactorCode("１２３４５６") &&
                !isValidTerminalTwoFactorCode("12345\n"),
            "A sensitive password handshake must fail closed without a fresh ASCII TOTP"
        )
        expect(
            requiresTerminalSensitiveTwoFactor(
                authType: "password",
                profileRequiresTwoFactor: true,
                passwordAuthenticationWasRejected: false
            ) &&
                !requiresTerminalSensitiveTwoFactor(
                    authType: "password",
                    profileRequiresTwoFactor: false,
                    passwordAuthenticationWasRejected: false
                ) &&
                requiresTerminalSensitiveTwoFactor(
                    authType: "password",
                    profileRequiresTwoFactor: false,
                    passwordAuthenticationWasRejected: true
                ) &&
                !requiresTerminalSensitiveTwoFactor(
                    authType: "apiKey",
                    profileRequiresTwoFactor: true,
                    passwordAuthenticationWasRejected: true
                ),
            "Known and legacy password profiles must prompt without affecting API-key behavior"
        )
        expect(
            isTerminalAuthenticationFailure(statusCode: 401, errorDescription: "") &&
                isTerminalAuthenticationFailure(
                    statusCode: nil,
                    errorDescription: "handshake returned 403 Forbidden"
                ) &&
                isTerminalAuthenticationFailure(
                    statusCode: nil,
                    errorDescription: "2FA code is required"
                ) &&
                !isTerminalAuthenticationFailure(
                    statusCode: 500,
                    errorDescription: "network unavailable"
                ),
            "Only authentication failures should upgrade a legacy password retry to 2FA"
        )
        expect(
            isCurrentTerminalRetryGeneration(4, current: 4) &&
                !isCurrentTerminalRetryGeneration(3, current: 4),
            "A stale retry generation must not replace the latest terminal StateObject"
        )
    }

    private static func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
        guard condition() else { fatalError("NodeMetricsPolicyTests: \(message)") }
    }
}
