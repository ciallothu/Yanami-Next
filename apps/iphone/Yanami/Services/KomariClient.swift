import Foundation

struct KomariClient {
    private var profile: ServerProfile

    init(profile: ServerProfile) {
        self.profile = profile
    }

    func login(twoFaCode: String? = nil) async throws -> String {
        guard !profile.username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw KomariClientError.invalidConfiguration("Username is required")
        }
        guard !profile.password.isEmpty else {
            throw KomariClientError.invalidConfiguration("Password is required")
        }

        var body: [String: Any] = [
            "username": profile.username,
            "password": profile.password
        ]
        if let twoFaCode, !twoFaCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            body["2fa_code"] = twoFaCode
        }

        let request = try makeRequest(path: "/api/login", method: "POST", jsonBody: body)
        let (data, response) = try await data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw KomariClientError.invalidResponse
        }
        // Komari reports a missing/invalid TOTP as a 401 JSON envelope. Parse that trusted,
        // bounded shape before converting the response into a generic authentication failure so
        // callers can ask for a code and retry the same login exactly once per user submission.
        if httpResponse.statusCode == 401,
           let envelope = try? Self.decoder.decode(AdminEnvelope.self, from: data),
           let message = envelope.message {
            switch classifyKomariLoginTwoFactorMessage(message) {
            case .required:
                throw KomariClientError.requires2FA
            case .invalidCode:
                throw KomariClientError.invalidTwoFactorCode
            case .none:
                break
            }
        }
        guard (200...299).contains(httpResponse.statusCode) else {
            throw KomariClientError.httpStatus(httpResponse.statusCode, path: "/api/login")
        }
        if let token = extractSessionToken(from: httpResponse) {
            return token
        }
        let text = String(data: data, encoding: .utf8) ?? ""
        if text.localizedCaseInsensitiveContains("2fa") || text.localizedCaseInsensitiveContains("totp") {
            throw KomariClientError.requires2FA
        }
        throw KomariClientError.invalidConfiguration("session_token was not returned")
    }

    func getVersion(token: String) async throws -> String {
        let result: VersionResult = try await rpc(
            method: "common:getVersion",
            token: token,
            responseType: VersionResult.self
        )
        return result.version
    }

    func getNodes(token: String) async throws -> [KomariNode] {
        async let infoTask: [String: NodeInfoPayload] = rpc(
            method: "common:getNodes",
            token: token,
            responseType: [String: NodeInfoPayload].self
        )
        async let statusTask: [String: NodeStatusPayload] = rpc(
            method: "common:getNodesLatestStatus",
            token: token,
            responseType: [String: NodeStatusPayload].self
        )

        let (infos, statuses) = try await (infoTask, statusTask)
        return mergeNodes(infos: infos, statuses: statuses)
    }

    func getLatestStatuses(token: String) async throws -> [String: NodeStatusPayload] {
        try await rpc(
            method: "common:getNodesLatestStatus",
            token: token,
            responseType: [String: NodeStatusPayload].self
        )
    }

    func getLoadRecords(token: String, uuid: String, hours: Int) async throws -> [LoadRecord] {
        let uuid = try normalizedUUID(uuid)
        let result: LoadRecordsPayload = try await rpc(
            method: "common:getRecords",
            params: ["uuid": uuid, "type": "load", "hours": hours],
            token: token,
            responseType: LoadRecordsPayload.self
        )
        let records = (result.records.matching(uuid: uuid) ?? []).sorted { $0.time < $1.time }
        var previousTotalUp: Int64?
        var previousTotalDown: Int64?
        return records.map { record in
            let trafficUp = max(
                record.trafficUp ?? resetAwareTrafficDelta(previous: previousTotalUp, current: record.netTotalUp),
                0
            )
            let trafficDown = max(
                record.trafficDown ?? resetAwareTrafficDelta(previous: previousTotalDown, current: record.netTotalDown),
                0
            )
            previousTotalUp = record.netTotalUp
            previousTotalDown = record.netTotalDown
            return record.toDomain(trafficUp: trafficUp, trafficDown: trafficDown)
        }
    }

    func getPingRecords(token: String, uuid: String, hours: Int) async throws -> ([PingTask], [PingRecord]) {
        let uuid = try normalizedUUID(uuid)
        let result: PingRecordsPayload = try await rpc(
            method: "common:getRecords",
            params: ["uuid": uuid, "type": "ping", "hours": hours],
            token: token,
            responseType: PingRecordsPayload.self
        )
        var seenTaskIDs = Set<Int>()
        let tasks = result.tasks
            .map { $0.toDomain() }
            .filter { seenTaskIDs.insert($0.id).inserted }
        let names = tasks.reduce(into: [Int: String]()) { names, task in
            names[task.id] = task.name
        }
        let records = result.records
            .filter { $0.client == uuid }
            .sorted {
                if $0.time == $1.time { return $0.taskId < $1.taskId }
                return $0.time < $1.time
            }
            .compactMap { $0.toDomain(taskName: names[$0.taskId] ?? "Task \($0.taskId)") }
        return (tasks, records)
    }

    func getRecentStatus(token: String, uuid: String) async throws -> [LoadRecord] {
        let uuid = try normalizedUUID(uuid)
        let encodedUuid = uuid.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? uuid
        let request = try makeRequest(path: "/api/recent/\(encodedUuid)", method: "GET", token: token)
        let (data, response) = try await data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw KomariClientError.invalidResponse
        }
        guard (200...299).contains(httpResponse.statusCode) else {
            throw KomariClientError.httpStatus(httpResponse.statusCode, path: "/api/recent/\(encodedUuid)")
        }
        let payload = try Self.decoder.decode(RecentStatusResponse.self, from: data)
        if payload.status == "error" {
            throw KomariClientError.rpc(
                Self.sanitizedServerMessage(payload.message, fallback: "Recent status API error")
            )
        }
        let samples = (payload.data ?? []).sorted {
            ($0.updatedAt ?? "") < ($1.updatedAt ?? "")
        }
        var previousTotalUp: Int64?
        var previousTotalDown: Int64?
        return samples.map { sample in
            let trafficUp = resetAwareTrafficDelta(
                previous: previousTotalUp,
                current: sample.network?.totalUp
            )
            let trafficDown = resetAwareTrafficDelta(
                previous: previousTotalDown,
                current: sample.network?.totalDown
            )
            previousTotalUp = sample.network?.totalUp
            previousTotalDown = sample.network?.totalDown
            return sample.toDomain(trafficUp: trafficUp, trafficDown: trafficDown)
        }
    }

    // MARK: - Admin Client API

    func getClients(token: String) async throws -> [ManagedClient] {
        let request = try makeRequest(path: "/api/admin/client/list", method: "GET", token: token)
        let (data, response) = try await data(for: request)
        try validateAdminResponse(data: data, response: response, path: "/api/admin/client/list")
        return try decodeAdminPayload([ManagedClient].self, from: data)
    }
    
    func deleteClient(token: String, uuid: String) async throws {
        let uuid = try normalizedUUID(uuid)
        let request = try makeRequest(path: "/api/admin/client/\(uuid)/remove", method: "POST", token: token)
        let (data, response) = try await data(for: request)
        try validateAdminResponse(data: data, response: response, path: "/api/admin/client/\(uuid)/remove")
    }
    
    func getClientToken(token: String, uuid: String) async throws -> String {
        let uuid = try normalizedUUID(uuid)
        let request = try makeRequest(path: "/api/admin/client/\(uuid)/token", method: "GET", token: token)
        let (data, response) = try await data(for: request)
        try validateAdminResponse(data: data, response: response, path: "/api/admin/client/\(uuid)/token")
        let payload = try decodeAdminPayload([String: String].self, from: data)
        return payload["token"] ?? ""
    }

    // MARK: - Admin Ping API

    func getPingTasks(token: String) async throws -> [AdminPingTask] {
        let request = try makeRequest(path: "/api/admin/ping/", method: "GET", token: token)
        let (data, response) = try await data(for: request)
        try validateAdminResponse(data: data, response: response, path: "/api/admin/ping/")
        return try decodeAdminPayload([AdminPingTask].self, from: data)
    }

    func deletePingTask(token: String, ids: [Int]) async throws {
        let request = try makeRequest(path: "/api/admin/ping/delete", method: "POST", jsonBody: ["id": ids], token: token)
        let (data, response) = try await data(for: request)
        try validateAdminResponse(data: data, response: response, path: "/api/admin/ping/delete")
    }
    
    private func validateAdminResponse(data: Data, response: URLResponse, path: String) throws {
        guard let httpResponse = response as? HTTPURLResponse else {
            throw KomariClientError.invalidResponse
        }
        // Preserve authentication failures as typed errors so callers can safely
        // refresh an expired password session without inspecting server messages.
        if httpResponse.statusCode == 401 || httpResponse.statusCode == 403 {
            throw KomariClientError.httpStatus(httpResponse.statusCode, path: path)
        }
        if (200...299).contains(httpResponse.statusCode),
           let env = try? Self.decoder.decode(AdminEnvelope.self, from: data),
           let status = env.status,
           status.localizedCaseInsensitiveCompare("error") == .orderedSame {
            throw KomariClientError.rpc(
                Self.sanitizedServerMessage(env.message, fallback: "Admin API error")
            )
        }
        guard (200...299).contains(httpResponse.statusCode) else {
            if let env = try? Self.decoder.decode(AdminEnvelope.self, from: data), let msg = env.message {
                throw KomariClientError.rpc(
                    Self.sanitizedServerMessage(msg, fallback: "Admin API error")
                )
            }
            throw KomariClientError.httpStatus(httpResponse.statusCode, path: path)
        }
    }

    private func decodeAdminPayload<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        if let raw = try? Self.decoder.decode(T.self, from: data) {
            return raw
        }
        if let envelope = try? Self.decoder.decode(AdminDataEnvelope<T>.self, from: data),
           let payload = envelope.data {
            return payload
        }
        throw KomariClientError.invalidResponse
    }

    func mergeNodes(infos: [String: NodeInfoPayload], statuses: [String: NodeStatusPayload]) -> [KomariNode] {
        infos.map { uuid, info in
            let status = statuses[uuid]
            let reportedTime = (status?.time ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let statusTime = validatedStatusTimeOrEmpty(reportedTime)
            let sampledStatus = reportedTime.isEmpty || !statusTime.isEmpty ? status : nil
            return KomariNode(
                uuid: uuid,
                name: (info.name ?? "").isEmpty ? uuid : info.name ?? uuid,
                region: info.region ?? "",
                group: info.group ?? "",
                ipv4: info.ipv4 ?? "",
                ipv6: info.ipv6 ?? "",
                isOnline: status?.online ?? false,
                statusTime: statusTime,
                cpuUsage: sampledStatus?.cpu ?? 0,
                memUsed: sampledStatus?.ram ?? 0,
                memTotal: nonZero(sampledStatus?.ramTotal, fallback: info.memTotal),
                swapUsed: sampledStatus?.swap ?? 0,
                swapTotal: nonZero(sampledStatus?.swapTotal, fallback: info.swapTotal),
                diskUsed: sampledStatus?.disk ?? 0,
                diskTotal: nonZero(sampledStatus?.diskTotal, fallback: info.diskTotal),
                netIn: sampledStatus?.netIn ?? 0,
                netOut: sampledStatus?.netOut ?? 0,
                netTotalUp: sampledStatus?.netTotalUp ?? 0,
                netTotalDown: sampledStatus?.netTotalDown ?? 0,
                trafficUp: 0,
                trafficDown: 0,
                uptime: sampledStatus?.uptime ?? 0,
                os: info.os ?? "",
                cpuName: info.cpuName ?? "",
                cpuCores: info.cpuCores ?? 0,
                weight: info.weight ?? 0,
                load1: sampledStatus?.load ?? 0,
                load5: sampledStatus?.load5 ?? 0,
                load15: sampledStatus?.load15 ?? 0,
                process: sampledStatus?.process ?? 0,
                connectionsTcp: safeTCPConnectionCount(
                    total: sampledStatus?.connections,
                    udp: sampledStatus?.connectionsUdp
                ),
                connectionsUdp: max(sampledStatus?.connectionsUdp ?? 0, 0),
                kernelVersion: info.kernelVersion ?? "",
                virtualization: info.virtualization ?? "",
                arch: info.arch ?? "",
                gpuName: info.gpuName ?? "",
                trafficLimit: info.trafficLimit ?? 0,
                trafficLimitType: info.trafficLimitType ?? "",
                expiredAt: info.expiredAt
            )
        }
        .sorted {
            if $0.isOnline != $1.isOnline {
                return $0.isOnline && !$1.isOnline
            }
            if $0.weight != $1.weight {
                return $0.weight < $1.weight
            }
            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    func mergeStatuses(nodes: [KomariNode], statuses: [String: NodeStatusPayload]) -> [KomariNode] {
        nodes.map { node in
            guard let status = statuses[node.uuid] else {
                var offline = node
                offline.isOnline = false
                return offline
            }
            let incomingTime = (status.time ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard shouldAcceptStatusSample(
                current: node,
                incomingTime: incomingTime,
                incomingUptime: status.uptime,
                incomingTotalUp: status.netTotalUp,
                incomingTotalDown: status.netTotalDown
            ) else {
                // Komari derives `online` from the live connection set independently of the
                // report timestamp. Preserve every sampled metric, but keep presence current.
                var presenceUpdated = node
                if let online = status.online {
                    presenceUpdated.isOnline = online
                }
                return presenceUpdated
            }

            var updated = node
            updated.isOnline = status.online ?? false
            updated.statusTime = incomingTime.isEmpty ? node.statusTime : incomingTime
            updated.cpuUsage = status.cpu ?? 0
            updated.memUsed = status.ram ?? 0
            updated.memTotal = nonZero(status.ramTotal, fallback: node.memTotal)
            updated.swapUsed = status.swap ?? 0
            updated.swapTotal = nonZero(status.swapTotal, fallback: node.swapTotal)
            updated.diskUsed = status.disk ?? 0
            updated.diskTotal = nonZero(status.diskTotal, fallback: node.diskTotal)
            updated.netIn = status.netIn ?? 0
            updated.netOut = status.netOut ?? 0
            updated.trafficUp = resetAwareTrafficDelta(
                previous: node.netTotalUp,
                current: status.netTotalUp
            )
            updated.trafficDown = resetAwareTrafficDelta(
                previous: node.netTotalDown,
                current: status.netTotalDown
            )
            updated.netTotalUp = status.netTotalUp ?? 0
            updated.netTotalDown = status.netTotalDown ?? 0
            updated.uptime = status.uptime ?? 0
            updated.load1 = status.load ?? 0
            updated.load5 = status.load5 ?? 0
            updated.load15 = status.load15 ?? 0
            updated.process = status.process ?? 0
            updated.connectionsTcp = safeTCPConnectionCount(
                total: status.connections,
                udp: status.connectionsUdp
            )
            updated.connectionsUdp = max(status.connectionsUdp ?? 0, 0)
            return updated
        }
        .sorted {
            if $0.isOnline != $1.isOnline {
                return $0.isOnline && !$1.isOnline
            }
            if $0.weight != $1.weight {
                return $0.weight < $1.weight
            }
            return $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    private func rpc<T: Decodable>(
        method: String,
        params: [String: Any]? = nil,
        token: String,
        responseType: T.Type
    ) async throws -> T {
        var body: [String: Any] = [
            "jsonrpc": "2.0",
            "method": method,
            "id": 1
        ]
        if let params {
            body["params"] = params
        }
        let request = try makeRequest(path: "/api/rpc2", method: "POST", jsonBody: body, token: token)
        let (data, response) = try await data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw KomariClientError.invalidResponse
        }
        guard (200...299).contains(httpResponse.statusCode) else {
            throw KomariClientError.httpStatus(httpResponse.statusCode, path: "/api/rpc2 (\(method))")
        }
        let envelope = try Self.decoder.decode(RpcEnvelope<T>.self, from: data)
        if let error = envelope.error {
            if let code = error.code, code == 401 || code == 403 {
                throw KomariClientError.httpStatus(
                    code,
                    path: "/api/rpc2 (\(method))"
                )
            }
            throw KomariClientError.rpc(
                Self.sanitizedServerMessage(error.message, fallback: "RPC error")
            )
        }
        guard let result = envelope.result else {
            throw KomariClientError.invalidResponse
        }
        return result
    }

    private func makeRequest(
        path: String,
        method: String,
        jsonBody: [String: Any]? = nil,
        token: String? = nil
    ) throws -> URLRequest {
        guard profile.validatedBaseURL != nil,
              let url = URL(string: profile.normalizedBaseURL + path) else {
            throw KomariClientError.invalidConfiguration("Server URL is invalid")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue(AppMetadata.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        profile.sanitizedCustomHeaders.forEach { header in
            request.setValue(header.value, forHTTPHeaderField: header.name)
        }

        if let token, !token.isEmpty {
            switch profile.authType {
            case .password:
                let existingCookie = request.value(forHTTPHeaderField: "Cookie") ?? ""
                let sessionCookie = "session_token=\(token)"
                let fullCookie = existingCookie.isEmpty ? sessionCookie : "\(existingCookie); \(sessionCookie)"
                request.setValue(fullCookie, forHTTPHeaderField: "Cookie")
            case .apiKey:
                request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            case .guest:
                break
            }
        }

        if let jsonBody {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: jsonBody)
        }
        return request
    }

    private func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData

        let delegate = KomariSessionDelegate(
            allowInsecureTLS: profile.allowInsecureTLS,
            allowedHost: request.url?.host
        )
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        return try await session.data(for: request)
    }

    private func normalizedUUID(_ uuid: String) throws -> String {
        let cleaned = uuid.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else {
            throw KomariClientError.invalidConfiguration("UUID is required")
        }
        return cleaned
    }

    private func extractSessionToken(from response: HTTPURLResponse) -> String? {
        let cookieHeader = response.value(forHTTPHeaderField: "Set-Cookie") ?? ""
        for part in cookieHeader.components(separatedBy: ";") {
            let trimmed = part.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.hasPrefix("session_token=") {
                let token = String(trimmed.dropFirst("session_token=".count))
                return token.isEmpty ? nil : token
            }
        }
        return nil
    }

    private func nonZero(_ value: Int64?, fallback: Int64?) -> Int64 {
        let candidate = value ?? 0
        return candidate > 0 ? candidate : fallback ?? 0
    }

    private static func sanitizedServerMessage(_ message: String?, fallback: String) -> String {
        guard let message else { return fallback }
        let boundedInput = String(message.prefix(2_048))
        let singleLine = boundedInput
            .components(separatedBy: .controlCharacters)
            .joined(separator: " ")
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
        let boundedOutput = String(singleLine.prefix(512))
        return boundedOutput.isEmpty ? fallback : boundedOutput
    }

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return decoder
    }()
}

final class KomariSessionDelegate: NSObject, URLSessionTaskDelegate {
    private let allowInsecureTLS: Bool
    private let allowedHost: String?

    init(allowInsecureTLS: Bool, allowedHost: String?) {
        self.allowInsecureTLS = allowInsecureTLS
        self.allowedHost = allowedHost?.lowercased()
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        // Never forward cookies, bearer tokens, or custom access headers to a redirect target.
        completionHandler(nil)
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard allowInsecureTLS,
              challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let serverTrust = challenge.protectionSpace.serverTrust,
              isAllowedHost(challenge.protectionSpace.host) else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        completionHandler(.useCredential, URLCredential(trust: serverTrust))
    }

    private func isAllowedHost(_ host: String) -> Bool {
        guard let allowedHost else { return false }
        return host.lowercased() == allowedHost
    }
}

enum KomariClientError: LocalizedError {
    case invalidConfiguration(String)
    case invalidResponse
    case httpStatus(Int, path: String)
    case rpc(String)
    case requires2FA
    case invalidTwoFactorCode

    /// A deliberately narrow signal for retrying expired password sessions.
    /// Callers must not infer authentication failures from untrusted response text.
    var isAuthenticationFailure: Bool {
        guard case .httpStatus(let status, _) = self else { return false }
        return status == 401 || status == 403
    }

    var errorDescription: String? {
        switch self {
        case .invalidConfiguration(let message):
            return message
        case .invalidResponse:
            return "Invalid server response"
        case .httpStatus(let status, let path):
            return "HTTP \(status) at \(path)"
        case .rpc(let message):
            return message
        case .requires2FA:
            return "Two-factor authentication is required"
        case .invalidTwoFactorCode:
            return "The two-factor authentication code is invalid"
        }
    }
}

private struct RpcEnvelope<Result: Decodable>: Decodable {
    let result: Result?
    let error: RpcErrorPayload?
}

private struct RpcErrorPayload: Decodable {
    let code: Int?
    let message: String?
}

private struct AdminEnvelope: Decodable {
    let status: String?
    let message: String?
}

private struct AdminDataEnvelope<DataPayload: Decodable>: Decodable {
    let status: String?
    let message: String?
    let data: DataPayload?
}

private struct VersionResult: Decodable {
    let version: String
}

struct NodeInfoPayload: Decodable {
    let name: String?
    let cpuName: String?
    let virtualization: String?
    let arch: String?
    let cpuCores: Int?
    let os: String?
    let kernelVersion: String?
    let gpuName: String?
    let region: String?
    let ipv4: String?
    let ipv6: String?
    let memTotal: Int64?
    let swapTotal: Int64?
    let diskTotal: Int64?
    let weight: Int?
    let expiredAt: String?
    let group: String?
    let trafficLimit: Int64?
    let trafficLimitType: String?
}

struct NodeStatusPayload: Decodable {
    let cpu: Double?
    let ram: Int64?
    let ramTotal: Int64?
    let swap: Int64?
    let swapTotal: Int64?
    let load: Double?
    let load5: Double?
    let load15: Double?
    let disk: Int64?
    let diskTotal: Int64?
    let netIn: Int64?
    let netOut: Int64?
    let netTotalUp: Int64?
    let netTotalDown: Int64?
    let process: Int?
    let connections: Int?
    let connectionsUdp: Int?
    let uptime: Int64?
    let online: Bool?
    let time: String?
}

private struct LoadRecordsPayload: Decodable {
    let records: [String: [LoadRecordPayload]]
}

private struct LoadRecordPayload: Decodable {
    let time: String
    let cpu: Double?
    let ram: Int64?
    let ramTotal: Int64?
    let disk: Int64?
    let diskTotal: Int64?
    let netIn: Int64?
    let netOut: Int64?
    let netTotalUp: Int64?
    let netTotalDown: Int64?
    let trafficUp: Int64?
    let trafficDown: Int64?
    let load: Double?
    let process: Int?
    let connections: Int?
    let connectionsUdp: Int?

    func toDomain(trafficUp: Int64, trafficDown: Int64) -> LoadRecord {
        LoadRecord(
            time: time,
            cpu: cpu ?? 0,
            ramPercent: percent(used: ram, total: ramTotal),
            diskPercent: percent(used: disk, total: diskTotal),
            netIn: netIn ?? 0,
            netOut: netOut ?? 0,
            netTotalUp: netTotalUp ?? 0,
            netTotalDown: netTotalDown ?? 0,
            trafficUp: trafficUp,
            trafficDown: trafficDown,
            load: load ?? 0,
            process: process ?? 0,
            connections: safeTCPConnectionCount(total: connections, udp: connectionsUdp),
            connectionsUdp: max(connectionsUdp ?? 0, 0)
        )
    }
}

private struct PingRecordsPayload: Decodable {
    let records: [PingRecordPayload]
    let tasks: [PingTaskPayload]
}

private struct PingTaskPayload: Decodable {
    let id: Int
    let name: String
    let interval: Int?
    let total: Int?
    let latest: Double?
    let min: Double?
    let max: Double?
    let avg: Double?
    let loss: Double?
    let p50: Double?
    let p99: Double?
    let p99P50Ratio: Double?

    func toDomain() -> PingTask {
        PingTask(
            id: id,
            name: name,
            interval: interval ?? 0,
            sampleCount: max(total ?? 0, 0),
            latest: latest,
            min: min,
            max: max,
            avg: avg,
            loss: loss,
            p50: p50,
            p99: p99,
            p99P50Ratio: p99P50Ratio
        )
    }
}

private struct PingRecordPayload: Decodable {
    let taskId: Int
    let time: String
    let value: Double?
    let client: String

    func toDomain(taskName: String) -> PingRecord? {
        guard let value, value.isFinite else { return nil }
        return PingRecord(taskId: taskId, taskName: taskName, time: time, value: value)
    }
}

private struct RecentStatusResponse: Decodable {
    let status: String?
    let message: String?
    let data: [RecentStatusItemPayload]?
}

private struct RecentCpuPayload: Decodable { let usage: Double? }
private struct RecentUsedTotalPayload: Decodable { let total: Int64?; let used: Int64? }
private struct RecentLoadPayload: Decodable { let load1: Double? }
private struct RecentNetworkPayload: Decodable {
    let up: Int64?
    let down: Int64?
    let totalUp: Int64?
    let totalDown: Int64?
}
private struct RecentConnectionsPayload: Decodable { let tcp: Int?; let udp: Int? }

private struct RecentStatusItemPayload: Decodable {
    let cpu: RecentCpuPayload?
    let ram: RecentUsedTotalPayload?
    let disk: RecentUsedTotalPayload?
    let network: RecentNetworkPayload?
    let load: RecentLoadPayload?
    let process: Int?
    let connections: RecentConnectionsPayload?
    let updatedAt: String?

    func toDomain(trafficUp: Int64, trafficDown: Int64) -> LoadRecord {
        LoadRecord(
            time: updatedAt ?? "",
            cpu: cpu?.usage ?? 0,
            ramPercent: percent(used: ram?.used, total: ram?.total),
            diskPercent: percent(used: disk?.used, total: disk?.total),
            netIn: network?.down ?? 0,
            netOut: network?.up ?? 0,
            netTotalUp: network?.totalUp ?? 0,
            netTotalDown: network?.totalDown ?? 0,
            trafficUp: trafficUp,
            trafficDown: trafficDown,
            load: load?.load1 ?? 0,
            process: process ?? 0,
            connections: max(connections?.tcp ?? 0, 0),
            connectionsUdp: max(connections?.udp ?? 0, 0)
        )
    }
}

private func percent(used: Int64?, total: Int64?) -> Double {
    guard let used, let total, total > 0 else { return 0 }
    return Double(used) / Double(total) * 100
}

private extension Dictionary where Key == String {
    func matching(uuid: String) -> Value? {
        self[uuid] ?? first { key, _ in
            key.trimmingCharacters(in: .whitespacesAndNewlines) == uuid
        }?.value
    }
}
