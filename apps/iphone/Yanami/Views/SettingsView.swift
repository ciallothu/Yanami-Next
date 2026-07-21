import LocalAuthentication
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var store: AppStore
    @State private var draft = AppSettings()
    @State private var biometricError: String?
    @State private var biometricAuthenticationContext: LAContext?
    @State private var biometricAuthorizationLease: BiometricLockAuthorizationLease?

    var body: some View {
        NavigationStack {
            Form {
                Section("General") {
                    Toggle("Auto enter node list", isOn: $draft.autoEnterNodeList)
                }

                Section("Security") {
                    Toggle("Biometric lock", isOn: Binding(
                        get: { draft.biometricEnabled },
                        set: { enabled in
                            guard enabled != draft.biometricEnabled else { return }
                            authenticateBiometric(enabling: enabled) { context in
                                var updated = draft
                                updated.biometricEnabled = enabled
                                if store.updateSettings(
                                    updated,
                                    authorizationContext: context
                                ) {
                                    biometricError = nil
                                } else {
                                    biometricError = store.statusMessage
                                }
                                draft = store.settings
                            }
                        }
                    ))
                    if let biometricError {
                        Text(biometricError)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Section("Refresh") {
                    Toggle("Auto refresh", isOn: $draft.autoRefreshEnabled)
                    Stepper(value: $draft.refreshIntervalSeconds, in: 1...60, step: 1) {
                        Text("Interval \(Int(draft.refreshIntervalSeconds))s")
                    }
                }

                Section("Display") {
                    Toggle("Mask IP addresses", isOn: $draft.maskIpEnabled)
                    Toggle("Chart animation", isOn: $draft.chartAnimationEnabled)
                    Picker("Dark mode", selection: $draft.darkMode) {
                        Text("System").tag("system")
                        Text("Light").tag("light")
                        Text("Dark").tag("dark")
                    }
                    Picker("Language", selection: $draft.language) {
                        Text("System").tag("system")
                        Text("简体中文").tag("zh")
                        Text("English").tag("en")
                        Text("日本語").tag("ja")
                    }
                    Slider(value: $draft.fontScale, in: 0.8...1.4, step: 0.05) {
                        Text("Font scale")
                    } minimumValueLabel: {
                        Text("80%")
                    } maximumValueLabel: {
                        Text("140%")
                    }
                    Text("Font scale \(Int(draft.fontScale * 100))%")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section("Terminal") {
                    Stepper(value: $draft.terminalFontSize, in: 8...32, step: 1) {
                        Text("Font size \(draft.terminalFontSize)")
                    }
                }
                
                Section("Build") {
                    Text("Version \(AppMetadata.version)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
            .environment(\.locale, AppLocalization.locale(for: draft.language))
            .onAppear {
                draft = store.settings
            }
            .onChange(of: draft) { newValue in
                // Lock transitions are committed only by the authenticated toggle callback.
                guard newValue.biometricEnabled == store.settings.biometricEnabled else { return }
                if !store.updateSettings(newValue), draft != store.settings {
                    draft = store.settings
                }
            }
            .onDisappear {
                biometricAuthenticationContext?.invalidate()
                biometricAuthenticationContext = nil
                if biometricAuthorizationLease != nil {
                    store.cancelBiometricLockAuthorization()
                    biometricAuthorizationLease = nil
                }
            }
        }
    }

    private func authenticateBiometric(
        enabling: Bool,
        onSuccess: @escaping (LAContext) -> Void
    ) {
        guard let lease = store.beginBiometricLockAuthorization() else { return }
        let context = LAContext()
        biometricAuthorizationLease = lease
        biometricAuthenticationContext = context
        context.localizedFallbackTitle = ""
        context.localizedCancelTitle = AppLocalization.string("Cancel", language: draft.language)
        var error: NSError?
        let policy = LAPolicy.deviceOwnerAuthenticationWithBiometrics
        guard context.canEvaluatePolicy(policy, error: &error) else {
            finishBiometricAuthorization(lease: lease, context: context)
            biometricError = AppLocalization.string("Face ID or Touch ID is unavailable.", language: draft.language)
            return
        }
        context.evaluatePolicy(
            policy,
            localizedReason: AppLocalization.string(
                enabling
                    ? "Enable biometric lock for Yanami Next"
                    : "Disable biometric lock for Yanami Next",
                language: draft.language
            )
        ) { success, _ in
            DispatchQueue.main.async {
                guard biometricAuthorizationLease == lease,
                      store.canApplyBiometricLockAuthorization(lease) else {
                    finishBiometricAuthorization(lease: lease, context: context)
                    return
                }
                if success {
                    biometricError = nil
                    onSuccess(context)
                } else {
                    biometricError = AppLocalization.string("Biometric authentication failed.", language: draft.language)
                }
                finishBiometricAuthorization(lease: lease, context: context)
            }
        }
    }

    private func finishBiometricAuthorization(
        lease: BiometricLockAuthorizationLease,
        context: LAContext
    ) {
        context.invalidate()
        store.endBiometricLockAuthorization(lease)
        if biometricAuthorizationLease == lease {
            biometricAuthorizationLease = nil
            biometricAuthenticationContext = nil
        }
    }
}
