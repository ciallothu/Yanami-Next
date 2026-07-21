package com.sekusarisu.yanami.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test

class IpAddressFormatterTest {
    @Test fun leavesAddressVisibleWhenMaskingIsDisabled() {
        assertEquals("192.0.2.15", formatIpAddress("192.0.2.15", false))
    }

    @Test fun masksIpv4AndIpv6WithoutRevealingHostBits() {
        assertEquals("192.0.*.*", formatIpAddress("192.0.2.15", true))
        assertEquals("2001:*:*:*:*:*:*:*", formatIpAddress("2001:db8::1234", true))
        assertEquals("::*", formatIpAddress("::1", true))
    }

    @Test fun preservesServerMaskedAndEmptyAddresses() {
        assertEquals("1.*.*.*", formatIpAddress("1.*.*.*", true))
        assertEquals("", formatIpAddress("", true))
    }
}
