package com.raulshma.lenscast.core

import java.math.BigInteger
import java.security.KeyPair
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Calendar

/**
 * Minimal self-signed X.509v3 certificate builder over hand-rolled DER —
 * no BouncyCastle dependency (a multi-MB APK cost for one certificate).
 * The profile is fixed: RSA (the caller's [KeyPair]), SHA256withRSA, CN=
 * LensCast, serverAuth EKU, digitalSignature|keyEncipherment key usage,
 * CA:false, and a subjectAltName carrying DNS names (the caller's
 * [dnsNames] — the fixed `lenscast.local` bookmarkable host) plus the
 * device's IPv4 and IPv6 addresses (browsers match https URLs against SAN
 * entries, not CN).
 *
 * JVM-tested: the produced bytes parse through the standard
 * [CertificateFactory] and the signature verifies against the key pair.
 */
object SelfSignedCert {

    /** DER-encode a self-signed certificate for [keyPair]. */
    fun generate(
        keyPair: KeyPair,
        subjectCn: String = "LensCast",
        ipAddresses: List<String>,
        dnsNames: List<String> = emptyList(),
        validityDays: Int = 3650,
        notBefore: java.util.Date = java.util.Date(),
    ): ByteArray {
        val calendar = Calendar.getInstance().apply {
            time = notBefore
            add(Calendar.DAY_OF_YEAR, validityDays)
        }
        val notAfter = calendar.time
        val serial = BigInteger(63, java.security.SecureRandom()).abs().add(BigInteger.ONE)

        val tbs = encodeTbs(keyPair, subjectCn, ipAddresses, dnsNames, serial, notBefore, notAfter)
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(tbs)
        val signature = signer.sign()

        val sigAlg = sequence(oid(1, 2, 840, 113549, 1, 1, 11) + asn1Null())
        val sigValue = bitString(signature)
        return sequence(tbs + sigAlg + sigValue)
    }

    /** Parse-and-verify helper used by tests and by [TlsCertManager] on load. */
    fun parse(der: ByteArray): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(der.inputStream()) as X509Certificate
    }

    private fun encodeTbs(
        keyPair: KeyPair,
        cn: String,
        ips: List<String>,
        dnsNames: List<String>,
        serial: BigInteger,
        notBefore: java.util.Date,
        notAfter: java.util.Date,
    ): ByteArray {
        val version = contextTag(0xA0, integer(2))
        val serialDer = integer(serial)
        val sigAlg = sequence(oid(1, 2, 840, 113549, 1, 1, 11) + asn1Null())
        val name = nameDer(cn)
        val validity = sequence(utcTime(notBefore) + utcTime(notAfter))
        val spki = keyPair.public.encoded // already X.509 SubjectPublicKeyInfo DER
        val extensions = contextTag(
            0xA3,
            sequence(
                // basicConstraints: CA=false
                sequence(oid(2, 5, 29, 19) + octetString(sequence(ByteArray(0)))) +
                    // keyUsage: digitalSignature (0) | keyEncipherment (2)
                    sequence(
                        oid(2, 5, 29, 15) + octetString(
                            bitStringRaw(byteArrayOf(0xA0.toByte()), unusedBits = 5),
                        )
                    ) +
                    // extKeyUsage: serverAuth
                    sequence(
                        oid(2, 5, 29, 37) + octetString(
                            sequence(oid(1, 3, 6, 1, 5, 5, 7, 3, 1)),
                        )
                    ) +
                    // subjectAltName: DNS names as [2] IA5String entries plus
                    // IP addresses as [7] OCTET STRING entries
                    sequence(
                        oid(2, 5, 29, 17) + octetString(
                            sequence(
                                (dnsNames.map { dnsNameDer(it) } + ips.mapNotNull { ipToDer(it) })
                                    .toTypedArray()
                                    .fold(ByteArray(0), ByteArray::plus),
                            ),
                        )
                    ),
            ),
        )
        return sequence(
            version + serialDer + sigAlg + name + validity + name + spki + extensions,
        )
    }

    private fun nameDer(cn: String): ByteArray = sequence(
        set(
            sequence(oid(2, 5, 4, 3) + utf8String(cn)),
        ),
    )

    private fun set(content: ByteArray): ByteArray = tagged(0x31, content)

    private fun ipToDer(ip: String): ByteArray? {
        // Zone ids (fe80::1%wlan0) are link-scope metadata, never part of the
        // certificate's address bytes.
        val literal = ip.trim().substringBefore('%')
        val bytes = when {
            literal.contains(':') -> ipv6ToBytes(literal)
            else -> ipv4ToBytes(literal)
        } ?: return null
        return contextTag(0x87, bytes) // [7] IMPLICIT OCTET STRING (iPAddress)
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

    /**
     * DER-encode a GeneralName dNSName: `[2]` context primitive over the
     * host's ASCII (IA5String IMPLICIT) bytes. Public for tests.
     */
    fun dnsNameDer(host: String): ByteArray =
        contextTag(0x82, host.toByteArray(Charsets.US_ASCII)) // [2] IMPLICIT IA5String (dNSName)

    // ── DER primitives ──

    private fun sequence(vararg children: ByteArray): ByteArray = sequence(children.fold(ByteArray(0), ByteArray::plus))

    private fun sequence(content: ByteArray): ByteArray = tagged(0x30, content)

    private fun tagged(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + encodeLength(content.size) + content

    private fun contextTag(tag: Int, content: ByteArray): ByteArray = tagged(tag, content)

    private fun integer(value: Long): ByteArray {
        val bytes = ByteArray(8) { i -> (value shr ((7 - i) * 8)).toByte() }
        return integer(bytes)
    }

    private fun integer(value: BigInteger): ByteArray = integer(value.toByteArray())

    private fun integer(magnitude: ByteArray): ByteArray {
        // Strip redundant leading zero bytes (keep one when required by sign bit).
        var start = 0
        while (start < magnitude.size - 1 && magnitude[start] == 0.toByte() && magnitude[start + 1].toInt() and 0x80 == 0) start++
        val content = magnitude.copyOfRange(start, magnitude.size)
        return tagged(0x02, content)
    }

    private fun oid(vararg arcs: Int): ByteArray {
        val body = mutableListOf<Byte>()
        body.add((arcs[0] * 40 + arcs[1]).toByte())
        for (i in 2 until arcs.size) {
            var value = arcs[i].toLong()
            val temp = mutableListOf<Byte>()
            temp.add((value and 0x7F).toByte())
            value = value shr 7
            while (value > 0) {
                temp.add(((value and 0x7F) or 0x80).toByte())
                value = value shr 7
            }
            temp.reverse()
            body.addAll(temp)
        }
        return tagged(0x06, body.toByteArray())
    }

    private fun utf8String(value: String): ByteArray = tagged(0x0C, value.toByteArray(Charsets.UTF_8))

    private fun utcTime(date: java.util.Date): ByteArray {
        val format = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return tagged(0x17, format.format(date).toByteArray(Charsets.US_ASCII))
    }

    private fun octetString(content: ByteArray): ByteArray = tagged(0x04, content)

    private fun bitString(content: ByteArray): ByteArray = tagged(0x03, byteArrayOf(0) + content)

    private fun bitStringRaw(content: ByteArray, unusedBits: Int): ByteArray = tagged(0x03, byteArrayOf(unusedBits.toByte()) + content)

    private fun asn1Null(): ByteArray = byteArrayOf(0x05, 0x00)

    private fun encodeLength(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length <= 0xFF -> byteArrayOf(0x81.toByte(), length.toByte())
        length <= 0xFFFF -> byteArrayOf(
            0x82.toByte(),
            (length shr 8).toByte(),
            length.toByte(),
        )
        else -> byteArrayOf(
            0x83.toByte(),
            (length shr 16).toByte(),
            (length shr 8).toByte(),
            length.toByte(),
        )
    }
}
