import Foundation

struct NodeTrafficUsageTotals: Equatable {
    let upload: Int64
    let download: Int64
}

struct NodeDetailRequestIdentity: Equatable {
    let generation: UInt64
    let serverID: UUID
    let nodeUUID: String
    let loadHours: Int
    let pingHours: Int
}

struct AuthenticationHeaderIdentity: Equatable {
    let name: String
    let value: String
}

/// Excludes mutable session state, but includes every endpoint, TLS, header, and credential input
/// that determines where a freshly issued session token is safe to persist.
struct ServerAuthenticationIdentity: Equatable {
    let serverID: UUID
    let normalizedBaseURL: String
    let authType: String
    let username: String
    let password: String
    let apiKey: String
    let headers: [AuthenticationHeaderIdentity]
    let allowsInsecureTLS: Bool
}

/// IP addresses hidden from the UI must not remain discoverable through node search.
func nodeIPAddressMatchesSearch(
    query: String,
    ipv4: String,
    ipv6: String,
    maskIpEnabled: Bool
) -> Bool {
    guard !maskIpEnabled, !query.isEmpty else { return false }
    return ipv4.localizedCaseInsensitiveContains(query) ||
        ipv6.localizedCaseInsensitiveContains(query)
}

/// Komari retains the last cumulative counters for offline nodes, so usage includes all nodes.
func aggregateNodeTrafficUsage(
    _ counters: [(upload: Int64, download: Int64)]
) -> NodeTrafficUsageTotals {
    NodeTrafficUsageTotals(
        upload: saturatingNonNegativeSum(counters.map { $0.upload }),
        download: saturatingNonNegativeSum(counters.map { $0.download })
    )
}

/// Remote metric values must not wrap, trap, or make aggregate UI values negative.
func saturatingNonNegativeSum<S: Sequence>(_ values: S) -> Int64 where S.Element == Int64 {
    values.reduce(0) { total, rawValue in
        let value = max(rawValue, 0)
        let (sum, overflow) = total.addingReportingOverflow(value)
        return overflow ? Int64.max : sum
    }
}

func trafficLimitUsedBytes(upload: Int64, download: Int64, type: String) -> Int64 {
    let safeUpload = max(upload, 0)
    let safeDownload = max(download, 0)
    switch type.lowercased() {
    case "max": return max(safeUpload, safeDownload)
    case "min": return min(safeUpload, safeDownload)
    case "up": return safeUpload
    case "down": return safeDownload
    default: return saturatingNonNegativeSum([safeUpload, safeDownload])
    }
}

/// A detail response is only safe to publish while every part of its request identity still
/// matches the visible selection. This keeps responses for old servers, nodes, and chart ranges
/// from overwriting the current screen.
func isCurrentNodeDetailRequest(
    _ request: NodeDetailRequestIdentity,
    generation: UInt64,
    activeServerID: UUID?,
    selectedNodeUUID: String?,
    loadHours: Int,
    pingHours: Int
) -> Bool {
    request.generation == generation &&
        request.serverID == activeServerID &&
        request.nodeUUID == selectedNodeUUID &&
        request.loadHours == loadHours &&
        request.pingHours == pingHours
}

/// Komari's legacy status shape reports a combined connection count and a UDP count. Clamp both
/// untrusted values before subtracting, and subtract only in the order that cannot overflow.
func safeTCPConnectionCount(total: Int?, udp: Int?) -> Int {
    let safeTotal = max(total ?? 0, 0)
    let safeUDP = max(udp ?? 0, 0)
    return safeTotal > safeUDP ? safeTotal - safeUDP : 0
}

/// Full-node and status requests may finish out of order. Presence belongs to whichever request
/// started most recently, while timestamp reconciliation remains responsible for sampled metrics.
func shouldApplyPresence(requestSequence: UInt64, lastAppliedSequence: UInt64) -> Bool {
    requestSequence >= lastAppliedSequence
}

func shouldPersistAuthenticationResult(
    startedWith: ServerAuthenticationIdentity,
    current: ServerAuthenticationIdentity?
) -> Bool {
    authenticationSnapshotMatchesCurrentIdentity(snapshot: startedWith, current: current)
}

/// Tokens are bearer credentials scoped to the complete server authentication identity. A view
/// created from an older profile must never borrow the current profile's token merely because the
/// database row still has the same UUID.
func authenticationSnapshotMatchesCurrentIdentity(
    snapshot: ServerAuthenticationIdentity,
    current: ServerAuthenticationIdentity?
) -> Bool {
    snapshot == current
}

struct TerminalSensitiveHeader: Equatable {
    let name: String
    let value: String
}

func isReservedTerminalSensitiveHeaderName(_ name: String) -> Bool {
    let normalized = name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    return normalized == "x-2fa-code" || normalized == "x-two-factor-code"
}

/// Mirrors Komari VerifySensitive2FACore: API keys are exempt, while password accounts known to
/// use 2FA require a fresh code. A rejected legacy password profile is upgraded for its next retry.
func requiresTerminalSensitiveTwoFactor(
    authType: String,
    profileRequiresTwoFactor: Bool,
    passwordAuthenticationWasRejected: Bool
) -> Bool {
    authType == "password" &&
        (profileRequiresTwoFactor || passwordAuthenticationWasRejected)
}

func isValidTerminalTwoFactorCode(_ code: String) -> Bool {
    let bytes = Array(code.utf8)
    return bytes.count == 6 && bytes.allSatisfy { (48...57).contains($0) }
}

func makeTerminalSensitiveHeader(
    authType: String,
    requiresTwoFactor: Bool,
    oneTimeCode: String?
) -> TerminalSensitiveHeader? {
    guard authType == "password", requiresTwoFactor else { return nil }
    let code = oneTimeCode?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    guard isValidTerminalTwoFactorCode(code) else { return nil }
    return TerminalSensitiveHeader(name: "X-2FA-Code", value: code)
}

func isTerminalAuthenticationFailure(statusCode: Int?, errorDescription: String) -> Bool {
    if statusCode == 401 || statusCode == 403 { return true }
    let normalized = errorDescription.lowercased()
    return normalized.contains("401") || normalized.contains("403") ||
        normalized.contains("unauthorized") || normalized.contains("forbidden") ||
        normalized.contains("2fa") || normalized.contains("two-factor")
}

/// A retry is current only while it still owns the wrapper's latest generation. The wrapper also
/// replaces its StateObject identity for every generation, even when the bearer token is unchanged.
func isCurrentTerminalRetryGeneration(_ attempt: UInt64, current: UInt64) -> Bool {
    attempt == current
}
