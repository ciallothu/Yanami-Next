import LocalAuthentication
import SwiftUI
import UIKit

struct RootView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.scenePhase) private var scenePhase
    @State private var selectedTab = 0
    @State private var authError: String?
    @State private var isAuthenticating = false
    @State private var authenticationGeneration: UInt64 = 0
    @State private var authenticationContext: LAContext?
    @State private var isPrivacyCoverVisible = false
    @StateObject private var privacyShieldWindow = PrivacyShieldWindowController()

    var body: some View {
        ZStack {
            Group {
                if !store.isCredentialAccessUnlocked {
                    BiometricLockView(message: authError ?? store.credentialAccessError) {
                        authenticate()
                    }
                } else {
                    tabs
                }
            }
            if isPrivacyCoverVisible {
                PrivacyShieldView()
                    .transition(.identity)
            }
        }
        .environment(\.locale, AppLocalization.locale(for: store.settings.language))
        .sheet(
            item: Binding(
                get: { store.twoFactorPrompt },
                set: { prompt in
                    if prompt == nil {
                        store.cancelTwoFactorPrompt()
                    }
                }
            )
        ) { prompt in
            TwoFactorCodePromptView(prompt: prompt)
                .environmentObject(store)
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .inactive:
                isPrivacyCoverVisible = true
                privacyShieldWindow.show()
                if shouldLockCredentialsForSceneTransition(
                    .inactive,
                    lockEnabled: store.settings.biometricEnabled,
                    rootAuthenticationInProgress: isAuthenticating,
                    lockMutationAuthorizationInProgress:
                        store.isBiometricLockAuthorizationInProgress
                ) {
                    store.lockCredentialAccess()
                }
            case .background:
                isPrivacyCoverVisible = true
                privacyShieldWindow.show()
                authenticationGeneration &+= 1
                authenticationContext?.invalidate()
                authenticationContext = nil
                isAuthenticating = false
                authError = nil
                if shouldCancelBiometricLockAuthorization(for: .background) {
                    store.cancelBiometricLockAuthorization()
                }
                if shouldLockCredentialsForSceneTransition(
                    .background,
                    lockEnabled: store.settings.biometricEnabled,
                    rootAuthenticationInProgress: isAuthenticating,
                    lockMutationAuthorizationInProgress:
                        store.isBiometricLockAuthorizationInProgress
                ) {
                    store.lockCredentialAccess()
                }
            case .active:
                isPrivacyCoverVisible = false
                privacyShieldWindow.hide()
                if !store.isCredentialAccessUnlocked && !isAuthenticating {
                    authenticate()
                }
            @unknown default:
                break
            }
        }
        .onDisappear {
            privacyShieldWindow.hide()
        }
    }

    private var tabs: some View {
        TabView(selection: $selectedTab) {
            ServerListView()
                .tabItem {
                    Label("Servers", systemImage: "server.rack")
                }
                .tag(0)

            NodeListView()
                .tabItem {
                    Label("Nodes", systemImage: "list.bullet.rectangle")
                }
                .tag(1)

            SettingsView()
                .tabItem {
                    Label("Settings", systemImage: "gearshape")
                }
                .tag(2)
        }
        .environment(\.sizeCategory, store.settings.fontScale.contentSizeCategory)
        .preferredColorScheme(store.settings.darkMode.colorScheme)
        .id(store.settings.language)
        .task {
            if store.settings.autoEnterNodeList, store.activeServer != nil {
                selectedTab = 1
            }
            if store.activeServer != nil && store.nodes.isEmpty {
                await store.loadNodes(mode: .initial)
            }
        }
        .onChange(of: store.settings.autoEnterNodeList) { enabled in
            if enabled, store.activeServer != nil {
                selectedTab = 1
            }
        }
        .onChange(of: store.settings.biometricEnabled) { enabled in
            if enabled {
                store.lockCredentialAccess()
            }
        }
    }

    private func authenticate() {
        guard scenePhase == .active, !isAuthenticating else { return }
        authenticationGeneration &+= 1
        let attemptGeneration = authenticationGeneration
        isAuthenticating = true
        let context = LAContext()
        authenticationContext = context
        context.localizedFallbackTitle = ""
        context.localizedCancelTitle = AppLocalization.string("Cancel", language: store.settings.language)
        var error: NSError?
        let policy = LAPolicy.deviceOwnerAuthenticationWithBiometrics
        guard context.canEvaluatePolicy(policy, error: &error) else {
            authenticationContext = nil
            isAuthenticating = false
            authError = AppLocalization.string("Face ID or Touch ID is unavailable.", language: store.settings.language)
            return
        }
        context.evaluatePolicy(
            policy,
            localizedReason: AppLocalization.string("Unlock Yanami Next", language: store.settings.language)
        ) { success, _ in
            DispatchQueue.main.async {
                guard attemptGeneration == authenticationGeneration else {
                    context.invalidate()
                    return
                }
                authenticationContext = nil
                isAuthenticating = false
                if canApplyLocalAuthenticationSuccess(
                    attemptGeneration: attemptGeneration,
                    currentGeneration: authenticationGeneration,
                    sceneIsActive: scenePhase == .active,
                    succeeded: success
                ) {
                    if store.unlockCredentialAccess(using: context) {
                        authError = nil
                    } else {
                        authError = store.credentialAccessError
                    }
                } else if scenePhase == .active {
                    authError = AppLocalization.string("Biometric authentication failed.", language: store.settings.language)
                }
                // ProfileStore has synchronously read and cached only the random AES key. Do
                // not retain a reusable LocalAuthentication credential after this attempt.
                context.invalidate()
            }
        }
    }
}

private struct PrivacyShieldView: View {
    var body: some View {
        Color(.systemBackground)
            .ignoresSafeArea()
            .overlay {
                Image(systemName: "lock.shield")
                    .font(.system(size: 36))
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
    }
}

/// A root ZStack cannot cover SwiftUI presentation windows. This separate alert-level window is
/// attached to the current foreground UIWindowScene, so sheets and nested presentations are also
/// excluded from inactive/app-switcher snapshots. System LocalAuthentication UI is presented by
/// iOS above application windows and remains usable.
@MainActor
private final class PrivacyShieldWindowController: ObservableObject {
    private var window: UIWindow?

    func show() {
        if let window {
            window.isHidden = false
            return
        }
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let scene = scenes.first(where: {
            $0.activationState == .foregroundActive ||
                $0.activationState == .foregroundInactive
        }) ?? scenes.first else {
            return
        }

        let controller = UIHostingController(rootView: PrivacyShieldView())
        controller.view.backgroundColor = .systemBackground
        let shieldWindow = UIWindow(windowScene: scene)
        shieldWindow.rootViewController = controller
        shieldWindow.windowLevel = .alert + 1
        shieldWindow.isUserInteractionEnabled = false
        shieldWindow.isHidden = false
        window = shieldWindow
    }

    func hide() {
        window?.isHidden = true
        window?.rootViewController = nil
        window = nil
    }
}

private struct TwoFactorCodePromptView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.dismiss) private var dismiss
    let prompt: TwoFactorPrompt
    @State private var code = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Enter the current authentication code for \(prompt.serverName).")
                    SecureField("Two-factor authentication code", text: $code)
                        .keyboardType(.numberPad)
                        .textContentType(.oneTimeCode)
                }
                if let message = prompt.message {
                    Section {
                        Text(message)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Two-Factor Authentication")
            .interactiveDismissDisabled()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        store.cancelTwoFactorPrompt(id: prompt.id)
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Continue") {
                        if store.submitTwoFactorCode(code, promptID: prompt.id) {
                            code = ""
                            dismiss()
                        }
                    }
                    .disabled(code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

private struct BiometricLockView: View {
    let message: String?
    let onUnlock: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "lock.shield")
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
            Text("Yanami Next Locked")
                .font(.title3.bold())
            if let message {
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            Button("Unlock", action: onUnlock)
                .buttonStyle(.borderedProminent)
        }
        .padding()
        .onAppear(perform: onUnlock)
    }
}

private extension String {
    var colorScheme: ColorScheme? {
        switch self {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }
}

private extension Double {
    var contentSizeCategory: ContentSizeCategory {
        switch self {
        case ..<0.9: return .small
        case 0.9..<1.1: return .medium
        case 1.1..<1.25: return .large
        default: return .extraLarge
        }
    }
}
