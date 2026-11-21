package com.local.personaltoolbox.modules.recents

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

internal class RequestBlocked : Exception()

/** Bounded two-phase protocol, independent of Android so fragmentation/cancellation can be tested. */
internal object RecentsExchange {
    fun run(input: InputStream, output: OutputStream, nonce: String, canCommit: Boolean, allowed: () -> Boolean): String? {
        require(RecentsProtocol.validNonce(nonce))
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
                    check(canCommit && !prepared)
                    prepared = true
                    if (!allowed()) throw RequestBlocked()
                    output.write("COMMIT:$nonce\n".toByteArray(Charsets.US_ASCII))
                    output.flush()
                } else result.append(text).append('\n')
            }
        }
        if (line.size() > 0) result.append(line.toString("UTF-8"))
        val status = RecentsProtocol.parse(result.toString(), nonce)
        if (status == "REMOVED") check(prepared)
        return status
    }
}
