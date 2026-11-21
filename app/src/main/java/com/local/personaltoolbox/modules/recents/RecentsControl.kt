package com.local.personaltoolbox.modules.recents

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.local.personaltoolbox.R
import com.local.personaltoolbox.ToolboxApplication
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.ToolboxPairing
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.security.interfaces.RSAPrivateCrtKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RecentsConnectionState(val ready: Boolean = false, val busy: Boolean = false, val message: String)

/** The only ADB transport in Toolbox. Connects exclusively to 127.0.0.1 using a paired identity.
 * It exposes two typed operations, probe/remove, and never accepts shell commands from callers. */
class RecentsControl(private val app: ToolboxApplication) {
    private val connection = AtomicReference<AdbConnection?>()
    private val connecting = AtomicReference<AdbConnection?>()
    private val pairingClient = AtomicReference<ToolboxPairing?>()
    private val generation = AtomicLong()
    private val gate = Mutex()
    private var initialized = false
    private val mutableState = MutableStateFlow(RecentsConnectionState(message = app.getString(R.string.recents_disconnected)))
    val state = mutableState.asStateFlow()

    fun initialize() {
        if (initialized) return
        initialized = true
        app.applicationScope.launch {
            app.preferences.settings.filterNotNull().map { it.isEnabled(ID) }.distinctUntilChanged().collect { enabled ->
                if (!enabled) disconnect()
                else {
                    val port = savedPort()
                    if (Build.VERSION.SDK_INT >= 30 && port in 1..65535) connect(port)
                }
            }
        }
    }

    suspend fun savedPort(): Int = app.preferences.store.data.first()[PORT] ?: 0
    private fun enabled() = app.preferences.settings.value?.isEnabled(ID) == true
    fun isReady() = enabled() && state.value.ready && connection.get()?.let { it.isConnected && it.isConnectionEstablished } == true

    suspend fun pair(port: Int, code: String): Boolean = gate.withLock {
        require(port in 1..65535 && Regex("[0-9]{6}").matches(code))
        if (!enabled() || Build.VERSION.SDK_INT < 30) { report(R.string.recents_enable_first); return@withLock false }
        report(R.string.recents_pairing, busy = true)
        val pairing = ToolboxPairing()
        val ticket = generation.get()
        pairingClient.set(pairing)
        try {
            val identity = withContext(Dispatchers.IO) { AdbIdentity.load(app) }
            blocking(15_000, { pairing.close() }) {
                check(ticket == generation.get() && enabled())
                pairing.pair(port, code.toByteArray(Charsets.US_ASCII), identity.privateKey, identity.certificate)
            }
            check(ticket == generation.get() && enabled())
            report(R.string.recents_paired)
            true
        } catch (e: CancellationException) {
            report(R.string.recents_disconnected)
            throw e
        } catch (_: Exception) {
            report(R.string.recents_pair_failed)
            false
        } finally {
            pairingClient.compareAndSet(pairing, null)
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { try { pairing.close() } catch (_: Exception) { } }
        }
    }

    suspend fun connect(port: Int): Boolean = gate.withLock {
        require(port in 1..65535)
        if (!enabled() || Build.VERSION.SDK_INT < 30) { report(R.string.recents_enable_first); return@withLock false }
        dropConnection()
        report(R.string.recents_connecting, busy = true)
        val candidate = AtomicReference<AdbConnection?>()
        val ticket = generation.get()
        try {
            val identity = withContext(Dispatchers.IO) { AdbIdentity.load(app) }
            val connected = blocking(7_000, { candidate.get()?.close() }) {
                val client = AdbConnection.Builder("127.0.0.1", port).setApi(Build.VERSION.SDK_INT)
                    .setPrivateKey(ConnectionKey(identity.privateKey as RSAPrivateCrtKey))
                    .setCertificate(identity.certificate).setDeviceName("Toolbox").build()
                candidate.set(client)
                connecting.set(client)
                check(ticket == generation.get() && enabled())
                client.connect(4_000, TimeUnit.MILLISECONDS, true)
            }
            check(connected && enabled() && ticket == generation.get())
            val client = candidate.get() ?: error("no_connection")
            check(command(client, "probe", "-", false) == "READY")
            currentCoroutineContext().ensureActive()
            check(enabled() && ticket == generation.get())
            connection.set(client)
            app.preferences.store.edit { it[PORT] = port }
            report(R.string.recents_connected, ready = true)
            true
        } catch (e: CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { try { candidate.get()?.close() } catch (_: Exception) { } }
            connection.compareAndSet(candidate.get(), null)
            report(R.string.recents_disconnected)
            throw e
        } catch (_: Exception) {
            withContext(Dispatchers.IO) { candidate.get()?.close() }
            connection.compareAndSet(candidate.get(), null)
            report(R.string.recents_connect_failed)
            false
        } finally { connecting.compareAndSet(candidate.get(), null) }
    }

    fun disconnect() {
        generation.incrementAndGet()
        val old = connection.getAndSet(null)
        val pending = connecting.getAndSet(null)
        val pairing = pairingClient.getAndSet(null)
        report(R.string.recents_disconnected)
        app.applicationScope.launch(Dispatchers.IO) {
            for (resource in listOfNotNull(old, pending, pairing)) try { resource.close() } catch (_: Exception) { }
        }
    }

    internal suspend fun remove(target: String, foregroundOnly: Boolean, allowed: () -> Boolean = { true }): RemovalResult = gate.withLock {
        require(target in RecentsTargets.packages)
        if (!isReady()) return@withLock RemovalResult.NOT_READY
        if (!allowed()) return@withLock RemovalResult.BLOCKED
        val client = connection.get() ?: return@withLock RemovalResult.NOT_READY
        try {
            val ticket = generation.get()
            val result = when (command(client, "remove", target, foregroundOnly) { ticket == generation.get() && enabled() && allowed() }) {
                "REMOVED" -> RemovalResult.REMOVED
                "ABSENT" -> RemovalResult.ABSENT
                "BLOCKED" -> RemovalResult.BLOCKED
                else -> RemovalResult.FAILED
            }
            if (result == RemovalResult.REMOVED) app.preferences.incrementBlockedCount(ID)
            if (result == RemovalResult.FAILED) report(R.string.recents_remove_failed, ready = true)
            result
        } catch (e: CancellationException) { throw e }
        catch (_: RequestBlocked) { RemovalResult.BLOCKED }
        catch (_: Exception) { disconnect(); RemovalResult.FAILED }
    }

    private suspend fun command(client: AdbConnection, operation: String, target: String, foregroundOnly: Boolean,
        allowed: () -> Boolean = { true }): String? {
        require(operation == "probe" || (operation == "remove" && target in RecentsTargets.packages))
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val expiry = SystemClock.elapsedRealtime() + 5_000
        // Android encodes the user in the upper UID range; the shell helper independently checks current user.
        val args = listOf(operation, target, (Process.myUid() / 100_000).toString(),
            if (foregroundOnly) "1" else "0", expiry.toString(), nonce).joinToString(" ", transform = RecentsProtocol::quote)
        val script = "CLASSPATH=${RecentsProtocol.quote(app.applicationInfo.sourceDir)} /system/bin/app_process /system/bin " +
            "com.local.personaltoolbox.modules.recents.RecentsCommand $args"
        val activeStream = AtomicReference<AdbStream?>()
        val output = blocking(6_000, {
            val stream = activeStream.get()
            if (stream != null) stream.close()
            else {
                client.close()
                if (connection.compareAndSet(client, null)) report(R.string.recents_disconnected)
            }
        }) {
            // Re-check after dispatching to the I/O worker, before opening any command stream.
            if (!allowed()) throw RequestBlocked()
            client.open("shell:$script").use { stream ->
                activeStream.set(stream)
                stream.openInputStream().use { input ->
                    val buffer = ByteArray(1024)
                    val line = ByteArrayOutputStream()
                    val result = StringBuilder()
                    var receivedBytes = 0
                    var prepared = false
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        receivedBytes += size
                        check(receivedBytes <= 8_192)
                        for (i in 0 until size) {
                            if (buffer[i] != 10.toByte()) { line.write(buffer[i].toInt()); continue }
                            val text = line.toString("UTF-8").trimEnd('\r')
                            line.reset()
                            if (text == RecentsProtocol.reply(nonce, "PREPARED")) {
                                check(operation == "remove" && !prepared)
                                prepared = true
                                if (!allowed()) throw RequestBlocked()
                                stream.openOutputStream().apply {
                                    write("COMMIT:$nonce\n".toByteArray(Charsets.US_ASCII))
                                    flush()
                                }
                            } else result.append(text).append('\n')
                        }
                    }
                    if (line.size() > 0) result.append(line.toString("UTF-8"))
                    if (RecentsProtocol.parse(result.toString(), nonce) == "REMOVED") check(prepared)
                    result.toString()
                }
            }
        }
        return RecentsProtocol.parse(output, nonce)
    }

    private suspend fun dropConnection() {
        val old = connection.getAndSet(null)
        withContext(Dispatchers.IO) { try { old?.close() } catch (_: Exception) { } }
    }

    private fun report(message: Int, ready: Boolean = false, busy: Boolean = false) {
        mutableState.value = RecentsConnectionState(ready, busy, app.getString(message))
    }

    private suspend fun <T> blocking(timeout: Long, abort: () -> Unit, block: () -> T): T = withTimeout(timeout) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                app.applicationScope.launch(Dispatchers.IO) { try { abort() } catch (_: Exception) { } }
            }
            app.applicationScope.launch(Dispatchers.IO) {
                try {
                    if (!continuation.isActive) return@launch
                    val result = block()
                    if (continuation.isActive) continuation.resume(result)
                    else try { abort() } catch (_: Exception) { }
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                catch (e: LinkageError) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException("unsupported_pairing_runtime", e))
                }
            }
        }
    }

    // LibADB destroys per-connection keys on close and caches its SSLContext. Keep this persistent
    // paired identity reusable across connections; its encrypted file remains app-private.
    private class ConnectionKey(key: RSAPrivateCrtKey) : RSAPrivateCrtKey by key {
        override fun destroy() = Unit
        override fun isDestroyed() = false
    }
    private class RequestBlocked : Exception()

    companion object {
        const val ID = "recents_control"
        private val PORT = intPreferencesKey("${ID}_port")
        fun isGranted(context: Context) = (context.applicationContext as ToolboxApplication).recentsControl.isReady()
    }
}
