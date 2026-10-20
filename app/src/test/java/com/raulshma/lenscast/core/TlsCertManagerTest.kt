package com.raulshma.lenscast.core

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

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
}
