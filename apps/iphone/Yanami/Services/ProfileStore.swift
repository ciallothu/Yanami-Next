import CryptoKit
import Foundation
import LocalAuthentication
import Security

/// Persists app state in one of two forms:
///
/// - Lock disabled: a ThisDeviceOnly Keychain value.
/// - Lock enabled: an AES-GCM sealed value plus a random key stored behind Keychain user
///   presence. The key only lives in memory for the current unlocked credential session.
///
/// The small bootstrap value contains no credentials and is intentionally readable without
/// authentication so application launch can decide whether it is allowed to load profiles.
final class ProfileStore {
    private let legacyAccount = "appState"
    private let protectedAccount = "protectedAppState.v1"
    private let bootstrapAccount = "securityBootstrap.v1"
    private let protectedKeyPrefix = "protectedAppState.key.v2."
    private let keychain = KeychainStore(service: "com.sekusarisu.yanami.ios")

    private var activeProtectedStateKey: SymmetricKey?
    private var activeProtectedKeyAccount: String?

    /// Loads only non-secret bootstrap metadata. Legacy plaintext is migrated before a locked
    /// marker is returned. If a crash occurred while enabling, an already-sealed value repairs
    /// the marker to locked. If a crash occurred while disabling, the old locked marker remains
    /// authoritative until a fresh user-presence check completes the transition.
    func loadBootstrap() throws -> SecurityBootstrapState {
        let existingBootstrap = try keychain.readStrict(
            SecurityBootstrapState.self,
            account: bootstrapAccount
        )
        if let existingBootstrap,
           !(1...SecurityBootstrapState.currentSchemaVersion).contains(
               existingBootstrap.schemaVersion
           ) {
            throw ProfileStoreError.unsupportedBootstrapVersion
        }

        if let stored = try readStoredProtectedState() {
            switch stored {
            case .sealed(let envelope):
                // A sealed envelope is already authoritative; remove any old combined plaintext
                // before repairing metadata so a migration crash cannot preserve two copies.
                try keychain.delete(account: legacyAccount)
                let bootstrap = SecurityBootstrapState(
                    lockEnabled: true,
                    keyAccount: envelope.keyAccount,
                    keyProtection: envelope.keyProtection
                )
                if existingBootstrap != bootstrap {
                    try keychain.save(bootstrap, account: bootstrapAccount)
                }
                return bootstrap

            case .plain(let state):
                if state.settings.biometricEnabled {
                    let envelope = try sealWithNewProtectedKey(
                        state,
                        authenticationContext: nil,
                        verifyAuthentication: false
                    )
                    // The sealed state is authoritative before marker=true is published. A
                    // crash between these writes is detected as sealed and therefore locks.
                    try keychain.save(envelope, account: protectedAccount)
                    try keychain.delete(account: legacyAccount)
                    let bootstrap = SecurityBootstrapState(
                        lockEnabled: true,
                        keyAccount: envelope.keyAccount,
                        keyProtection: envelope.keyProtection
                    )
                    try keychain.save(bootstrap, account: bootstrapAccount)
                    clearCredentialSession()
                    return bootstrap
                }

                if existingBootstrap?.lockEnabled == true {
                    // This is an interrupted disable: keep requiring a cryptographic
                    // user-presence proof before publishing marker=false.
                    let bootstrap = try bootstrapWithAuthorizationSentinel(
                        existingBootstrap
                    )
                    if existingBootstrap != bootstrap {
                        try keychain.save(bootstrap, account: bootstrapAccount)
                    }
                    return bootstrap
                }

                let bootstrap = SecurityBootstrapState(lockEnabled: false)
                if existingBootstrap != bootstrap {
                    try keychain.save(bootstrap, account: bootstrapAccount)
                }
                try keychain.delete(account: legacyAccount)
                return bootstrap
            }
        }

        if let legacy = try keychain.readStrict(
            PersistedAppState.self,
            account: legacyAccount
        ) {
            if legacy.settings.biometricEnabled {
                let envelope = try sealWithNewProtectedKey(
                    legacy,
                    authenticationContext: nil,
                    verifyAuthentication: false
                )
                try keychain.save(envelope, account: protectedAccount)
                try keychain.delete(account: legacyAccount)
                let bootstrap = SecurityBootstrapState(
                    lockEnabled: true,
                    keyAccount: envelope.keyAccount,
                    keyProtection: envelope.keyProtection
                )
                try keychain.save(bootstrap, account: bootstrapAccount)
                clearCredentialSession()
                return bootstrap
            }

            try keychain.save(legacy, account: protectedAccount)
            let bootstrap = SecurityBootstrapState(lockEnabled: false)
            try keychain.save(bootstrap, account: bootstrapAccount)
            try keychain.delete(account: legacyAccount)
            return bootstrap
        }

        if existingBootstrap?.lockEnabled == true {
            throw ProfileStoreError.missingProtectedState
        }

        let initial = PersistedAppState(
            servers: [],
            activeServerId: nil,
            settings: AppSettings()
        )
        try keychain.save(initial, account: protectedAccount)
        let bootstrap = SecurityBootstrapState(lockEnabled: false)
        try keychain.save(bootstrap, account: bootstrapAccount)
        return bootstrap
    }

    /// Loads only the steady-state lock-disabled representation. This API cannot decrypt a
    /// sealed value or complete an interrupted disable.
    func loadUnprotectedState() throws -> PersistedAppState {
        try loadState(authenticationContext: nil)
    }

    /// Loads locked state. Requiring a non-optional context makes accidental pre-auth profile
    /// reads impossible at the API boundary; Keychain also refuses any implicit prompt.
    func loadProtectedState(
        authenticationContext: LAContext
    ) throws -> PersistedAppState {
        try loadState(authenticationContext: authenticationContext)
    }

    private func loadState(
        authenticationContext: LAContext?
    ) throws -> PersistedAppState {
        guard let stored = try readStoredProtectedState() else {
            throw ProfileStoreError.missingProtectedState
        }
        let bootstrap = try keychain.readStrict(
            SecurityBootstrapState.self,
            account: bootstrapAccount
        )
        guard canReadProtectedState(
            bootstrapLockEnabled: bootstrap?.lockEnabled ?? false,
            stateIsSealed: stored.isSealed,
            hasAuthenticatedContext: authenticationContext != nil
        ) else {
            throw ProfileStoreError.authenticationRequired
        }

        switch stored {
        case .plain(let state):
            if bootstrap?.lockEnabled == true {
                guard let authenticationContext else {
                    throw ProfileStoreError.authenticationRequired
                }
                try verifyAuthorizationSentinel(
                    bootstrap: bootstrap,
                    authenticationContext: authenticationContext
                )
            }
            clearCredentialSession()
            return state

        case .sealed(let envelope):
            guard let authenticationContext else {
                throw ProfileStoreError.authenticationRequired
            }
            let key = try readProtectedKey(
                account: envelope.keyAccount,
                authenticationContext: authenticationContext
            )
            let state = try open(envelope, using: key)
            activeProtectedStateKey = key
            activeProtectedKeyAccount = envelope.keyAccount

            let repairedBootstrap = SecurityBootstrapState(
                lockEnabled: true,
                keyAccount: envelope.keyAccount,
                keyProtection: envelope.keyProtection
            )
            if bootstrap != repairedBootstrap {
                try keychain.save(repairedBootstrap, account: bootstrapAccount)
            }
            return state
        }
    }

    /// Saves state using the current credential session. A lock transition additionally needs
    /// a freshly evaluated context. Enabling verifies that context by reading the newly-created
    /// protected key before credentials are sealed; disabling re-reads the existing key (or
    /// transition sentinel) before plaintext is published.
    func save(
        _ state: PersistedAppState,
        authorizationContext: LAContext? = nil
    ) throws {
        let bootstrap = try keychain.readStrict(
            SecurityBootstrapState.self,
            account: bootstrapAccount
        )
        let stored = try readStoredProtectedState()
        let currentLockEnabled = reconciledProtectedStateLock(
            bootstrapLockEnabled: bootstrap?.lockEnabled ?? false,
            stateIsSealed: stored?.isSealed ?? false
        )
        let nextLockEnabled = state.settings.biometricEnabled
        if lockTransitionRequiresFreshAuthentication(
            currentLockEnabled: currentLockEnabled,
            nextLockEnabled: nextLockEnabled
        ), authorizationContext == nil {
            throw ProfileStoreError.lockTransitionRequiresAuthentication
        }

        if nextLockEnabled {
            let envelope: ProtectedStateEnvelope
            var createdKeyAccount: String?
            if case .sealed(let currentEnvelope)? = stored,
               currentLockEnabled,
               activeProtectedKeyAccount == currentEnvelope.keyAccount,
               let activeProtectedStateKey {
                envelope = try seal(
                    state,
                    using: activeProtectedStateKey,
                    keyAccount: currentEnvelope.keyAccount,
                    keyProtection: currentEnvelope.keyProtection
                )
            } else {
                guard !currentLockEnabled, let authorizationContext else {
                    throw ProfileStoreError.lockTransitionRequiresAuthentication
                }
                envelope = try sealWithNewProtectedKey(
                    state,
                    authenticationContext: authorizationContext,
                    verifyAuthentication: true
                )
                createdKeyAccount = envelope.keyAccount
            }

            // Secure state first. If marker publication is interrupted, launch recognizes the
            // envelope itself and fails closed instead of attempting a plaintext read.
            var envelopeWasPublished = false
            do {
                try keychain.save(envelope, account: protectedAccount)
                envelopeWasPublished = true
                try keychain.save(
                    SecurityBootstrapState(
                        lockEnabled: true,
                        keyAccount: envelope.keyAccount,
                        keyProtection: envelope.keyProtection
                    ),
                    account: bootstrapAccount
                )
            } catch {
                // Before envelope publication a newly-created key is only an orphan. Once the
                // envelope is durable, retaining its key is essential: launch recognizes the
                // sealed value and can finish marker repair after authentication.
                if !envelopeWasPublished, let createdKeyAccount {
                    try? keychain.delete(account: createdKeyAccount)
                }
                if createdKeyAccount != nil {
                    clearCredentialSession()
                }
                throw error
            }
        } else {
            var keyAccountToDelete: String?
            if currentLockEnabled {
                guard let authorizationContext else {
                    throw ProfileStoreError.lockTransitionRequiresAuthentication
                }
                if case .sealed(let envelope)? = stored {
                    _ = try readProtectedKey(
                        account: envelope.keyAccount,
                        authenticationContext: authorizationContext
                    )
                    keyAccountToDelete = envelope.keyAccount
                } else {
                    try verifyAuthorizationSentinel(
                        bootstrap: bootstrap,
                        authenticationContext: authorizationContext
                    )
                    keyAccountToDelete = bootstrap?.keyAccount
                }
            }

            // Marker=false first while the durable payload is still sealed. If plaintext
            // publication is interrupted, launch sees the envelope, repairs marker=true, and
            // fails closed. There is never a crash point containing marker=true plus plaintext.
            try keychain.save(
                SecurityBootstrapState(lockEnabled: false),
                account: bootstrapAccount
            )
            try keychain.save(state, account: protectedAccount)
            if let keyAccountToDelete {
                // The state is already plaintext and the marker is disabled. An orphaned random
                // key cannot decrypt current data, so cleanup failure must not report a rollback.
                try? keychain.delete(account: keyAccountToDelete)
            }
            clearCredentialSession()
        }
        try keychain.delete(account: legacyAccount)
    }

    /// Drops the only in-process copy of the protected AES key. AppStore calls this before it
    /// clears profiles and invalidates request generations on every lock/background transition.
    func clearCredentialSession() {
        activeProtectedStateKey = nil
        activeProtectedKeyAccount = nil
    }

    private func readStoredProtectedState() throws -> StoredProtectedState? {
        guard let data = try keychain.readDataStrict(account: protectedAccount) else {
            return nil
        }
        if let envelope = try? JSONDecoder().decode(ProtectedStateEnvelope.self, from: data),
           envelope.schemaVersion == ProtectedStateEnvelope.currentSchemaVersion,
           !envelope.keyAccount.isEmpty,
           !envelope.sealedState.isEmpty {
            return .sealed(envelope)
        }
        if let state = try? JSONDecoder().decode(PersistedAppState.self, from: data) {
            return .plain(state)
        }
        throw ProfileStoreError.corruptProtectedState
    }

    private func sealWithNewProtectedKey(
        _ state: PersistedAppState,
        authenticationContext: LAContext?,
        verifyAuthentication: Bool
    ) throws -> ProtectedStateEnvelope {
        let key = SymmetricKey(size: .bits256)
        let keyData = key.withUnsafeBytes { Data($0) }
        let account = protectedKeyPrefix + UUID().uuidString
        let protection = try addProtectedKey(keyData, account: account)

        do {
            if verifyAuthentication {
                guard let authenticationContext else {
                    throw ProfileStoreError.lockTransitionRequiresAuthentication
                }
                guard let verified = try keychain.readDataStrict(
                    account: account,
                    authenticationContext: authenticationContext
                ), verified == keyData else {
                    throw ProfileStoreError.authenticationRequired
                }
            }
            let envelope = try seal(
                state,
                using: key,
                keyAccount: account,
                keyProtection: protection
            )
            activeProtectedStateKey = key
            activeProtectedKeyAccount = account
            return envelope
        } catch {
            try? keychain.delete(account: account)
            clearCredentialSession()
            throw mapKeychainAuthenticationError(error)
        }
    }

    private func addProtectedKey(
        _ data: Data,
        account: String
    ) throws -> ProtectedKeyProtection {
        do {
            try keychain.saveProtectedData(
                data,
                account: account,
                flags: .biometryCurrentSet
            )
            return .biometryCurrentSet
        } catch let primaryError {
            // `userPresence` is the compatibility path for devices/OS versions that cannot
            // create a current-biometry-set item. RootView still performs biometric evaluation;
            // this fallback avoids silently storing an unprotected key.
            do {
                try keychain.saveProtectedData(
                    data,
                    account: account,
                    flags: .userPresence
                )
                return .userPresence
            } catch {
                throw ProfileStoreError.protectedKeyUnavailable(
                    underlying: mapKeychainAuthenticationError(primaryError)
                )
            }
        }
    }

    private func readProtectedKey(
        account: String,
        authenticationContext: LAContext
    ) throws -> SymmetricKey {
        do {
            guard let data = try keychain.readDataStrict(
                account: account,
                authenticationContext: authenticationContext
            ) else {
                // biometryCurrentSet items are invalidated and commonly surface as not found
                // after the enrolled biometric set changes.
                throw ProfileStoreError.protectedKeyInvalidated
            }
            guard data.count == 32 else {
                throw ProfileStoreError.corruptProtectedState
            }
            return SymmetricKey(data: data)
        } catch {
            throw mapKeychainAuthenticationError(error)
        }
    }

    private func seal(
        _ state: PersistedAppState,
        using key: SymmetricKey,
        keyAccount: String,
        keyProtection: ProtectedKeyProtection
    ) throws -> ProtectedStateEnvelope {
        let encoded = try JSONEncoder().encode(state)
        let sealedBox = try AES.GCM.seal(encoded, using: key)
        guard let combined = sealedBox.combined else {
            throw ProfileStoreError.couldNotSealProtectedState
        }
        return ProtectedStateEnvelope(
            keyAccount: keyAccount,
            keyProtection: keyProtection,
            sealedState: combined
        )
    }

    private func open(
        _ envelope: ProtectedStateEnvelope,
        using key: SymmetricKey
    ) throws -> PersistedAppState {
        do {
            let sealedBox = try AES.GCM.SealedBox(combined: envelope.sealedState)
            let plaintext = try AES.GCM.open(sealedBox, using: key)
            return try JSONDecoder().decode(PersistedAppState.self, from: plaintext)
        } catch {
            throw ProfileStoreError.corruptProtectedState
        }
    }

    private func bootstrapWithAuthorizationSentinel(
        _ bootstrap: SecurityBootstrapState?
    ) throws -> SecurityBootstrapState {
        if let account = bootstrap?.keyAccount, !account.isEmpty {
            return SecurityBootstrapState(
                lockEnabled: true,
                keyAccount: account,
                keyProtection: bootstrap?.keyProtection ?? .userPresence
            )
        }
        let sentinel = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
        let account = protectedKeyPrefix + UUID().uuidString
        let protection = try addProtectedKey(sentinel, account: account)
        return SecurityBootstrapState(
            lockEnabled: true,
            keyAccount: account,
            keyProtection: protection
        )
    }

    private func verifyAuthorizationSentinel(
        bootstrap: SecurityBootstrapState?,
        authenticationContext: LAContext
    ) throws {
        guard let account = bootstrap?.keyAccount, !account.isEmpty else {
            throw ProfileStoreError.protectedKeyInvalidated
        }
        do {
            guard try keychain.readDataStrict(
                account: account,
                authenticationContext: authenticationContext
            ) != nil else {
                throw ProfileStoreError.protectedKeyInvalidated
            }
        } catch {
            throw mapKeychainAuthenticationError(error)
        }
    }

    private func mapKeychainAuthenticationError(_ error: Error) -> Error {
        if let profileError = error as? ProfileStoreError {
            return profileError
        }
        guard let keychainError = error as? KeychainError,
              let status = keychainError.status else {
            return error
        }
        switch status {
        case errSecInteractionNotAllowed, errSecAuthFailed, errSecUserCanceled:
            return ProfileStoreError.authenticationRequired
        case errSecNotAvailable:
            return ProfileStoreError.authenticationUnavailable
        default:
            return keychainError
        }
    }
}

private enum StoredProtectedState {
    case plain(PersistedAppState)
    case sealed(ProtectedStateEnvelope)

    var isSealed: Bool {
        if case .sealed = self { return true }
        return false
    }
}

enum ProtectedKeyProtection: String, Codable, Equatable {
    case biometryCurrentSet
    case userPresence
}

private struct ProtectedStateEnvelope: Codable {
    static let currentSchemaVersion = 2

    let schemaVersion: Int
    let keyAccount: String
    let keyProtection: ProtectedKeyProtection
    let sealedState: Data

    init(
        keyAccount: String,
        keyProtection: ProtectedKeyProtection,
        sealedState: Data
    ) {
        schemaVersion = Self.currentSchemaVersion
        self.keyAccount = keyAccount
        self.keyProtection = keyProtection
        self.sealedState = sealedState
    }
}

struct SecurityBootstrapState: Codable, Equatable {
    static let currentSchemaVersion = 2

    let schemaVersion: Int
    let lockEnabled: Bool
    let keyAccount: String?
    let keyProtection: ProtectedKeyProtection?

    init(
        lockEnabled: Bool,
        keyAccount: String? = nil,
        keyProtection: ProtectedKeyProtection? = nil
    ) {
        schemaVersion = Self.currentSchemaVersion
        self.lockEnabled = lockEnabled
        self.keyAccount = keyAccount
        self.keyProtection = keyProtection
    }

    private enum CodingKeys: String, CodingKey {
        case schemaVersion, lockEnabled, keyAccount, keyProtection
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try container.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? 1
        lockEnabled = try container.decode(Bool.self, forKey: .lockEnabled)
        keyAccount = try container.decodeIfPresent(String.self, forKey: .keyAccount)
        keyProtection = try container.decodeIfPresent(
            ProtectedKeyProtection.self,
            forKey: .keyProtection
        )
    }
}

enum ProfileStoreError: LocalizedError {
    case missingProtectedState
    case unsupportedBootstrapVersion
    case corruptProtectedState
    case couldNotSealProtectedState
    case authenticationRequired
    case authenticationUnavailable
    case lockTransitionRequiresAuthentication
    case protectedKeyInvalidated
    case protectedKeyUnavailable(underlying: Error)

    var errorDescription: String? {
        switch self {
        case .missingProtectedState:
            return "Protected app data is unavailable"
        case .unsupportedBootstrapVersion:
            return "Protected app data uses an unsupported security format"
        case .corruptProtectedState:
            return "Protected app data could not be decrypted"
        case .couldNotSealProtectedState:
            return "Protected app data could not be encrypted"
        case .authenticationRequired:
            return "Authentication is required to access protected app data"
        case .authenticationUnavailable:
            return "Secure authentication is unavailable on this device"
        case .lockTransitionRequiresAuthentication:
            return "Changing biometric lock requires fresh authentication"
        case .protectedKeyInvalidated:
            return "The protected key is unavailable. The enrolled biometric set may have changed"
        case .protectedKeyUnavailable:
            return "This device could not create a user-presence protected key"
        }
    }
}
