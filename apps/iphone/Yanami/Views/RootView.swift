import LocalAuthentication
import SwiftUI

struct RootView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.scenePhase) private var scenePhase
    @State private var selectedTab = 0
    @State private var unlocked = false
    @State private var authError: String?
    @State private var isAuthenticating = false

    var body: some View {
        Group {
            if store.settings.biometricEnabled && !unlocked {
                BiometricLockView(message: authError) {
                    authenticate()
                }
            } else {
                tabs
            }
        }
        .environment(\.locale, AppLocalization.locale(for: store.settings.language))
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .inactive, .background:
                if store.settings.biometricEnabled {
                    unlocked = false
                }
            case .active:
                if store.settings.biometricEnabled && !unlocked {
                    authenticate()
                }
            @unknown default:
                break
            }
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
            unlocked = !enabled
        }
    }

    private func authenticate() {
        guard !isAuthenticating else { return }
        isAuthenticating = true
        let context = LAContext()
        context.localizedFallbackTitle = ""
        context.localizedCancelTitle = AppLocalization.string("Cancel", language: store.settings.language)
        var error: NSError?
        let policy = LAPolicy.deviceOwnerAuthenticationWithBiometrics
        guard context.canEvaluatePolicy(policy, error: &error) else {
            isAuthenticating = false
            authError = AppLocalization.string("Face ID or Touch ID is unavailable.", language: store.settings.language)
            return
        }
        context.evaluatePolicy(
            policy,
            localizedReason: AppLocalization.string("Unlock Yanami Next", language: store.settings.language)
        ) { success, _ in
            DispatchQueue.main.async {
                isAuthenticating = false
                if success {
                    authError = nil
                    unlocked = true
                } else {
                    authError = AppLocalization.string("Biometric authentication failed.", language: store.settings.language)
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
