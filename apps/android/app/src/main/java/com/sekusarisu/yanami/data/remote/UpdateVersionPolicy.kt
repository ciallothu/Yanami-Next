package com.sekusarisu.yanami.data.remote

internal object UpdateVersionPolicy {
    private val releaseTagPattern =
            Regex("^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")

    data class ReleaseVersion(
            val tagName: String,
            val versionName: String,
            val semanticBaseCode: Int
    ) {
        val universalAssetName: String
            get() = "Yanami-Next-v$versionName-universal.apk"
    }

    fun parseReleaseTag(tagName: String): ReleaseVersion? {
        val match = releaseTagPattern.matchEntire(tagName) ?: return null
        val major = match.groupValues[1].toLongOrNull() ?: return null
        val minor = match.groupValues[2].toLongOrNull() ?: return null
        val patch = match.groupValues[3].toLongOrNull() ?: return null
        if (major > 2_000L || minor > 99L || patch > 99L) return null

        val semanticBaseCode = major * 1_000_000L + minor * 10_000L + patch * 100L
        if (semanticBaseCode > Int.MAX_VALUE) return null

        return ReleaseVersion(
                tagName = tagName,
                versionName = tagName.removePrefix("v"),
                semanticBaseCode = semanticBaseCode.toInt()
        )
    }

    fun selectVersionCode(
            releaseVersion: ReleaseVersion,
            metadataVersionName: String?,
            metadataVersionCode: Long?
    ): Int {
        if (metadataVersionName != releaseVersion.versionName || metadataVersionCode == null) {
            return releaseVersion.semanticBaseCode
        }

        val semanticBase = releaseVersion.semanticBaseCode.toLong()
        return metadataVersionCode
                .takeIf { it in semanticBase..(semanticBase + MAX_REVISION) }
                ?.toInt()
                ?: releaseVersion.semanticBaseCode
    }

    private const val MAX_REVISION = 99L
}
