package com.local.personaltoolbox.modules.recents

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import org.junit.Assert.*
import org.junit.Test

class RecentsExchangeTest {
    private val nonce = "c".repeat(32)
    private val prepared = RecentsProtocol.reply(nonce, "PREPARED")
    private val removed = RecentsProtocol.reply(nonce, "REMOVED")
    private fun bytes(text: String) = ByteArrayInputStream(text.toByteArray())

    @Test fun fragmentedRepliesCommitExactlyOnceBeforeAcceptingRemoval() {
        val input = object : FilterInputStream(bytes("startup\n$prepared\r\n$removed\n")) {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = super.read(buffer, offset, minOf(length, 1))
        }
        val output = ByteArrayOutputStream()
        assertEquals("REMOVED", RecentsExchange.run(input, output, nonce, true) { true })
        assertEquals("COMMIT:$nonce\n", output.toString("UTF-8"))
    }
    @Test fun invalidatedVisitNeverSendsCommit() {
        val output = ByteArrayOutputStream()
        assertThrows(RequestBlocked::class.java) {
            RecentsExchange.run(bytes("$prepared\n$removed\n"), output, nonce, true) { false }
        }
        assertEquals(0, output.size())
    }
    @Test fun aRemovalReceiptWithoutPreparationIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            RecentsExchange.run(bytes("$removed\n"), ByteArrayOutputStream(), nonce, true) { true }
        }
    }
    @Test fun readOnlyProbeCannotSendCommit() {
        val output = ByteArrayOutputStream()
        assertEquals("READY", RecentsExchange.run(bytes(RecentsProtocol.reply(nonce, "READY")), output, nonce, false) { true })
        assertThrows(IllegalStateException::class.java) {
            RecentsExchange.run(bytes("$prepared\n"), output, nonce, false) { true }
        }
        assertEquals(0, output.size())
    }
    @Test fun aWrongNonceCannotRequestCommitOrAuthenticateAReceipt() {
        val output = ByteArrayOutputStream()
        val wrong = RecentsProtocol.reply("d".repeat(32), "PREPARED")
        assertNull(RecentsExchange.run(bytes("$wrong\n$removed\n"), output, nonce, true) { true })
        assertEquals(0, output.size())
    }
    @Test fun duplicatePreparationCannotTriggerASecondCommit() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalStateException::class.java) {
            RecentsExchange.run(bytes("$prepared\n$prepared\n$removed\n"), output, nonce, true) { true }
        }
        assertEquals("COMMIT:$nonce\n", output.toString("UTF-8"))
    }
    @Test fun absentTaskDoesNotRequireOrSendCommit() {
        val output = ByteArrayOutputStream()
        assertEquals("ABSENT", RecentsExchange.run(bytes(RecentsProtocol.reply(nonce, "ABSENT")), output, nonce, true) { true })
        assertEquals(0, output.size())
    }
    @Test fun excessiveOutputIsBoundedAndRejected() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalStateException::class.java) {
            RecentsExchange.run(bytes("x".repeat(8_193)), output, nonce, true) { true }
        }
        assertEquals(0, output.size())
    }
}
