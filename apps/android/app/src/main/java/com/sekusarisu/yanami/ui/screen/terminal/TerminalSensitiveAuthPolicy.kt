package com.sekusarisu.yanami.ui.screen.terminal

import com.sekusarisu.yanami.domain.model.AuthType

/**
 * Whether a terminal handshake must pause for a fresh sensitive-operation code.
 *
 * [passwordAuthenticationWasRejected] supports profiles saved by older app versions before the
 * account's 2FA capability was persisted. API keys stay exempt exactly as Komari specifies.
 */
internal fun requiresTerminalSensitiveTwoFactor(
        authType: AuthType,
        profileRequiresTwoFactor: Boolean,
        passwordAuthenticationWasRejected: Boolean
): Boolean =
        authType == AuthType.PASSWORD &&
                (profileRequiresTwoFactor || passwordAuthenticationWasRejected)

internal fun shouldRememberTerminalTwoFactorHint(
        authType: AuthType,
        isAuthenticationFailure: Boolean
): Boolean = authType == AuthType.PASSWORD && isAuthenticationFailure
