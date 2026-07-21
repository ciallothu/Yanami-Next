import Foundation

struct NodeTrafficUsageTotals: Equatable {
    let upload: Int64
    let download: Int64
}

enum TrafficLimitAccountingMode: Equatable {
    case total
    case maximumDirection
    case minimumDirection
    case upload
    case download
}

struct PingLatencySummary: Equatable {
    let sampleCount: Int
    let successfulSampleCount: Int
    let lostSampleCount: Int
    let latestMilliseconds: Double?
    let averageMilliseconds: Double?
    let minimumMilliseconds: Double?
    let maximumMilliseconds: Double?
    let packetLossPercent: Double?
}

struct ResolvedPingLatencyMetrics: Equatable {
    let sampleCount: Int
    let latestMilliseconds: Double?
    let averageMilliseconds: Double?
    let packetLossPercent: Double?
    /// True only when Komari returned a valid full-window loss statistic with successful samples.
    let hasReportedSuccessfulSamples: Bool
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

func trafficLimitAccountingMode(type: String) -> TrafficLimitAccountingMode {
    switch type.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
    case "max": return .maximumDirection
    case "min": return .minimumDirection
    case "up": return .upload
    case "down": return .download
    default: return .total
    }
}

func trafficLimitUsedBytes(upload: Int64, download: Int64, type: String) -> Int64 {
    let safeUpload = max(upload, 0)
    let safeDownload = max(download, 0)
    switch trafficLimitAccountingMode(type: type) {
    case .maximumDirection: return max(safeUpload, safeDownload)
    case .minimumDirection: return min(safeUpload, safeDownload)
    case .upload: return safeUpload
    case .download: return safeDownload
    case .total: return saturatingNonNegativeSum([safeUpload, safeDownload])
    }
}

/// Komari encodes a failed ping as a negative record value. Only finite values are considered;
/// non-finite transport data is ignored rather than leaking invalid chart coordinates or labels.
/// A missing packet-loss value means there were no valid records in the selected window, which is
/// deliberately different from a measured 0% loss rate.
func summarizePingLatency(values: [Double]) -> PingLatencySummary {
    let samples = values.filter(\.isFinite)
    var successfulSampleCount = 0
    var lostSampleCount = 0
    var latest: Double?
    var average = 0.0
    var minimum: Double?
    var maximum: Double?

    for value in samples {
        guard value >= 0 else {
            lostSampleCount += 1
            continue
        }
        successfulSampleCount += 1
        // Incremental averaging avoids overflowing an intermediate sum for hostile remote input.
        average += (value - average) / Double(successfulSampleCount)
        minimum = min(minimum ?? value, value)
        maximum = max(maximum ?? value, value)
        latest = value
    }

    let packetLossPercent: Double?
    if samples.isEmpty {
        packetLossPercent = nil
    } else {
        packetLossPercent = Double(lostSampleCount) / Double(samples.count) * 100
    }

    return PingLatencySummary(
        sampleCount: samples.count,
        successfulSampleCount: successfulSampleCount,
        lostSampleCount: lostSampleCount,
        latestMilliseconds: latest,
        averageMilliseconds: successfulSampleCount > 0 ? average : nil,
        minimumMilliseconds: minimum,
        maximumMilliseconds: maximum,
        packetLossPercent: packetLossPercent
    )
}

/// Assigns each successful latency sample to a contiguous series. Loss and invalid samples return
/// `nil` and start a new series, so chart renderers cannot visually bridge a packet-loss gap.
func pingLatencySegmentIdentifiers(values: [Double]) -> [Int?] {
    var nextSegment = 0
    var activeSegment: Int?
    return values.map { value in
        guard value.isFinite, value >= 0 else {
            activeSegment = nil
            return nil
        }
        if activeSegment == nil {
            activeSegment = nextSegment
            nextSegment += 1
        }
        return activeSegment
    }
}

func shouldRefreshNodePingHistory(
    lastRefreshAt: Date?,
    now: Date,
    minimumInterval: TimeInterval
) -> Bool {
    guard let lastRefreshAt else { return true }
    let safeInterval = minimumInterval.isFinite ? max(minimumInterval, 0) : 0
    let elapsed = now.timeIntervalSince(lastRefreshAt)
    // A wall-clock correction must not freeze latency refreshes indefinitely.
    return elapsed < 0 || elapsed >= safeInterval
}

/// Komari computes task statistics before it downsamples chart records. Prefer that complete-window
/// result when it is present, and use the returned records only as a compatibility fallback for an
/// older server. This prevents a visually sampled history from distorting the displayed loss rate.
func resolvePingLatencyMetrics(
    reportedSampleCount: Int,
    reportedLatestMilliseconds: Double?,
    reportedAverageMilliseconds: Double?,
    reportedPacketLossPercent: Double?,
    recordValues: [Double]
) -> ResolvedPingLatencyMetrics {
    let summary = summarizePingLatency(values: recordValues)
    let safeReportedSampleCount = max(reportedSampleCount, 0)
    let reportedLoss: Double? = {
        guard safeReportedSampleCount > 0,
              let value = reportedPacketLossPercent,
              value.isFinite,
              (0...100).contains(value) else { return nil }
        return value
    }()
    let hasValidatedReportedWindow = reportedLoss != nil
    let hasReportedSuccess = reportedLoss.map { $0 < 100 } ?? false

    let latest: Double? = {
        if reportedLoss == 100 { return nil }
        guard hasReportedSuccess,
              let value = reportedLatestMilliseconds,
              value.isFinite,
              value >= 0 else { return summary.latestMilliseconds }
        return value
    }()
    let average: Double? = {
        if reportedLoss == 100 { return nil }
        guard hasReportedSuccess,
              let value = reportedAverageMilliseconds,
              value.isFinite,
              value >= 0 else { return summary.averageMilliseconds }
        return value
    }()

    return ResolvedPingLatencyMetrics(
        sampleCount: hasValidatedReportedWindow ? safeReportedSampleCount : summary.sampleCount,
        latestMilliseconds: latest,
        averageMilliseconds: average,
        packetLossPercent: reportedLoss ?? summary.packetLossPercent,
        hasReportedSuccessfulSamples: hasReportedSuccess
    )
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
