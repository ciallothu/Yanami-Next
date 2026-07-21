import Foundation

enum AppMetadata {
    static var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0"
    }

    static var userAgent: String { "Yanami-Next-iPhone/\(version)" }
}

enum AuthType: String, CaseIterable, Codable, Identifiable {
    case password
    case apiKey
    case guest

    var id: String { rawValue }

    var title: String {
        switch self {
        case .password:
            return "Password"
        case .apiKey:
            return "API Key"
        case .guest:
            return "Guest"
        }
    }
}

enum StatusFilter: String, CaseIterable, Codable, Identifiable {
    case all
    case online
    case offline

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all:
            return "All"
        case .online:
            return "Online"
        case .offline:
            return "Offline"
        }
    }
}

struct CustomHeader: Codable, Equatable, Identifiable {
    var id = UUID()
    var name: String
    var value: String
}

struct ServerProfile: Codable, Equatable, Identifiable {
    var id = UUID()
    var name = "My Komari"
    var baseURL = "https://"
    var username = ""
    var password = ""
    var apiKey = ""
    var sessionToken = ""
    var requires2FA = false
    var authType = AuthType.password
    var customHeaders: [CustomHeader] = []
    var allowInsecureTLS = false
    var createdAt = Date()

    enum CodingKeys: String, CodingKey {
        case id, name, baseURL, username, password, apiKey, sessionToken, requires2FA
        case authType, customHeaders, allowInsecureTLS, createdAt
    }

    init() {}

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
        name = try container.decodeIfPresent(String.self, forKey: .name) ?? "My Komari"
        baseURL = try container.decodeIfPresent(String.self, forKey: .baseURL) ?? "https://"
        username = try container.decodeIfPresent(String.self, forKey: .username) ?? ""
        password = try container.decodeIfPresent(String.self, forKey: .password) ?? ""
        apiKey = try container.decodeIfPresent(String.self, forKey: .apiKey) ?? ""
        sessionToken = try container.decodeIfPresent(String.self, forKey: .sessionToken) ?? ""
        requires2FA = try container.decodeIfPresent(Bool.self, forKey: .requires2FA) ?? false
        authType = try container.decodeIfPresent(AuthType.self, forKey: .authType) ?? .password
        customHeaders = try container.decodeIfPresent([CustomHeader].self, forKey: .customHeaders) ?? []
        allowInsecureTLS = try container.decodeIfPresent(Bool.self, forKey: .allowInsecureTLS) ?? false
        createdAt = try container.decodeIfPresent(Date.self, forKey: .createdAt) ?? Date()
    }

    var normalizedBaseURL: String {
        baseURL.trimmingCharacters(in: .whitespacesAndNewlines).trimmedTrailingSlash()
    }

    var validatedBaseURL: URL? {
        let value = normalizedBaseURL
        guard value.unicodeScalars.allSatisfy({ $0.value >= 0x20 && $0.value != 0x7f }),
              let components = URLComponents(string: value),
              let scheme = components.scheme?.lowercased(),
              scheme == "https",
              components.host?.isEmpty == false,
              components.user == nil,
              components.password == nil,
              components.query == nil,
              components.fragment == nil else {
            return nil
        }
        return components.url
    }

    var sanitizedCustomHeaders: [CustomHeader] {
        customHeaders
            .map {
                CustomHeader(
                    id: $0.id,
                    name: $0.name.trimmingCharacters(in: .whitespacesAndNewlines),
                    value: $0.value.trimmingCharacters(in: .whitespacesAndNewlines)
                )
            }
            .filter {
                !$0.name.isEmpty &&
                !$0.value.isEmpty &&
                Self.isAllowedCustomHeader($0.name) &&
                $0.value.utf8.count <= 8_192 &&
                !$0.value.contains(where: { $0 == "\r" || $0 == "\n" || $0 == "\0" })
            }
    }

    private static func isAllowedCustomHeader(_ name: String) -> Bool {
        let scalars = name.unicodeScalars
        guard (1...128).contains(scalars.count), scalars.allSatisfy({ scalar in
            let value = scalar.value
            return (0x30...0x39).contains(value) ||
                (0x41...0x5A).contains(value) ||
                (0x61...0x7A).contains(value) ||
                "!#$%&'*+-.^_`|~".unicodeScalars.contains(scalar)
        }) else {
            return false
        }
        return !Set([
            "authorization", "cookie", "host", "content-length", "connection", "upgrade", "origin"
        ]).contains(name.lowercased())
    }
}

struct TerminalSnippet: Codable, Equatable, Identifiable {
    var id = UUID()
    var title: String
    var content: String
    var appendEnter: Bool = true
}

struct AppSettings: Codable, Equatable {
    var autoRefreshEnabled = true
    var refreshIntervalSeconds = 2.0
    var terminalFontSize: Int = 14
    var snippets: [TerminalSnippet] = []
    var maskIpEnabled = false
    var autoEnterNodeList = false
    var chartAnimationEnabled = true
    var biometricEnabled = false
    var darkMode = "system"
    var language = "system"
    var fontScale = 1.0

    enum CodingKeys: String, CodingKey {
        case autoRefreshEnabled, refreshIntervalSeconds, terminalFontSize, snippets, maskIpEnabled
        case autoEnterNodeList, chartAnimationEnabled, biometricEnabled, darkMode, language, fontScale
    }

    init() {}

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        autoRefreshEnabled = try container.decodeIfPresent(Bool.self, forKey: .autoRefreshEnabled) ?? true
        refreshIntervalSeconds = try container.decodeIfPresent(Double.self, forKey: .refreshIntervalSeconds) ?? 2.0
        terminalFontSize = try container.decodeIfPresent(Int.self, forKey: .terminalFontSize) ?? 14
        snippets = try container.decodeIfPresent([TerminalSnippet].self, forKey: .snippets) ?? []
        maskIpEnabled = try container.decodeIfPresent(Bool.self, forKey: .maskIpEnabled) ?? false
        autoEnterNodeList = try container.decodeIfPresent(Bool.self, forKey: .autoEnterNodeList) ?? false
        chartAnimationEnabled = try container.decodeIfPresent(Bool.self, forKey: .chartAnimationEnabled) ?? true
        biometricEnabled = try container.decodeIfPresent(Bool.self, forKey: .biometricEnabled) ?? false
        darkMode = try container.decodeIfPresent(String.self, forKey: .darkMode) ?? "system"
        language = try container.decodeIfPresent(String.self, forKey: .language) ?? "system"
        fontScale = try container.decodeIfPresent(Double.self, forKey: .fontScale) ?? 1.0
    }
}

struct PersistedAppState: Codable {
    var servers: [ServerProfile]
    var activeServerId: UUID?
    var settings: AppSettings
}

struct KomariNode: Identifiable, Equatable {
    var id: String { uuid }

    let uuid: String
    var name: String
    var region: String
    var group: String
    var ipv4: String
    var ipv6: String
    var isOnline: Bool
    /// Timestamp reported by Komari for the latest agent sample.
    var statusTime: String
    var cpuUsage: Double
    var memUsed: Int64
    var memTotal: Int64
    var swapUsed: Int64
    var swapTotal: Int64
    var diskUsed: Int64
    var diskTotal: Int64
    var netIn: Int64
    var netOut: Int64
    var netTotalUp: Int64
    var netTotalDown: Int64
    /// Bytes transferred during the latest agent sampling interval.
    var trafficUp: Int64
    var trafficDown: Int64
    var uptime: Int64
    var os: String
    var cpuName: String
    var cpuCores: Int
    var weight: Int
    var load1: Double
    var load5: Double
    var load15: Double
    var process: Int
    var connectionsTcp: Int
    var connectionsUdp: Int
    var kernelVersion: String
    var virtualization: String
    var arch: String
    var gpuName: String
    var trafficLimit: Int64
    var trafficLimitType: String
    var expiredAt: String?

    var statusText: String { isOnline ? "Online" : "Offline" }

    var trafficLimitUsage: (used: Int64, fraction: Double)? {
        guard trafficLimit > 0 else { return nil }
        let kind = trafficLimitType.lowercased()
        let used: Int64
        switch kind {
        case "max": used = max(netTotalUp, netTotalDown)
        case "min": used = min(netTotalUp, netTotalDown)
        case "up": used = netTotalUp
        case "down": used = netTotalDown
        default: used = netTotalUp + netTotalDown
        }
        return (used, Double(used) / Double(trafficLimit))
    }
}

struct LoadRecord: Identifiable, Equatable {
    var id = UUID()
    let time: String
    let cpu: Double
    let ramPercent: Double
    let diskPercent: Double
    let netIn: Int64
    let netOut: Int64
    let netTotalUp: Int64
    let netTotalDown: Int64
    /// Bytes transferred during this Komari sampling interval.
    let trafficUp: Int64
    let trafficDown: Int64
    let load: Double
    let process: Int
    let connections: Int
    let connectionsUdp: Int
}

/// Komari agents may reset cumulative counters after a restart.
func resetAwareTrafficDelta(previous: Int64?, current: Int64?) -> Int64 {
    guard let previous, let current, previous >= 0, current >= 0 else { return 0 }
    return current >= previous ? current - previous : current
}

/// Returns true only when an incoming latest-status sample can safely advance the stored state.
/// Timestamped samples must be strictly newer. Legacy undated samples need monotonic evidence so
/// duplicate refreshes cannot erase the previously calculated traffic delta.
func shouldAcceptStatusSample(
    current: KomariNode,
    incomingTime: String?,
    incomingUptime: Int64?,
    incomingTotalUp: Int64?,
    incomingTotalDown: Int64?
) -> Bool {
    let storedTime = current.statusTime.trimmingCharacters(in: .whitespacesAndNewlines)
    let candidateTime = (incomingTime ?? "").trimmingCharacters(in: .whitespacesAndNewlines)

    if !candidateTime.isEmpty {
        if storedTime.isEmpty { return true }
        if candidateTime == storedTime { return false }
        guard let storedDate = parseStatusDate(storedTime),
              let candidateDate = parseStatusDate(candidateTime) else {
            return false
        }
        return candidateDate > storedDate
    }
    if !storedTime.isEmpty { return false }

    let hasBaseline = current.isOnline ||
        current.uptime != 0 ||
        current.netTotalUp != 0 ||
        current.netTotalDown != 0 ||
        current.netIn != 0 ||
        current.netOut != 0 ||
        current.memUsed != 0 ||
        current.diskUsed != 0 ||
        current.cpuUsage != 0
    if !hasBaseline { return true }

    if let incomingUptime, incomingUptime > current.uptime { return true }
    let uptimeDidNotRegress = incomingUptime.map { $0 == current.uptime } ?? true
    guard uptimeDidNotRegress,
          let incomingTotalUp,
          let incomingTotalDown,
          incomingTotalUp >= current.netTotalUp,
          incomingTotalDown >= current.netTotalDown else {
        return false
    }
    return incomingTotalUp > current.netTotalUp || incomingTotalDown > current.netTotalDown
}

private func parseStatusDate(_ value: String) -> Date? {
    let fractionalFormatter = ISO8601DateFormatter()
    fractionalFormatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let date = fractionalFormatter.date(from: value) { return date }

    let internetFormatter = ISO8601DateFormatter()
    internetFormatter.formatOptions = [.withInternetDateTime]
    if let date = internetFormatter.date(from: value) { return date }

    let localFormatter = DateFormatter()
    localFormatter.locale = Locale(identifier: "en_US_POSIX")
    localFormatter.calendar = Calendar(identifier: .gregorian)
    localFormatter.timeZone = TimeZone(secondsFromGMT: 0)
    localFormatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
    if let date = localFormatter.date(from: value) { return date }

    guard let numeric = Double(value), numeric.isFinite else { return nil }
    let seconds = abs(numeric) < 100_000_000_000 ? numeric : numeric / 1_000
    return Date(timeIntervalSince1970: seconds)
}

struct PingTask: Identifiable, Equatable {
    let id: Int
    let name: String
    let interval: Int
    let latest: Double
    let min: Double
    let max: Double
    let avg: Double
    let loss: Double
    let p50: Double
    let p99: Double
}

struct PingRecord: Identifiable, Equatable {
    var id = UUID()
    let taskId: Int
    let taskName: String
    let time: String
    let value: Double
}

struct NodeDetailState: Equatable {
    var node: KomariNode?
    var loadRecords: [LoadRecord] = []
    var pingTasks: [PingTask] = []
    var pingRecords: [PingRecord] = []
    var loadHours = 0
    var pingHours = 1
    var isLoading = false
    var error: String?
}

struct ManagedClient: Codable, Equatable, Identifiable {
    let uuid: String
    var name: String?
    var weight: Int?
    
    var id: String { uuid }
}

struct AdminPingTask: Codable, Equatable, Identifiable {
    let id: Int
    var name: String
    var type: String
    var target: String
    var interval: Int
    var weight: Int?
    
    enum CodingKeys: String, CodingKey {
        case id, name, type, target, interval, weight
    }
}
