import Foundation

@main
enum SecurityLifecyclePolicyTests {
    static func main() {
        expect(
            initialCredentialAccessPhase(bootstrapLoaded: true, lockEnabled: false) == .unlocked,
            "An explicitly disabled lock may load protected profiles"
        )
        expect(
            initialCredentialAccessPhase(bootstrapLoaded: true, lockEnabled: true) == .locked &&
                initialCredentialAccessPhase(bootstrapLoaded: false, lockEnabled: false) == .locked,
            "An enabled, missing, or unreadable lock marker must fail closed"
        )

        let lease = CredentialRequestLease(generation: 41)
        expect(
            isCredentialRequestLeaseCurrent(lease, generation: 41, phase: .unlocked),
            "A current unlocked request lease should be usable"
        )
        expect(
            !isCredentialRequestLeaseCurrent(lease, generation: 42, phase: .unlocked) &&
                !isCredentialRequestLeaseCurrent(lease, generation: 41, phase: .locked),
            "Locking and generation changes must invalidate every in-flight request"
        )

        expect(
            canBeginTwoFactorChallenge(phase: .unlocked, hasPendingChallenge: false) &&
                !canBeginTwoFactorChallenge(phase: .unlocked, hasPendingChallenge: true) &&
                !canBeginTwoFactorChallenge(phase: .locked, hasPendingChallenge: false),
            "2FA prompts must be serialized and forbidden while credentials are locked"
        )

        expect(
            protectedStateSaveOrder(
                currentLockEnabled: false,
                nextLockEnabled: true
            ) == .securedStateFirst &&
                protectedStateSaveOrder(
                    currentLockEnabled: true,
                    nextLockEnabled: false
                ) == .disabledMarkerFirst &&
                protectedStateSaveOrder(
                    currentLockEnabled: true,
                    nextLockEnabled: true
                ) == .securedStateFirst,
            "Transition ordering must keep credentials sealed at every incomplete boundary"
        )

        // Fault injection at every durable write boundary. Enabling is either still the old
        // intentionally-unlocked state or already locked; disabling remains sealed until the
        // final plaintext write has succeeded.
        expect(
            !reconciledProtectedStateLock(
                bootstrapLockEnabled: false,
                stateIsSealed: false
            ) &&
                reconciledProtectedStateLock(
                    bootstrapLockEnabled: false,
                    stateIsSealed: true
                ) &&
                reconciledProtectedStateLock(
                    bootstrapLockEnabled: true,
                    stateIsSealed: true
                ),
            "Every interrupted enable write must remain old-unlocked or fail closed"
        )
        expect(
            reconciledProtectedStateLock(
                bootstrapLockEnabled: true,
                stateIsSealed: true
            ) &&
                reconciledProtectedStateLock(
                    bootstrapLockEnabled: false,
                    stateIsSealed: true
                ) &&
                !reconciledProtectedStateLock(
                    bootstrapLockEnabled: false,
                    stateIsSealed: false
                ),
            "Every interrupted disable write must retain sealed credentials until completion"
        )

        expect(
            canReadProtectedState(
                bootstrapLockEnabled: false,
                stateIsSealed: false,
                hasAuthenticatedContext: false
            ) &&
                !canReadProtectedState(
                    bootstrapLockEnabled: true,
                    stateIsSealed: false,
                    hasAuthenticatedContext: false
                ) &&
                !canReadProtectedState(
                    bootstrapLockEnabled: false,
                    stateIsSealed: true,
                    hasAuthenticatedContext: false
                ) &&
                canReadProtectedState(
                    bootstrapLockEnabled: true,
                    stateIsSealed: true,
                    hasAuthenticatedContext: true
                ),
            "Locked, sealed, and interrupted-transition state must require authentication"
        )

        expect(
            lockTransitionRequiresFreshAuthentication(
                currentLockEnabled: false,
                nextLockEnabled: true
            ) &&
                lockTransitionRequiresFreshAuthentication(
                    currentLockEnabled: true,
                    nextLockEnabled: false
                ) &&
                !lockTransitionRequiresFreshAuthentication(
                    currentLockEnabled: true,
                    nextLockEnabled: true
                ),
            "Enabling and disabling require fresh authentication; ordinary saves do not"
        )

        expect(
            shouldCompleteInterruptedLockDisable(
                credentialPhase: .locked,
                persistedLockEnabled: false
            ) &&
                !shouldCompleteInterruptedLockDisable(
                    credentialPhase: .unlocked,
                    persistedLockEnabled: false
                ) &&
                !shouldCompleteInterruptedLockDisable(
                    credentialPhase: .locked,
                    persistedLockEnabled: true
                ),
            "Authenticated interrupted-disable recovery must publish the final plaintext state"
        )

        let biometricMutation = BiometricLockAuthorizationLease(generation: 31)
        expect(
            isBiometricLockAuthorizationCurrent(
                biometricMutation,
                currentGeneration: 31,
                authorizationInProgress: true,
                credentialPhase: .unlocked
            ) &&
                !isBiometricLockAuthorizationCurrent(
                    biometricMutation,
                    currentGeneration: 32,
                    authorizationInProgress: true,
                    credentialPhase: .unlocked
                ) &&
                !isBiometricLockAuthorizationCurrent(
                    biometricMutation,
                    currentGeneration: 31,
                    authorizationInProgress: false,
                    credentialPhase: .unlocked
                ) &&
                !isBiometricLockAuthorizationCurrent(
                    biometricMutation,
                    currentGeneration: 31,
                    authorizationInProgress: true,
                    credentialPhase: .locked
                ),
            "Background locking and stale biometric mutation callbacks must be rejected"
        )

        expect(
            !shouldLockCredentialsForSceneTransition(
                .inactive,
                lockEnabled: true,
                rootAuthenticationInProgress: false,
                lockMutationAuthorizationInProgress: true
            ) &&
                !shouldLockCredentialsForSceneTransition(
                    .inactive,
                    lockEnabled: true,
                    rootAuthenticationInProgress: true,
                    lockMutationAuthorizationInProgress: false
                ) &&
                shouldLockCredentialsForSceneTransition(
                    .inactive,
                    lockEnabled: true,
                    rootAuthenticationInProgress: false,
                    lockMutationAuthorizationInProgress: false
                ) &&
                shouldLockCredentialsForSceneTransition(
                    .background,
                    lockEnabled: true,
                    rootAuthenticationInProgress: true,
                    lockMutationAuthorizationInProgress: true
                ),
            "App-owned auth may survive inactive behind a privacy cover, never background"
        )
        expect(
            !shouldCancelBiometricLockAuthorization(for: .inactive) &&
                shouldCancelBiometricLockAuthorization(for: .background),
            "Background must cancel even a lock-enable attempt that began while lock was off"
        )

        expect(
            canApplyLocalAuthenticationSuccess(
                attemptGeneration: 9,
                currentGeneration: 9,
                sceneIsActive: true,
                succeeded: true
            ) &&
                !canApplyLocalAuthenticationSuccess(
                    attemptGeneration: 9,
                    currentGeneration: 10,
                    sceneIsActive: true,
                    succeeded: true
                ) &&
                !canApplyLocalAuthenticationSuccess(
                    attemptGeneration: 9,
                    currentGeneration: 9,
                    sceneIsActive: false,
                    succeeded: true
                ),
            "A stale or background LocalAuthentication callback must never unlock credentials"
        )

        let saveLease = ServerFormSaveLease(generation: 12)
        expect(
            canCommitPreparedServerSave(
                saveLease,
                currentGeneration: 12,
                formAcceptsCompletion: true,
                taskIsCancelled: false
            ),
            "A current visible server form may commit its prepared profile"
        )
        expect(
            !canCommitPreparedServerSave(
                saveLease,
                currentGeneration: 12,
                formAcceptsCompletion: true,
                taskIsCancelled: true
            ) &&
                !canCommitPreparedServerSave(
                    saveLease,
                    currentGeneration: 13,
                    formAcceptsCompletion: true,
                    taskIsCancelled: false
                ) &&
                !canCommitPreparedServerSave(
                    saveLease,
                    currentGeneration: 12,
                    formAcceptsCompletion: false,
                    taskIsCancelled: false
                ),
            "Cancellation after login, a newer save, or dismissal before commit must reject the result"
        )

        expect(
            classifyKomariLoginTwoFactorMessage("2FA code is required") == .required &&
                classifyKomariLoginTwoFactorMessage("Invalid 2FA code") == .invalidCode &&
                classifyKomariLoginTwoFactorMessage("Invalid credentials") == .none,
            "Only explicit Komari 2FA login responses may trigger the code workflow"
        )
    }

    private static func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
        guard condition() else { fatalError("SecurityLifecyclePolicyTests: \(message)") }
    }
}
