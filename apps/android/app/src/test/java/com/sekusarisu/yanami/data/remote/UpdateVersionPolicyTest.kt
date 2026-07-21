package com.sekusarisu.yanami.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateVersionPolicyTest {
    @Test
    fun `matching metadata revision is selected`() {
        val release = requireNotNull(UpdateVersionPolicy.parseReleaseTag("v0.1.0"))

        val selected =
                UpdateVersionPolicy.selectVersionCode(
                        releaseVersion = release,
                        metadataVersionName = "0.1.0",
                        metadataVersionCode = 10_001L
                )

        assertEquals(10_001, selected)
    }

    @Test
    fun `metadata for another semantic version falls back to tag base`() {
        val release = requireNotNull(UpdateVersionPolicy.parseReleaseTag("v0.1.0"))

        val selected =
                UpdateVersionPolicy.selectVersionCode(
                        releaseVersion = release,
                        metadataVersionName = "0.1.1",
                        metadataVersionCode = 10_101L
                )

        assertEquals(10_000, selected)
    }

    @Test
    fun `revision outside reserved range falls back to tag base`() {
        val release = requireNotNull(UpdateVersionPolicy.parseReleaseTag("v0.1.0"))

        assertEquals(
                10_000,
                UpdateVersionPolicy.selectVersionCode(release, "0.1.0", 9_999L)
        )
        assertEquals(
                10_000,
                UpdateVersionPolicy.selectVersionCode(release, "0.1.0", 10_100L)
        )
    }

    @Test
    fun `missing metadata fields fall back to tag base`() {
        val release = requireNotNull(UpdateVersionPolicy.parseReleaseTag("v0.1.0"))

        assertEquals(10_000, UpdateVersionPolicy.selectVersionCode(release, null, 10_001L))
        assertEquals(10_000, UpdateVersionPolicy.selectVersionCode(release, "0.1.0", null))
    }

    @Test
    fun `tag parser accepts canonical tag and rejects non-semver or leading zero tags`() {
        val release = UpdateVersionPolicy.parseReleaseTag("v12.34.56")

        assertNotNull(release)
        assertEquals("12.34.56", release?.versionName)
        assertEquals(12_345_600, release?.semanticBaseCode)
        assertEquals("Yanami-Next-v12.34.56-universal.apk", release?.universalAssetName)
        assertNull(UpdateVersionPolicy.parseReleaseTag("12.34.56"))
        assertNull(UpdateVersionPolicy.parseReleaseTag("v0.1.0-rc.1"))
        assertNull(UpdateVersionPolicy.parseReleaseTag("v00.1.0"))
    }
}
