package com.raulshma.lenscast.core

import android.content.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyStore

/**
 * The filesDir persistence path behind issue #8: a fresh install has no
 * tls/ folder under filesDir, so the first [TlsCertManager.identity] call
 * must create it. Before the fix, the keystore write threw ENOENT, the
 * server silently fell back to plain HTTP, and the dashboard kept
 * advertising https:// — the browser-side SSL protocol errors the issue
 * reported.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TlsCertManagerTest {

    @Test
    fun `identity creates the tls directory on a fresh install and serves a working factory`() {
        val context: Context = RuntimeEnvironment.getApplication()
        assertFalse(File(context.filesDir, "tls").exists())

        val manager = TlsCertManager(context)
        val identity = manager.identity(listOf("127.0.0.1"))

        assertTrue(File(context.filesDir, "tls").isDirectory)
        assertTrue(File(context.filesDir, "tls/lenscast.p12").exists())
        assertTrue(File(context.filesDir, "tls/lenscast.der").exists())
        assertEquals("CN=LensCast", identity.certificate.subjectX500Principal.name)
        // The factory the server binds must open without throwing.
        identity.serverSocketFactory.createServerSocket().close()
    }

    @Test
    fun `identity reuses the persisted key pair and certificate across calls`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val manager = TlsCertManager(context)

        val first = manager.identity(listOf("127.0.0.1"))
        val second = manager.identity(listOf("127.0.0.1"))

        assertEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `regenerate recreates the identity through the same directory path`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val manager = TlsCertManager(context)
        val original = manager.identity(listOf("127.0.0.1"))

        val regenerated = manager.regenerate(listOf("127.0.0.1"))

        assertTrue(File(context.filesDir, "tls/lenscast.der").exists())
        assertTrue(regenerated.fingerprint != original.fingerprint)
    }

    @Test
    fun `a missing key store regenerates a certificate matching the new key pair`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val first = TlsCertManager(context).identity(listOf("127.0.0.1"))

        // The p12 is lost while the DER survives (a fresh process loads the
        // pair independently): reusing the old certificate would bind a
        // factory whose private key does not match it.
        File(context.filesDir, "tls/lenscast.p12").delete()
        val second = TlsCertManager(context).identity(listOf("127.0.0.1"))

        assertNotEquals(first.fingerprint, second.fingerprint)
        // The served DER and the persisted PKCS12 entry must describe the
        // same key pair: the certificate's public key is what the factory
        // presents, and the keystore entry is the private key it signs with.
        val certificate = SelfSignedCert.parse(File(context.filesDir, "tls/lenscast.der").readBytes())
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            File(context.filesDir, "tls/lenscast.p12").inputStream().use {
                load(it, "lenscast".toCharArray())
            }
        }
        val stored = keyStore.getCertificate("lenscast")
        assertArrayEquals(stored.publicKey.encoded, certificate.publicKey.encoded)
        second.serverSocketFactory.createServerSocket().close()
    }

    @Test
    fun `identity is stable across equivalent IPv6 spellings and zone suffixes`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val manager = TlsCertManager(context)

        val first = manager.identity(listOf("127.0.0.1", "fe80::1%wlan0", "2001:db8::a"))
        // Same addresses, expanded and without the interface zone: the
        // identity must not regenerate (a fingerprint flip on every server
        // recreate is the reported HTTPS symptom).
        val second = manager.identity(
            listOf("127.0.0.1", "fe80:0:0:0:0:0:0:1", "2001:db8:0:0:0:0:0:a"),
        )

        assertEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `identity regenerates when a new LAN address appears`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val manager = TlsCertManager(context)
        val original = manager.identity(listOf("127.0.0.1"))

        val moved = manager.identity(listOf("127.0.0.1", "192.168.1.77"))

        assertNotEquals(original.fingerprint, moved.fingerprint)
        val sans = moved.certificate.subjectAlternativeNames
            ?.filter { (it[0] as Int) == 7 }
            ?.map { it[1] as String }
            ?: emptyList()
        assertTrue(sans.any { it.contains("192.168.1.77") })
    }
}
