package com.sekusarisu.yanami.ui.widget

import android.content.Intent
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPackageReplacedReceiverTest {

    @Test
    fun `only own package replacement triggers privacy clear`() {
        assertTrue(isOwnPackageReplacement(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse(isOwnPackageReplacement(Intent.ACTION_PACKAGE_REPLACED))
        assertFalse(isOwnPackageReplacement(Intent.ACTION_PACKAGE_ADDED))
        assertFalse(isOwnPackageReplacement(null))
    }

    @Test
    fun `privacy clear removes cached widget payload and preserves unrelated state`() {
        val unrelatedKey = stringPreferencesKey("unrelated")
        val preferences =
                mutablePreferencesOf(
                        WIDGET_STATE_KEY to
                                """{"serverName":"private.example","totalTrafficUp":42}""",
                        unrelatedKey to "keep"
                )

        val cleared = clearPersistedWidgetState(preferences)

        assertNull(cleared[WIDGET_STATE_KEY])
        assertEquals("keep", cleared[unrelatedKey])
    }
}
