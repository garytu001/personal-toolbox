package com.local.personaltoolbox.modules.recents

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.local.personaltoolbox.ToolboxApplication
import io.github.muntashirakon.adb.ToolboxPairing
import java.net.InetAddress
import java.net.ServerSocket
import java.security.Signature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.conscrypt.Conscrypt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecentsDeviceTest {
    @Test fun identitySurvivesRecreationAndIsEncryptedOnDisk() {
        val app = ApplicationProvider.getApplicationContext<ToolboxApplication>()
        val first = AdbIdentity.load(app)
        val second = AdbIdentity.load(app)
        assertArrayEquals(first.certificate.encoded, second.certificate.encoded)
        val payload = "Toolbox identity persistence".toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(first.privateKey); update(payload); sign() }
        assertTrue(Signature.getInstance("SHA256withRSA").run { initVerify(second.certificate.publicKey); update(payload); verify(signature) })
        val encrypted = java.io.File(app.noBackupFilesDir, "recents_control_identity").readBytes()
        val privateBytes = first.privateKey.encoded
        try { assertFalse(encrypted.toList().windowed(privateBytes.size).any { it == privateBytes.toList() }) }
        finally { privateBytes.fill(0) }
    }

    @Test fun bundledNativePairingLibrariesLoadOnThisAndroidVersion() {
        System.loadLibrary("spake2")
        assertNotNull(javax.net.ssl.SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()))
        val role = Class.forName("io.github.muntashirakon.crypto.spake2.Spake2Role")
        val context = Class.forName("io.github.muntashirakon.crypto.spake2.Spake2Context")
        val constructor = context.getConstructor(role, ByteArray::class.java, ByteArray::class.java)
        val clientName = "adb pair client\u0000".toByteArray()
        val serverName = "adb pair server\u0000".toByteArray()
        val alice = constructor.newInstance(role.getField("Alice").get(null), clientName, serverName)
        val bob = constructor.newInstance(role.getField("Bob").get(null), serverName, clientName)
        try {
            val password = "123456".toByteArray() + ByteArray(64) { it.toByte() }
            val generate = context.getMethod("generateMessage", ByteArray::class.java)
            val process = context.getMethod("processMessage", ByteArray::class.java)
            val a = generate.invoke(alice, password) as ByteArray
            val b = generate.invoke(bob, password) as ByteArray
            assertArrayEquals(process.invoke(alice, b) as ByteArray, process.invoke(bob, a) as ByteArray)
        } finally {
            context.getMethod("destroy").invoke(alice)
            context.getMethod("destroy").invoke(bob)
        }
    }

    @Test fun cancellingPairingClosesAStalledTlsSocket() {
        val app = ApplicationProvider.getApplicationContext<ToolboxApplication>()
        val identity = AdbIdentity.load(app)
        val pairing = ToolboxPairing()
        val accepted = CountDownLatch(1)
        val ended = AtomicBoolean(false)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val peer = Thread {
                try {
                    server.accept().use { socket ->
                        accepted.countDown()
                        // Block until cancellation closes the client, never complete the TLS handshake.
                        while (socket.getInputStream().read() >= 0) { }
                    }
                } catch (_: Exception) { }
            }.apply { isDaemon = true; start() }
            val worker = Thread {
                try { pairing.pair(server.localPort, "123456".toByteArray(), identity.privateKey, identity.certificate) }
                catch (_: Exception) { }
                finally { ended.set(true) }
            }.apply { isDaemon = true; start() }
            try {
                assertTrue(accepted.await(3, TimeUnit.SECONDS))
                pairing.close()
                worker.join(2_000)
                assertTrue(ended.get())
            } finally { pairing.close(); peer.join(1_000) }
        }
    }
}
