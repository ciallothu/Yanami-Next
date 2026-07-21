package com.sekusarisu.yanami.ui.screen.server

import com.sekusarisu.yanami.domain.model.AuthType

/** A successful password request carrying an explicit ASCII TOTP proves the profile uses 2FA. */
internal fun requiresTwoFactorAfterSuccessfulAuthentication(
        authType: AuthType,
        submittedCode: String?,
        previouslyRequired: Boolean
): Boolean =
        authType == AuthType.PASSWORD &&
                (previouslyRequired || submittedCode.isAsciiSixDigitCode())

private fun String?.isAsciiSixDigitCode(): Boolean {
    val normalized = this?.trim().orEmpty()
    return normalized.length == 6 && normalized.all { it in '0'..'9' }
}
