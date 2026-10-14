package com.iptvapp.cast

import java.net.Inet4Address
import java.net.InetAddress

/**
 * Which of this phone's addresses a Chromecast can reach, and what to tell the person when a
 * cast doesn't start. Android-free so both are unit-tested.
 */
object CastNetwork {

    /** One IPv4 address of one of this phone's network interfaces. */
    data class Candidate(val interfaceName: String, val address: Inet4Address, val prefixLength: Int)

    // Mobile data, VPNs and tethering plumbing: never where a Chromecast is. Phones keep mobile
    // data connected alongside Wi-Fi ("Mobile data always active"), and its interface can be
    // listed first.
    private val unreachable = Regex("^(rmnet|ccmni|pdp|v4-|clat|tun|ppp|ipsec|dummy|ifb|sit|ip6|p2p).*", RegexOption.IGNORE_CASE)
    private val local = Regex("^(wlan|eth|ap|swlan|en|wl|br).*", RegexOption.IGNORE_CASE)

    /**
     * The address to serve a cast from: the one on the same subnet as the [receiver] when it's
     * known, else a Wi-Fi or Ethernet one, else anything but mobile data or a VPN. Null when
     * there's none.
     */
    fun pickAddress(candidates: List<Candidate>, receiver: InetAddress?): String? {
        val usable = candidates.filter { !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress }
        if (receiver is Inet4Address) {
            usable.firstOrNull { sameSubnet(it.address, receiver, it.prefixLength) }?.let { return it.address.hostAddress }
        }
        return (usable.firstOrNull { local.matches(it.interfaceName) }
            ?: usable.firstOrNull { !unreachable.matches(it.interfaceName) })?.address?.hostAddress
    }

    fun sameSubnet(a: Inet4Address, b: Inet4Address, prefixLength: Int): Boolean {
        val bits = prefixLength.coerceIn(0, 32)
        if (bits == 0) return false
        val mask = if (bits == 32) -1 else (-1 shl (32 - bits))
        return (toInt(a) and mask) == (toInt(b) and mask)
    }

    private fun toInt(a: Inet4Address): Int =
        a.address.fold(0) { acc, byte -> (acc shl 8) or (byte.toInt() and 0xff) }

    /**
     * What to say when the Chromecast reports an error, or never starts playing.
     * [proxied]: the cast went through this phone's proxy (false: no Wi-Fi address was found).
     * [receiverRequests]: requests the Chromecast made to the proxy. [upstreamStatus]: the
     * provider's last HTTP status through the proxy (-1 no answer, null not asked yet).
     */
    fun problemMessage(proxied: Boolean, receiverRequests: Int, upstreamStatus: Int?): String = when {
        !proxied -> "Couldn't find this phone's Wi-Fi address. Connect to Wi-Fi and cast again."
        receiverRequests == 0 -> "The TV couldn't reach this phone. Put both on the same Wi-Fi network, then cast again."
        upstreamStatus == -1 -> "Your provider didn't answer. Try casting again in a moment."
        upstreamStatus != null && upstreamStatus !in 200..399 ->
            "Your provider refused the stream (error $upstreamStatus). Close this channel on other devices, then cast again."
        else -> "The TV couldn't play this channel. Try casting again, or another channel."
    }
}
