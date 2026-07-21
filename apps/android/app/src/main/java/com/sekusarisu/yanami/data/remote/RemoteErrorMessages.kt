package com.sekusarisu.yanami.data.remote

internal const val MAX_REMOTE_ERROR_MESSAGE_CHARS = 160

/**
 * Sanitizes a structured server error without ever reflecting a raw response body.
 * Control characters are collapsed and the user-visible result is bounded.
 */
internal fun safeRemoteErrorMessage(message: String?, statusCode: Int): String {
    val cleaned =
            message
                    .orEmpty()
                    .map { character ->
                        when {
                            !character.isISOControl() -> character
                            character.isWhitespace() -> ' '
                            else -> ' '
                        }
                    }
                    .joinToString(separator = "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(MAX_REMOTE_ERROR_MESSAGE_CHARS)
    return cleaned.ifBlank { "HTTP $statusCode" }
}

internal fun invalidRemoteResponseMessage(statusCode: Int): String =
        "HTTP $statusCode: 响应格式无效"

internal fun missingRemoteDataMessage(statusCode: Int): String =
        "HTTP $statusCode: 响应缺少 data"

class AdminApiException(val statusCode: Int, message: String) :
        Exception(safeRemoteErrorMessage(message, statusCode))
