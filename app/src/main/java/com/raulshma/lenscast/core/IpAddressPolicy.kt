package com.raulshma.lenscast.core

import java.net.InetAddress

/**
 * IP literal parsing shared by the TLS identity: [SelfSignedCert] needs the
 * address bytes for the SAN extension, [TlsCertManager] needs a canonical
 * spelling to compare the interface list against parsed SANs. Zone ids
 * (`fe80::1%wlan0`) are link-scope metadata and never part of either form.
 *
 * Pure (no Android types), so the IPv6 edge cases stay JVM-testable: a
 * malformed interface string answers null, never throws.
 */
object IpAddressPolicy {

    /** The 4- or 16-byte address behind [value], or null when it is not a literal. */
    fun toBytes(value: String): ByteArray? {
        val literal = value.trim().substringBefore('%')
        if (literal.isEmpty()) return null
        return if (literal.contains(':')) ipv6ToBytes(literal) else ipv4ToBytes(literal)
    }

    /**
     * The canonical spelling of [value] — zones stripped and IPv6 expanded by
     * [InetAddress] — so the interface list and the parsed SANs compare equal
     * even when the platform spells the same address differently. Null for
     * non-IP values.
     */
    fun canonical(value: String): String? {
        val bytes = toBytes(value) ?: return null
        return runCatching { InetAddress.getByAddress(bytes).hostAddress }
            .getOrNull()
            ?.substringBefore('%')
    }

    private fun ipv4ToBytes(ip: String): ByteArray? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.any { !it.isDigit() }) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            bytes[index] = value.toByte()
        }
        return bytes
    }

    /**
     * Parse an IPv6 literal (compressed `::`, full eight groups, or a
     * trailing embedded IPv4) into its 16 address bytes. The device exposes
     * link-local IPv6 addresses alongside the LAN IPv4 one; without these
     * SANs the identity check saw them missing on every start and
     * regenerated the certificate each time.
     */
    private fun ipv6ToBytes(ip: String): ByteArray? {
        val firstDoubleColon = ip.indexOf("::")
        if (firstDoubleColon >= 0 && ip.indexOf("::", firstDoubleColon + 1) >= 0) return null
        val headText = if (firstDoubleColon >= 0) ip.substring(0, firstDoubleColon) else ip
        val tailText = if (firstDoubleColon >= 0) ip.substring(firstDoubleColon + 2) else ""
        val head = parseIpv6Groups(headText) ?: return null
        val tail = parseIpv6Groups(tailText) ?: return null
        val groups = if (firstDoubleColon >= 0) {
            val missing = 8 - head.size - tail.size
            if (missing < 1) return null
            head + List(missing) { 0 } + tail
        } else {
            if (head.size != 8) return null
            head
        }
        val bytes = ByteArray(16)
        for ((index, group) in groups.withIndex()) {
            bytes[index * 2] = (group shr 8).toByte()
            bytes[index * 2 + 1] = group.toByte()
        }
        return bytes
    }

    /** Sixteen-bit groups; a trailing dotted quad occupies the last two groups. */
    private fun parseIpv6Groups(text: String): List<Int>? {
        if (text.isEmpty()) return emptyList()
        val parts = text.split(":")
        if (parts.any { it.isEmpty() }) return null
        val groups = mutableListOf<Int>()
        for ((index, part) in parts.withIndex()) {
            if (part.contains('.')) {
                if (index != parts.lastIndex) return null
                val v4 = ipv4ToBytes(part) ?: return null
                groups.add(((v4[0].toInt() and 0xFF) shl 8) or (v4[1].toInt() and 0xFF))
                groups.add(((v4[2].toInt() and 0xFF) shl 8) or (v4[3].toInt() and 0xFF))
            } else {
                if (part.length > 4 || part.any { it.digitToIntOrNull(16) == null }) return null
                groups.add(part.toInt(16))
            }
        }
        return groups
    }
}
