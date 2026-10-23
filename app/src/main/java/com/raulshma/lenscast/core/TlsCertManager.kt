package com.raulshma.lenscast.core

import android.content.Context
import android.util.Log
import java.io.File
import java.security.KeyPair
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

/**
 * Owns the phone's TLS identity: a persistent RSA-2048 key pair plus a
 * self-signed certificate (via [SelfSignedCert]) in app-private storage.
 * The certificate is regenerated when absent, unverifiable, no longer
 * matches the stored key pair, or when the device's LAN addresses no longer
 * appear in its subjectAltName — browsers
 * match https URLs against SAN entries, so a network move needs a new cert
 * and a one-tap browser exception. Every served certificate also carries the
 * fixed DNS SAN [STABLE_HOST] (`lenscast.local`), so bookmarks using that
 * name keep validating wherever the network resolves it (LensCast does not
 * advertise the name itself; see docs/remote-access.md); the Connect sheet
 * (camera/, not owned here) shows the fingerprint, and this constant is the
 * stable hostname surface.
 *
 * The user-facing trust model is QR/toe-print verification: [fingerprint]
 * is the SHA-256 digest of the DER certificate, shown on the Connect sheet.
 */
class TlsCertManager(private val context: Context) {

    private val lock = Any()

    data class Identity(
        val serverSocketFactory: SSLServerSocketFactory,
        val certificate: X509Certificate,
        val fingerprint: String,
    )

    private var cached: Identity? = null

    /**
     * The current identity, generating or regenerating as needed.
     * [currentIps] should be the device's live LAN addresses. IPv4 and IPv6
     * SANs compare in canonical form: the interfaces spell IPv6 addresses
     * compressed/expanded and with zone suffixes, and a raw string compare
     * treated every start as a network move (regenerating the certificate,
     * and with it the fingerprint, on every TLS apply).
     */
    fun identity(currentIps: List<String>): Identity {
        synchronized(lock) {
            val wanted = canonicalIps(currentIps)
            cached?.let { existing ->
                val sans = canonicalIps(certificateIps(existing.certificate))
                if (sans.containsAll(wanted)) return existing
                Log.d(TAG, "LAN addresses changed $sans -> $wanted; regenerating certificate")
            }
            val identity = loadOrCreate(currentIps)
            cached = identity
            return identity
        }
    }

    /** Force a new key pair + certificate (e.g. the "regenerate" button). */
    fun regenerate(currentIps: List<String>): Identity {
        synchronized(lock) {
            keyFile.delete()
            certFile.delete()
            cached = null
            return loadOrCreate(currentIps)
        }
    }

    private fun loadOrCreate(currentIps: List<String>): Identity {
        ensureTlsDirectory()
        val keyPair = loadOrCreateKey()
        // Only literal addresses can become SAN bytes; hostnames carry no
        // iPAddress entry and would otherwise force a regeneration on every
        // load. Deduplicate canonical forms so an interface alias list
        // (`fe80::1` and `fe80::1%wlan0`) does not inflate the certificate.
        val encodableIps = currentIps
            .filter { IpAddressPolicy.canonical(it) != null }
            .distinctBy { IpAddressPolicy.canonical(it) }
        val wanted = canonicalIps(encodableIps)
        val certDer = certFile.takeIf { it.exists() }?.readBytes()
        val certificate = certDer?.let { der ->
            runCatching { SelfSignedCert.parse(der) }.getOrNull()
                ?.takeIf { cert ->
                    // The keystore can be rebuilt independently (unreadable
                    // p12): a stale DER paired with the new private key would
                    // serve a certificate whose key does not match, failing
                    // every handshake. Regenerate whenever they disagree.
                    publicKeysMatch(cert, keyPair) &&
                        canonicalIps(certificateIps(cert)).containsAll(wanted)
                }
        }
        val finalCert = certificate ?: run {
            val created = SelfSignedCert.generate(
                keyPair,
                ipAddresses = encodableIps,
                dnsNames = listOf(STABLE_HOST),
            )
            certFile.writeBytes(created)
            SelfSignedCert.parse(created)
        }
        return Identity(
            serverSocketFactory = serverSocketFactoryFor(keyPair, finalCert),
            certificate = finalCert,
            fingerprint = fingerprintOf(certFile.readBytes()),
        )
    }

    /**
     * The tls/ folder under filesDir does not exist on a fresh install;
     * every file write below throws ENOENT without it (issue #8 — the
     * silent plain-HTTP fallback the HTTPS toggle's failure produced).
     */
    private fun ensureTlsDirectory() {
        keyFile.parentFile?.mkdirs()
    }

    private fun loadOrCreateKey(): KeyPair {
        if (keyFile.exists()) {
            runCatching {
                val bytes = keyFile.readBytes()
                val keyStore = KeyStore.getInstance(KEYSTORE_TYPE).apply {
                    load(bytes.inputStream(), KEY_PASSWORD)
                }
                val key = keyStore.getKey(ALIAS, KEY_PASSWORD) as java.security.PrivateKey
                val certChain = keyStore.getCertificateChain(ALIAS)
                if (certChain != null && certChain.isNotEmpty()) {
                    val publicKey = certChain[0].publicKey
                    return KeyPair(publicKey, key)
                }
            }.onFailure { Log.w(TAG, "Stored keystore unreadable; regenerating key pair", it) }
        }
        val generator = java.security.KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom())
        val keyPair = generator.generateKeyPair()
        // Persist through a PKCS12 keystore so the private key never sits in
        // plaintext on disk.
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        keyStore.load(null, null)
        val selfDer = SelfSignedCert.generate(keyPair, ipAddresses = listOf("127.0.0.1"))
        val self = SelfSignedCert.parse(selfDer)
        keyStore.setKeyEntry(ALIAS, keyPair.private, KEY_PASSWORD, arrayOf<java.security.cert.Certificate>(self))
        keyFile.outputStream().use { keyStore.store(it, KEY_PASSWORD) }
        return keyPair
    }

    private fun serverSocketFactoryFor(keyPair: KeyPair, certificate: X509Certificate): SSLServerSocketFactory {
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        keyStore.load(null, null)
        keyStore.setKeyEntry(ALIAS, keyPair.private, KEY_PASSWORD, arrayOf(certificate))
        val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore, KEY_PASSWORD)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(factory.keyManagers, null, null)
        return sslContext.serverSocketFactory
    }

    private fun certificateIps(certificate: X509Certificate): List<String> = try {
        certificate.subjectAlternativeNames
            ?.mapNotNull { entry ->
                @Suppress("UNCHECKED_CAST")
                val list = entry as List<Any>
                if ((list[0] as Int) != 7) return@mapNotNull null
                when (val value = list[1]) {
                    is String -> value
                    // Some providers hand back the DER bytes rather than the
                    // textual form; canonicalize through InetAddress either way.
                    is ByteArray -> runCatching { java.net.InetAddress.getByAddress(value).hostAddress }.getOrNull()
                    else -> null
                }
            }
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Compare the interface list and the parsed SANs in the canonical form
     * [IpAddressPolicy] defines — zones stripped, IPv6 expanded — so the
     * platform's spelling of the same address never reads as a network move.
     */
    private fun canonicalIps(values: List<String>): Set<String> =
        values.mapNotNull { IpAddressPolicy.canonical(it) }.toSet()

    private fun publicKeysMatch(certificate: X509Certificate, keyPair: KeyPair): Boolean = runCatching {
        certificate.publicKey.encoded.contentEquals(keyPair.public.encoded)
    }.getOrDefault(false)

    private val keyFile: File get() = File(context.filesDir, "tls/lenscast.p12")
    private val certFile: File get() = File(context.filesDir, "tls/lenscast.der")

    companion object {
        private const val TAG = "TlsCertManager"
        private const val KEYSTORE_TYPE = "PKCS12"
        private const val ALIAS = "lenscast"

        /**
         * The fixed DNS SAN every served certificate carries, so a
         * `lenscast.local` bookmark keeps validating across the IP-driven
         * certificate regenerations — wherever the network resolves the name.
         */
        const val STABLE_HOST = "lenscast.local"

        // PKCS12 requires a password; this constant only obfuscates it. The
        // actual protection boundary is the app-private filesDir sandbox —
        // anyone who can read tls/lenscast.p12 can read this source too.
        private val KEY_PASSWORD = "lenscast".toCharArray()

        /** The colon-separated SHA-256 certificate fingerprint shown to the user. */
        fun fingerprintOf(der: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(der)
            // Locale.US: hex digits are locale-invariant, matching the repo idiom.
            return digest.joinToString(":") { String.format(java.util.Locale.US, "%02X", it) }
        }
    }
}
