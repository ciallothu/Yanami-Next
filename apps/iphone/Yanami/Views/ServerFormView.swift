import SwiftUI

struct ServerFormView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var draft: ServerProfile
    @State private var isTesting = false
    @State private var isSaving = false
    @State private var testMessage = ""
    @State private var twoFaCode = ""
    @State private var showTwoFactorCode = false
    @State private var saveTask: Task<Void, Never>?
    @State private var saveGeneration: UInt64 = 0
    @State private var formAcceptsSaveCompletion = true
    let onSave: (PreparedServerProfile) -> Void

    init(server: ServerProfile, onSave: @escaping (PreparedServerProfile) -> Void) {
        _draft = State(initialValue: server)
        self.onSave = onSave
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Server") {
                    TextField("Name", text: $draft.name)
                    TextField("Server URL", text: $draft.baseURL)
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        .autocorrectionDisabled()
                    Toggle("Allow insecure TLS", isOn: $draft.allowInsecureTLS)
                    if draft.allowInsecureTLS {
                        Text("Use only for self-signed or private certificates you trust.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Section("Authentication") {
                    Picker("Mode", selection: $draft.authType) {
                        ForEach(AuthType.allCases) { authType in
                            Text(authType.title).tag(authType)
                        }
                    }

                    if draft.authType == .password {
                        TextField("Username", text: $draft.username)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        SecureField("Password", text: $draft.password)
                        if showTwoFactorCode || draft.requires2FA {
                            SecureField("Two-factor authentication code", text: $twoFaCode)
                                .keyboardType(.numberPad)
                                .textContentType(.oneTimeCode)
                            Text("Enter the current code. It is used once and is never saved.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }

                    if draft.authType == .apiKey {
                        SecureField("API Key", text: $draft.apiKey)
                    }
                }

                Section("Custom Headers") {
                    ForEach($draft.customHeaders) { $header in
                        VStack(alignment: .leading, spacing: 8) {
                            TextField("Header name", text: $header.name)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                            SecureField("Header value", text: $header.value)
                        }
                    }
                    .onDelete { offsets in
                        draft.customHeaders.remove(atOffsets: offsets)
                    }

                    Button {
                        draft.customHeaders.append(CustomHeader(name: "", value: ""))
                    } label: {
                        Label("Add Header", systemImage: "plus")
                    }

                    Button {
                        store.addCloudflareHeaders(to: &draft)
                    } label: {
                        Label("Add Cloudflare Access Headers", systemImage: "lock.shield")
                    }
                }

                Section("Validation") {
                    Button {
                        Task { await testConnection() }
                    } label: {
                        if isTesting {
                            ProgressView()
                        } else {
                            Label("Test Connection", systemImage: "network")
                        }
                    }
                    if !testMessage.isEmpty {
                        Text(testMessage)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Komari Instance")
            .onAppear {
                formAcceptsSaveCompletion = true
            }
            .onDisappear {
                cancelPendingSave()
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        cancelPendingSave()
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        beginSave()
                    }
                    .disabled(!canSave || isSaving)
                }
            }
        }
    }

    private var canSave: Bool {
        !draft.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        draft.validatedBaseURL != nil &&
        (draft.authType == .guest ||
         draft.authType == .apiKey && !draft.apiKey.isEmpty ||
         draft.authType == .password && !draft.username.isEmpty && !draft.password.isEmpty)
    }

    private func testConnection() async {
        isTesting = true
        defer { isTesting = false }
        do {
            let code = twoFaCode.trimmingCharacters(in: .whitespacesAndNewlines)
            let version = try await store.testConnection(
                draft,
                twoFaCode: code.isEmpty ? nil : code
            )
            draft.requires2FA = !code.isEmpty
            testMessage = "Connected. Komari \(version)"
        } catch {
            revealTwoFactorFieldIfNeeded(error)
            testMessage = error.localizedDescription
        }
    }

    @MainActor
    private func beginSave() {
        saveTask?.cancel()
        saveGeneration &+= 1
        let lease = ServerFormSaveLease(generation: saveGeneration)
        isSaving = true
        saveTask = Task { @MainActor in
            await saveServer(lease: lease)
        }
    }

    @MainActor
    private func cancelPendingSave() {
        formAcceptsSaveCompletion = false
        saveGeneration &+= 1
        saveTask?.cancel()
        saveTask = nil
        isSaving = false
    }

    @MainActor
    private func saveServer(lease: ServerFormSaveLease) async {
        defer {
            if lease.generation == saveGeneration {
                isSaving = false
                saveTask = nil
            }
        }
        do {
            let code = twoFaCode.trimmingCharacters(in: .whitespacesAndNewlines)
            let prepared = try await store.prepareServerForSave(
                draft,
                twoFaCode: code.isEmpty ? nil : code
            )
            try Task.checkCancellation()
            guard canCommitPreparedServerSave(
                lease,
                currentGeneration: saveGeneration,
                formAcceptsCompletion: formAcceptsSaveCompletion,
                taskIsCancelled: Task.isCancelled
            ) else {
                throw CancellationError()
            }
            twoFaCode = ""
            onSave(prepared)
        } catch is CancellationError {
            return
        } catch {
            revealTwoFactorFieldIfNeeded(error)
            testMessage = error.localizedDescription
        }
    }

    private func revealTwoFactorFieldIfNeeded(_ error: Error) {
        guard let clientError = error as? KomariClientError else { return }
        switch clientError {
        case .requires2FA, .invalidTwoFactorCode:
            draft.requires2FA = true
            showTwoFactorCode = true
            twoFaCode = ""
        default:
            break
        }
    }
}
