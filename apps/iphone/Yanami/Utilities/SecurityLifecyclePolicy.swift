import Foundation

enum CredentialAccessPhase: Equatable {
    case locked
    case unlocked
}

struct CredentialRequestLease: Equatable {
    let generation: UInt64
}

struct ServerFormSaveLease: Equatable {
    let generation: UInt64
}

struct BiometricLockAuthorizationLease: Equatable {
    let generation: UInt64
}

enum ProtectedStateSaveOrder: Equatable {
    case securedStateFirst
    case disabledMarkerFirst
}

enum CredentialSceneTransition: Equatable {
    case inactive
    case background
}

enum LoginTwoFactorResponse: Equatable {
    case none
    case required
    case invalidCode
}

/// Missing or unreadable bootstrap state must never expose protected profiles.
func initialCredentialAccessPhase(
    bootstrapLoaded: Bool,
    lockEnabled: Bool
) -> CredentialAccessPhase {
    bootstrapLoaded && !lockEnabled ? .unlocked : .locked
}

/// Every request obtains a generation lease. Locking increments the generation, so responses
/// already in flight become unable to publish, retry, or persist authentication state.
func isCredentialRequestLeaseCurrent(
    _ lease: CredentialRequestLease,
    generation: UInt64,
    phase: CredentialAccessPhase
) -> Bool {
    phase == .unlocked && lease.generation == generation
}

/// Only one interactive code prompt may own a continuation at a time. Callers that race with an
/// existing challenge fail explicitly instead of leaking a continuation or reusing a TOTP code.
func canBeginTwoFactorChallenge(
    phase: CredentialAccessPhase,
    hasPendingChallenge: Bool
) -> Bool {
    phase == .unlocked && !hasPendingChallenge
}

/// Enabling publishes an already-encrypted state before marker=true. Disabling publishes
/// marker=false while the previous state is still sealed, then replaces it with plaintext. Launch
/// always treats a sealed value as locked, so both intermediate states fail closed.
func protectedStateSaveOrder(
    currentLockEnabled: Bool?,
    nextLockEnabled: Bool
) -> ProtectedStateSaveOrder {
    _ = currentLockEnabled
    return nextLockEnabled ? .securedStateFirst : .disabledMarkerFirst
}

/// The sealed envelope is an authoritative lock signal. This repairs either a missing marker or
/// marker=false left by a crash between the two writes of a disable transition.
func reconciledProtectedStateLock(
    bootstrapLockEnabled: Bool,
    stateIsSealed: Bool
) -> Bool {
    bootstrapLockEnabled || stateIsSealed
}

/// A sealed payload is never readable without an authenticated context, even if a crash left an
/// older marker. A locked plaintext payload represents an interrupted disable and also remains
/// behind a cryptographic authorization sentinel until that transition finishes.
func canReadProtectedState(
    bootstrapLockEnabled: Bool,
    stateIsSealed: Bool,
    hasAuthenticatedContext: Bool
) -> Bool {
    (!bootstrapLockEnabled && !stateIsSealed) || hasAuthenticatedContext
}

/// Both directions change the at-rest protection boundary and require a fresh user-presence
/// proof. Ordinary settings saves within one mode use the current unlocked credential session.
func lockTransitionRequiresFreshAuthentication(
    currentLockEnabled: Bool,
    nextLockEnabled: Bool
) -> Bool {
    currentLockEnabled != nextLockEnabled
}

/// After authenticated loading, a locked bootstrap paired with settings.lock=false means a
/// disable crashed after publishing its marker. The caller must finish plaintext publication
/// before exposing profiles as unlocked.
func shouldCompleteInterruptedLockDisable(
    credentialPhase: CredentialAccessPhase,
    persistedLockEnabled: Bool
) -> Bool {
    credentialPhase == .locked && !persistedLockEnabled
}

/// A LocalAuthentication sheet temporarily makes the scene inactive. Its result may change the
/// lock only while the same one-shot mutation lease is active and credentials remain unlocked;
/// background locking increments the generation and rejects every delayed callback.
func isBiometricLockAuthorizationCurrent(
    _ lease: BiometricLockAuthorizationLease,
    currentGeneration: UInt64,
    authorizationInProgress: Bool,
    credentialPhase: CredentialAccessPhase
) -> Bool {
    authorizationInProgress &&
        credentialPhase == .unlocked &&
        lease.generation == currentGeneration
}

/// System authentication UI can transiently make a scene inactive. Privacy covering still
/// applies, but only a real background transition unconditionally destroys the credential
/// session. Ordinary inactive transitions continue to lock when no app-owned auth is running.
func shouldLockCredentialsForSceneTransition(
    _ transition: CredentialSceneTransition,
    lockEnabled: Bool,
    rootAuthenticationInProgress: Bool,
    lockMutationAuthorizationInProgress: Bool
) -> Bool {
    guard lockEnabled else { return false }
    if transition == .background { return true }
    return !rootAuthenticationInProgress && !lockMutationAuthorizationInProgress
}

/// A pending enable attempt starts while the lock is still disabled, so background must cancel
/// its lease independently of whether an existing credential session needs locking.
func shouldCancelBiometricLockAuthorization(
    for transition: CredentialSceneTransition
) -> Bool {
    transition == .background
}

/// LocalAuthentication callbacks can arrive after the app backgrounds or a newer prompt starts.
/// Only the current generation may unlock, and only while the scene is active.
func canApplyLocalAuthenticationSuccess(
    attemptGeneration: UInt64,
    currentGeneration: UInt64,
    sceneIsActive: Bool,
    succeeded: Bool
) -> Bool {
    succeeded && sceneIsActive && attemptGeneration == currentGeneration
}

/// Saving a server performs network authentication before the profile can be committed. A form
/// dismissal or explicit cancellation advances the generation and cancels the task, preventing a
/// late login response from persisting or activating a profile after the sheet has gone away.
func canCommitPreparedServerSave(
    _ lease: ServerFormSaveLease,
    currentGeneration: UInt64,
    formAcceptsCompletion: Bool,
    taskIsCancelled: Bool
) -> Bool {
    formAcceptsCompletion && !taskIsCancelled && lease.generation == currentGeneration
}

/// Komari's login endpoint returns a small JSON envelope with these stable English messages.
/// Credential failures must remain generic; only an explicit 2FA/TOTP message starts a prompt.
func classifyKomariLoginTwoFactorMessage(_ message: String?) -> LoginTwoFactorResponse {
    guard let message else { return .none }
    let normalized = message.lowercased()
    let mentionsTwoFactor = normalized.contains("2fa") ||
        normalized.contains("two-factor") ||
        normalized.contains("two factor") ||
        normalized.contains("totp")
    guard mentionsTwoFactor else { return .none }
    if normalized.contains("required") { return .required }
    if normalized.contains("invalid") { return .invalidCode }
    return .none
}
