package com.iptvapp.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class CastNetworkTest {

    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private fun c(name: String, address: String, prefix: Int = 24) = CastNetwork.Candidate(name, ip(address), prefix)

    // Mobile data listed before Wi-Fi: the old "first IPv4 address" sent the Chromecast to it.
    private val phone = listOf(c("rmnet_data1", "10.170.33.5", 30), c("wlan0", "192.168.50.20"), c("tun0", "10.8.0.2"))

    @Test fun picksTheAddressOnTheChromecastsNetwork() {
        assertEquals("192.168.50.20", CastNetwork.pickAddress(phone, ip("192.168.50.77")))
    }

    @Test fun withoutTheChromecastsAddressWifiBeatsMobileDataAndVpn() {
        assertEquals("192.168.50.20", CastNetwork.pickAddress(phone, null))
        // A receiver on no network of ours: still Wi-Fi, never mobile data.
        assertEquals("192.168.50.20", CastNetwork.pickAddress(phone, ip("172.16.0.9")))
    }

    @Test fun aReceiverOnTheVpnsSubnetGetsTheVpnAddress() {
        assertEquals("10.8.0.2", CastNetwork.pickAddress(phone, ip("10.8.0.40")))
    }

    @Test fun onlyMobileDataOrVpnMeansNoAddress() {
        assertNull(CastNetwork.pickAddress(listOf(c("rmnet_data0", "10.1.2.3"), c("tun0", "10.8.0.2")), null))
    }

    @Test fun anUnknownInterfaceIsUsedWhenNothingBetterExists() {
        assertEquals("192.168.1.4", CastNetwork.pickAddress(listOf(c("rmnet0", "10.1.2.3"), c("usb0", "192.168.1.4")), null))
    }

    @Test fun subnetsCompareByPrefix() {
        assertTrue(CastNetwork.sameSubnet(ip("192.168.50.20"), ip("192.168.50.200"), 24))
        assertFalse(CastNetwork.sameSubnet(ip("192.168.50.20"), ip("192.168.51.20"), 24))
        assertTrue(CastNetwork.sameSubnet(ip("192.168.50.20"), ip("192.168.51.20"), 16))
        assertFalse(CastNetwork.sameSubnet(ip("10.0.0.1"), ip("10.0.0.1"), 0))
    }

    @Test fun theMessageSaysWhatWentWrong() {
        assertTrue(CastNetwork.problemMessage(false, 0, null).contains("Wi-Fi address"))
        assertTrue(CastNetwork.problemMessage(true, 0, null).contains("couldn't reach this phone"))
        assertTrue(CastNetwork.problemMessage(true, 3, -1).contains("didn't answer"))
        assertTrue(CastNetwork.problemMessage(true, 3, 509).contains("error 509"))
        assertTrue(CastNetwork.problemMessage(true, 3, 200).contains("couldn't play"))
    }
}
