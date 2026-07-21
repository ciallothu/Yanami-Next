import Combine
import Foundation

@MainActor
final class SshTerminalViewModel: ObservableObject {
    @Published var isConnecting = true
    @Published var isConnected = false
    @Published var error: String?
    @Published var ctrlActive = false
    @Published var altActive = false
    @Published private(set) var shouldRequestTwoFactorOnRetry = false
    @Published private(set) var shouldRefreshAuthenticationOnRetry = false

    private static let maximumBufferedOutputBytes = 1_048_576

    private let uuid: String
    private let server: ServerProfile
    private let token: String
    private let requiresSensitiveTwoFactorForEveryHandshake: Bool
    private var pendingTwoFactorCode: String?

    private var urlSession: URLSession?
    private var sessionDelegate: TerminalWebSocketSessionDelegate?
    private var webSocketTask: URLSessionWebSocketTask?
    private var pingTimer: Timer?
    private var pingInFlight = false
    private var isDisconnecting = false
    private var currentHandshakeUsedTwoFactor = false
    private var lastTerminalSize: (cols: Int, rows: Int)?

    private var outputSinkID: UUID?
    private var outputSink: ((Data) -> Void)?
    private var bufferedOutput = Data()

    init(
        uuid: String,
        server: ServerProfile,
        token: String,
        oneTimeTwoFactorCode: String? = nil
    ) {
        self.uuid = uuid
        self.server = server
        self.token = token
        requiresSensitiveTwoFactorForEveryHandshake =
            server.authType == .password &&
            (server.requires2FA || oneTimeTwoFactorCode != nil)
        pendingTwoFactorCode = oneTimeTwoFactorCode
    }

    func connect() {
        guard webSocketTask == nil else { return }

        isDisconnecting = false
        isConnecting = true
        isConnected = false
        error = nil
        shouldRequestTwoFactorOnRetry = false
        shouldRefreshAuthenticationOnRetry = false

        let oneTimeTwoFactorCode = pendingTwoFactorCode
        pendingTwoFactorCode = nil
        guard let url = buildTerminalURL() else {
            error = "Invalid terminal URL"
            isConnecting = false
            return
        }

        var request = URLRequest(url: url, timeoutInterval: 30)
        request.setValue(AppMetadata.userAgent, forHTTPHeaderField: "User-Agent")
        server.sanitizedCustomHeaders.forEach { header in
            request.setValue(header.value, forHTTPHeaderField: header.name)
        }
        applyAuthentication(to: &request)
        if requiresSensitiveTwoFactorForEveryHandshake {
            guard let header = makeTerminalSensitiveHeader(
                authType: server.authType.rawValue,
                requiresTwoFactor: true,
                oneTimeCode: oneTimeTwoFactorCode
            ) else {
                isConnecting = false
                shouldRequestTwoFactorOnRetry = true
                error = "A fresh 6-digit two-factor code is required for this terminal connection."
                return
            }
            request.setValue(header.value, forHTTPHeaderField: header.name)
            currentHandshakeUsedTwoFactor = true
        } else {
            currentHandshakeUsedTwoFactor = false
        }
        request.setValue(buildOrigin(), forHTTPHeaderField: "Origin")

        let delegate = TerminalWebSocketSessionDelegate(
            owner: self,
            allowInsecureTLS: server.allowInsecureTLS,
            allowedHost: url.host
        )
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        let task = session.webSocketTask(with: request)

        sessionDelegate = delegate
        urlSession = session
        webSocketTask = task
        task.resume()
        receiveMessage(for: task)
    }

    func disconnect() {
        isDisconnecting = true
        closeConnection(sendCloseFrame: true)
        isConnecting = false
        isConnected = false
    }

    func attachOutputSink(id: UUID, sink: @escaping (Data) -> Void) {
        outputSinkID = id
        outputSink = sink
        guard !bufferedOutput.isEmpty else { return }
        let pending = bufferedOutput
        bufferedOutput.removeAll(keepingCapacity: true)
        sink(pending)
    }

    func detachOutputSink(id: UUID) {
        guard outputSinkID == id else { return }
        outputSinkID = nil
        outputSink = nil
    }

    /// Receives raw terminal bytes from SwiftTerm without a text round-trip.
    func sendTerminalInput(_ bytes: ArraySlice<UInt8>) {
        guard !bytes.isEmpty else { return }
        var payload = Array(bytes)

        if ctrlActive {
            ctrlActive = false
            if payload.count == 1 {
                let byte = payload[0]
                if byte >= 65, byte <= 90 {
                    payload[0] = byte - 64
                } else if byte >= 97, byte <= 122 {
                    payload[0] = byte - 96
                }
            }
        }
        if altActive {
            altActive = false
            payload.insert(0x1b, at: 0)
        }

        sendBinary(Data(payload))
    }

    func sendText(_ text: String) {
        guard !text.isEmpty else { return }
        sendBinary(Data(text.utf8))
    }

    func sendResize(cols: Int, rows: Int) {
        let size = (cols: max(cols, 1), rows: max(rows, 1))
        guard lastTerminalSize?.cols != size.cols || lastTerminalSize?.rows != size.rows else {
            return
        }
        lastTerminalSize = size
        guard isConnected else { return }
        sendResizeMessage(cols: size.cols, rows: size.rows)
    }

    fileprivate func webSocketDidOpen(_ task: URLSessionWebSocketTask) {
        guard webSocketTask === task else { return }
        isConnecting = false
        isConnected = true
        error = nil
        shouldRequestTwoFactorOnRetry = false
        shouldRefreshAuthenticationOnRetry = false
        if let lastTerminalSize {
            sendResizeMessage(cols: lastTerminalSize.cols, rows: lastTerminalSize.rows)
        }
        startPingTimer(for: task)
    }

    fileprivate func webSocketDidClose(
        _ task: URLSessionWebSocketTask,
        code: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        guard webSocketTask === task else { return }
        stopPingTimer()
        webSocketTask = nil
        urlSession?.finishTasksAndInvalidate()
        urlSession = nil
        sessionDelegate = nil
        isConnecting = false
        isConnected = false

        guard !isDisconnecting else { return }
        let reasonText = reason.flatMap { String(data: $0, encoding: .utf8) }
        error = reasonText?.isEmpty == false
            ? reasonText
            : "Terminal connection closed (\(code.rawValue))"
    }

    fileprivate func webSocketDidComplete(
        _ task: URLSessionWebSocketTask,
        error: Error
    ) {
        handleConnectionFailure(error, task: task)
    }

    private func applyAuthentication(to request: inout URLRequest) {
        switch server.authType {
        case .password:
            let existingCookie = request.value(forHTTPHeaderField: "Cookie") ?? ""
            let sessionCookie = "session_token=\(token)"
            request.setValue(
                existingCookie.isEmpty ? sessionCookie : "\(existingCookie); \(sessionCookie)",
                forHTTPHeaderField: "Cookie"
            )
        case .apiKey:
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        case .guest:
            break
        }
    }

    private func sendBinary(_ data: Data) {
        guard !data.isEmpty, isConnected, let task = webSocketTask else { return }
        task.send(.data(data)) { [weak self, weak task] error in
            guard let error, let task else { return }
            Task { @MainActor [weak self] in
                self?.handleConnectionFailure(error, task: task)
            }
        }
    }

    private func sendResizeMessage(cols: Int, rows: Int) {
        guard isConnected, let task = webSocketTask else { return }
        let payload: [String: Any] = [
            "type": "resize",
            "cols": cols,
            "rows": rows
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: payload),
              let text = String(data: data, encoding: .utf8) else {
            return
        }
        task.send(.string(text)) { [weak self, weak task] error in
            guard let error, let task else { return }
            Task { @MainActor [weak self] in
                self?.handleConnectionFailure(error, task: task)
            }
        }
    }

    private func receiveMessage(for task: URLSessionWebSocketTask) {
        task.receive { [weak self, weak task] result in
            guard let task else { return }
            Task { @MainActor [weak self] in
                guard let self, self.webSocketTask === task else { return }
                switch result {
                case .success(let message):
                    switch message {
                    case .string(let text):
                        self.deliverOutput(Data(text.utf8))
                    case .data(let data):
                        self.deliverOutput(data)
                    @unknown default:
                        break
                    }
                    self.receiveMessage(for: task)
                case .failure(let error):
                    self.handleConnectionFailure(error, task: task)
                }
            }
        }
    }

    private func deliverOutput(_ data: Data) {
        guard !data.isEmpty else { return }
        if let outputSink {
            outputSink(data)
            return
        }

        if data.count >= Self.maximumBufferedOutputBytes {
            bufferedOutput = Data(data.suffix(Self.maximumBufferedOutputBytes))
            return
        }
        bufferedOutput.append(data)
        let overflow = bufferedOutput.count - Self.maximumBufferedOutputBytes
        if overflow > 0 {
            bufferedOutput.removeFirst(overflow)
        }
    }

    private func startPingTimer(for task: URLSessionWebSocketTask) {
        stopPingTimer()
        let timer = Timer(timeInterval: 25, repeats: true) { [weak self, weak task] _ in
            guard let task else { return }
            Task { @MainActor [weak self] in
                self?.sendPing(on: task)
            }
        }
        RunLoop.main.add(timer, forMode: .common)
        pingTimer = timer
    }

    private func sendPing(on task: URLSessionWebSocketTask) {
        guard webSocketTask === task, isConnected, !pingInFlight else { return }
        pingInFlight = true
        task.sendPing { [weak self, weak task] error in
            guard let task else { return }
            Task { @MainActor [weak self] in
                guard let self, self.webSocketTask === task else { return }
                self.pingInFlight = false
                if let error {
                    self.handleConnectionFailure(error, task: task)
                }
            }
        }
    }

    private func stopPingTimer() {
        pingTimer?.invalidate()
        pingTimer = nil
        pingInFlight = false
    }

    private func handleConnectionFailure(_ failure: Error, task: URLSessionWebSocketTask) {
        guard webSocketTask === task else { return }
        let statusCode = (task.response as? HTTPURLResponse)?.statusCode
        let authenticationFailure = isTerminalAuthenticationFailure(
            statusCode: statusCode,
            errorDescription: failure.localizedDescription
        )
        stopPingTimer()
        webSocketTask = nil
        urlSession?.invalidateAndCancel()
        urlSession = nil
        sessionDelegate = nil
        isConnecting = false
        isConnected = false
        if !isDisconnecting {
            if authenticationFailure, server.authType == .password {
                shouldRequestTwoFactorOnRetry = true
                shouldRefreshAuthenticationOnRetry = true
                error = currentHandshakeUsedTwoFactor
                    ? "The two-factor code was invalid or expired, or the login session has expired. Try a fresh code; re-login if it continues to fail."
                    : "Terminal authentication was denied. If this account uses two-factor authentication, retry with the current code."
            } else {
                shouldRequestTwoFactorOnRetry = false
                shouldRefreshAuthenticationOnRetry = authenticationFailure
                error = failure.localizedDescription
            }
        }
    }

    private func closeConnection(sendCloseFrame: Bool) {
        stopPingTimer()
        let task = webSocketTask
        let session = urlSession
        webSocketTask = nil
        urlSession = nil
        sessionDelegate = nil

        if sendCloseFrame {
            task?.cancel(with: .goingAway, reason: nil)
            session?.finishTasksAndInvalidate()
        } else {
            task?.cancel()
            session?.invalidateAndCancel()
        }
    }

    private func buildTerminalURL() -> URL? {
        let cleanUUID = uuid.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanUUID.isEmpty,
              let baseURL = server.validatedBaseURL,
              var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false),
              let scheme = components.scheme?.lowercased() else {
            return nil
        }

        switch scheme {
        case "https": components.scheme = "wss"
        case "http": components.scheme = "ws"
        case "wss", "ws": break
        default: return nil
        }

        var componentCharacters = CharacterSet.urlPathAllowed
        componentCharacters.remove(charactersIn: "/?#%")
        guard let encodedUUID = cleanUUID.addingPercentEncoding(withAllowedCharacters: componentCharacters) else {
            return nil
        }

        let basePath = components.percentEncodedPath
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let pathComponents = [basePath, "api/admin/client", encodedUUID, "terminal"]
            .filter { !$0.isEmpty }
        components.percentEncodedPath = "/" + pathComponents.joined(separator: "/")
        components.query = nil
        components.fragment = nil
        return components.url
    }

    private func buildOrigin() -> String {
        guard let baseURL = server.validatedBaseURL,
              var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false),
              let scheme = components.scheme?.lowercased(),
              components.host != nil else {
            return server.normalizedBaseURL
        }
        components.scheme = scheme == "wss" ? "https" : (scheme == "ws" ? "http" : scheme)
        components.path = ""
        components.query = nil
        components.fragment = nil
        return components.url?.absoluteString.trimmedTrailingSlash() ?? server.normalizedBaseURL
    }
}

private final class TerminalWebSocketSessionDelegate: NSObject, URLSessionWebSocketDelegate {
    private weak var owner: SshTerminalViewModel?
    private let allowInsecureTLS: Bool
    private let allowedHost: String?

    init(owner: SshTerminalViewModel, allowInsecureTLS: Bool, allowedHost: String?) {
        self.owner = owner
        self.allowInsecureTLS = allowInsecureTLS
        self.allowedHost = allowedHost?.lowercased()
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol protocol: String?
    ) {
        Task { @MainActor [weak owner] in
            owner?.webSocketDidOpen(webSocketTask)
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        // Authentication and access headers must never follow a WebSocket handshake redirect.
        completionHandler(nil)
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        Task { @MainActor [weak owner] in
            owner?.webSocketDidClose(webSocketTask, code: closeCode, reason: reason)
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        guard let error, let webSocketTask = task as? URLSessionWebSocketTask else { return }
        Task { @MainActor [weak owner] in
            owner?.webSocketDidComplete(webSocketTask, error: error)
        }
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard allowInsecureTLS,
              challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              challenge.protectionSpace.host.lowercased() == allowedHost,
              let serverTrust = challenge.protectionSpace.serverTrust else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        completionHandler(.useCredential, URLCredential(trust: serverTrust))
    }
}
