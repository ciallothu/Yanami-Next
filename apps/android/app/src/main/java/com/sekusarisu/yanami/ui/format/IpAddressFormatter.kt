package com.sekusarisu.yanami.ui.format

/** Masks an address for display without mutating the value retained by the data layer. */
fun formatIpAddress(address: String, masked: Boolean): String {
    val value = address.trim()
    if (!masked || value.isEmpty() || '*' in value) return value

    if (':' in value) {
        val dottedSuffix = value.substringAfterLast(':')
        if (dottedSuffix.count { it == '.' } == 3) {
            val prefix = value.removeSuffix(dottedSuffix)
            return prefix + maskIpv4(dottedSuffix)
        }
        val firstHextet = value.split(':').firstOrNull { it.isNotBlank() }
        return if (value.startsWith("::") || firstHextet == null) "::*" else "$firstHextet:*:*:*:*:*:*:*"
    }

    return if (value.count { it == '.' } == 3) maskIpv4(value) else "••••"
}

private fun maskIpv4(address: String): String {
    val parts = address.split('.')
    if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return "*.*.*.*"
    return "${parts[0]}.${parts[1]}.*.*"
}
