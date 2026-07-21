package com.sekusarisu.yanami.data.remote

import java.net.URI

/** Validates and normalizes the origin used for authenticated Komari requests. */
fun normalizeServerBaseUrl(rawValue: String): String {
    val value = rawValue.trim().trimEnd('/')
    require(value.isNotBlank()) { "Server URL is required" }
    require(value.none { it.code < 0x20 || it.code == 0x7f }) {
        "Server URL contains control characters"
    }

    val uri =
            runCatching { URI(value) }
                    .getOrElse { throw IllegalArgumentException("Invalid server URL") }
    require(uri.scheme.equals("https", true)) {
        "Server URL must use HTTPS"
    }
    require(!uri.host.isNullOrBlank()) { "Server URL must include a valid host" }
    require(uri.rawUserInfo == null) { "Server URL must not contain user information" }
    require(uri.rawQuery == null && uri.rawFragment == null) {
        "Server URL must not contain a query or fragment"
    }
    return value
}
