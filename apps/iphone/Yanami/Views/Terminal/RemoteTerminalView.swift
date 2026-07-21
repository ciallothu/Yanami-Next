import SwiftTerm
import SwiftUI
import UIKit

struct TerminalNavigationWrapper: View {
    let uuid: String
    let server: ServerProfile

    @EnvironmentObject private var store: AppStore
    @State private var token: String?
    @State private var error: String?
    @State private var isResolvingToken = false
    @State private var didResolveInitialToken = false

    var body: some View {
        Group {
            if isResolvingToken {
                ProgressView("Preparing terminal...")
            } else if let token {
                RemoteTerminalScreen(
                    viewModel: SshTerminalViewModel(uuid: uuid, server: server, token: token),
                    onRetryAuthentication: {
                        Task { await resolveToken(forcePasswordLogin: true) }
                    }
                )
                .id(token)
            } else if let error {
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.largeTitle)
                        .foregroundStyle(.red)
                    Text(error)
                        .multilineTextAlignment(.center)
                    Button("Retry") {
                        Task { await resolveToken(forcePasswordLogin: true) }
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding()
            } else {
                ProgressView("Preparing terminal...")
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
            switch server.authType {
            case .guest:
                error = "Guest mode not supported"
            case .apiKey:
                let apiKey = server.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
                if apiKey.isEmpty {
                    error = "API Key is required"
                } else {
                    token = apiKey
                }
            case .password:
                token = try await store.authenticationToken(
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

private struct RemoteTerminalScreen: View {
    @StateObject private var viewModel: SshTerminalViewModel
    private let onRetryAuthentication: () -> Void
    @EnvironmentObject private var store: AppStore
    @Environment(\.scenePhase) private var scenePhase

    @State private var showingSnippets = false
    @State private var isVisible = false

    init(viewModel: SshTerminalViewModel, onRetryAuthentication: @escaping () -> Void) {
        _viewModel = StateObject(wrappedValue: viewModel)
        self.onRetryAuthentication = onRetryAuthentication
    }

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                RemoteTerminalView(
                    viewModel: viewModel,
                    fontSize: store.settings.terminalFontSize
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.black)

                if viewModel.isConnecting {
                    VStack(spacing: 10) {
                        ProgressView()
                        Text("Connecting to terminal...")
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.black.opacity(0.88))
                } else if let error = viewModel.error {
                    VStack(spacing: 12) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.largeTitle)
                            .foregroundStyle(.red)
                        Text(error)
                            .multilineTextAlignment(.center)
                        Button("Retry") {
                            onRetryAuthentication()
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .padding()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.black.opacity(0.88))
                }
            }

            keyboardAccessoryBar
        }
        .navigationTitle("Terminal")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    showingSnippets = true
                } label: {
                    Image(systemName: "command")
                }
            }
        }
        .onAppear {
            isVisible = true
            if scenePhase == .active {
                viewModel.connect()
            }
        }
        .onDisappear {
            isVisible = false
            viewModel.disconnect()
        }
        .onChange(of: scenePhase) { phase in
            guard isVisible else { return }
            if phase == .active {
                viewModel.connect()
            } else {
                viewModel.disconnect()
            }
        }
        .sheet(isPresented: $showingSnippets) {
            SnippetPickerView { snippet in
                viewModel.sendText(snippet.content + (snippet.appendEnter ? "\r" : ""))
            }
        }
    }

    private var keyboardAccessoryBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                TerminalKeyButton(title: "Ctrl", isHighlighted: viewModel.ctrlActive) {
                    viewModel.ctrlActive.toggle()
                }
                TerminalKeyButton(title: "Alt", isHighlighted: viewModel.altActive) {
                    viewModel.altActive.toggle()
                }
                TerminalKeyButton(title: "Esc") { viewModel.sendText("\u{1b}") }
                TerminalKeyButton(title: "Tab") { viewModel.sendText("\t") }
                TerminalKeyButton(title: "↑") { viewModel.sendText("\u{1b}[A") }
                TerminalKeyButton(title: "↓") { viewModel.sendText("\u{1b}[B") }
                TerminalKeyButton(title: "←") { viewModel.sendText("\u{1b}[D") }
                TerminalKeyButton(title: "→") { viewModel.sendText("\u{1b}[C") }
            }
            .padding(8)
        }
        .background(.thinMaterial)
    }
}

struct RemoteTerminalView: UIViewRepresentable {
    @ObservedObject var viewModel: SshTerminalViewModel
    let fontSize: Int

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> TerminalView {
        let terminal = TerminalView(
            frame: .zero,
            font: UIFont.monospacedSystemFont(ofSize: CGFloat(clampedFontSize), weight: .regular)
        )
        terminal.terminalDelegate = context.coordinator
        terminal.nativeBackgroundColor = .black
        terminal.nativeForegroundColor = UIColor(white: 0.94, alpha: 1)
        terminal.backgroundColor = .black
        terminal.indicatorStyle = .white
        terminal.allowMouseReporting = false
        terminal.optionAsMetaKey = true
        terminal.linkReporting = .implicit
        terminal.linkHighlightMode = .always
        terminal.changeScrollback(2_000)
        context.coordinator.bind(viewModel: viewModel, terminal: terminal)
        return terminal
    }

    func updateUIView(_ terminal: TerminalView, context: Context) {
        context.coordinator.bind(viewModel: viewModel, terminal: terminal)
        context.coordinator.requestInitialFocusIfNeeded(
            terminal: terminal,
            isConnected: viewModel.isConnected
        )
        let newSize = CGFloat(clampedFontSize)
        if abs(terminal.font.pointSize - newSize) > 0.1 {
            terminal.font = UIFont.monospacedSystemFont(ofSize: newSize, weight: .regular)
        }
    }

    static func dismantleUIView(_ terminal: TerminalView, coordinator: Coordinator) {
        coordinator.unbind()
        terminal.terminalDelegate = nil
    }

    private var clampedFontSize: Int {
        min(max(fontSize, 8), 32)
    }

    final class Coordinator: NSObject, TerminalViewDelegate {
        private let sinkID = UUID()
        private weak var viewModel: SshTerminalViewModel?
        private weak var terminal: TerminalView?
        private var didRequestInitialFocus = false

        @MainActor
        func bind(viewModel: SshTerminalViewModel, terminal: TerminalView) {
            if self.viewModel === viewModel, self.terminal === terminal { return }
            self.viewModel?.detachOutputSink(id: sinkID)
            self.viewModel = viewModel
            self.terminal = terminal
            viewModel.attachOutputSink(id: sinkID) { [weak terminal] data in
                guard let terminal, !data.isEmpty else { return }
                let bytes = [UInt8](data)
                terminal.feed(byteArray: bytes[...])
            }
        }

        @MainActor
        func requestInitialFocusIfNeeded(terminal: TerminalView, isConnected: Bool) {
            guard isConnected else {
                didRequestInitialFocus = false
                return
            }
            guard !didRequestInitialFocus else { return }
            didRequestInitialFocus = true
            DispatchQueue.main.async { [weak terminal] in
                terminal?.becomeFirstResponder()
            }
        }

        @MainActor
        func unbind() {
            viewModel?.detachOutputSink(id: sinkID)
            viewModel = nil
            terminal = nil
            didRequestInitialFocus = false
        }

        func send(source: TerminalView, data: ArraySlice<UInt8>) {
            let bytes = Array(data)
            DispatchQueue.main.async { [weak self] in
                self?.viewModel?.sendTerminalInput(bytes[...])
            }
        }

        func sizeChanged(source: TerminalView, newCols: Int, newRows: Int) {
            DispatchQueue.main.async { [weak self] in
                self?.viewModel?.sendResize(cols: newCols, rows: newRows)
            }
        }

        func requestOpenLink(source: TerminalView, link: String, params: [String: String]) {
            guard let url = Self.validatedWebURL(from: link) else { return }
            DispatchQueue.main.async {
                UIApplication.shared.open(url, options: [:], completionHandler: nil)
            }
        }

        func clipboardRead(source: TerminalView) -> Data? {
            // Remote OSC 52 queries must never read the local clipboard implicitly.
            nil
        }

        func clipboardCopy(source: TerminalView, content: Data) {
            // Ignore remote OSC 52 writes. User-driven selection/copy remains available.
        }

        func setTerminalTitle(source: TerminalView, title: String) {}
        func hostCurrentDirectoryUpdate(source: TerminalView, directory: String?) {}
        func scrolled(source: TerminalView, position: Double) {}
        func bell(source: TerminalView) {}
        func iTermContent(source: TerminalView, content: ArraySlice<UInt8>) {}
        func rangeChanged(source: TerminalView, startY: Int, endY: Int) {}

        private static func validatedWebURL(from rawLink: String) -> URL? {
            let link = rawLink.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !link.isEmpty,
                  link.rangeOfCharacter(from: .controlCharacters) == nil,
                  var components = URLComponents(string: link),
                  let scheme = components.scheme?.lowercased(),
                  scheme == "http" || scheme == "https",
                  let host = components.host,
                  !host.isEmpty,
                  components.user == nil,
                  components.password == nil else {
                return nil
            }
            components.scheme = scheme
            return components.url
        }
    }
}

private struct TerminalKeyButton: View {
    let title: String
    var isHighlighted = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(.subheadline, design: .monospaced))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(isHighlighted ? Color.accentColor : Color.secondary.opacity(0.2))
                .foregroundColor(isHighlighted ? .white : .primary)
                .clipShape(RoundedRectangle(cornerRadius: 6))
        }
    }
}

private struct SnippetPickerView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss

    let onSelect: (TerminalSnippet) -> Void

    @State private var showingAdd = false
    @State private var newTitle = ""
    @State private var newContent = ""

    var body: some View {
        NavigationStack {
            List {
                ForEach(store.settings.snippets) { snippet in
                    Button {
                        onSelect(snippet)
                        dismiss()
                    } label: {
                        VStack(alignment: .leading) {
                            Text(snippet.title)
                                .font(.headline)
                            Text(snippet.content)
                                .font(.caption)
                                .lineLimit(1)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .onDelete { indexSet in
                    var newSettings = store.settings
                    newSettings.snippets.remove(atOffsets: indexSet)
                    store.updateSettings(newSettings)
                }
            }
            .navigationTitle("Snippets")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showingAdd = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .alert("Add Snippet", isPresented: $showingAdd) {
                TextField("Title", text: $newTitle)
                TextField("Command", text: $newContent)
                Button("Add") {
                    guard !newTitle.isEmpty, !newContent.isEmpty else { return }
                    var newSettings = store.settings
                    newSettings.snippets.append(
                        TerminalSnippet(title: newTitle, content: newContent, appendEnter: true)
                    )
                    store.updateSettings(newSettings)
                    newTitle = ""
                    newContent = ""
                }
                Button("Cancel", role: .cancel) {
                    newTitle = ""
                    newContent = ""
                }
            }
        }
    }
}
