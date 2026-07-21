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
