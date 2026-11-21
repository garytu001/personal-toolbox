package com.local.personaltoolbox.modules.recents

import org.junit.Assert.*
import org.junit.Test

class RecentsProtocolTest {
    private val nonce = "a".repeat(32)
    @Test fun acceptsOnlyOneAuthenticatedReceiptWithAKnownStatus() {
        assertEquals("REMOVED", RecentsProtocol.parse("runtime startup\n${RecentsProtocol.reply(nonce, "REMOVED")}\r\n", nonce))
        assertNull(RecentsProtocol.parse(RecentsProtocol.reply("b".repeat(32), "REMOVED"), nonce))
        assertNull(RecentsProtocol.parse("REMOVED", nonce))
        assertNull(RecentsProtocol.parse(RecentsProtocol.reply(nonce, "UNRECOGNIZED"), nonce))
        assertNull(RecentsProtocol.parse(List(2) { RecentsProtocol.reply(nonce, "REMOVED") }.joinToString("\n"), nonce))
    }
    @Test fun shellArgumentsCannotIntroduceCommandsOrSubstitutions() {
        assertEquals("'/data/app/abc/base.apk'", RecentsProtocol.quote("/data/app/abc/base.apk"))
        assertEquals("'a'\"'\"'b;\$(id)'", RecentsProtocol.quote("a'b;\$(id)"))
        assertFalse(RecentsProtocol.validNonce(";echo injected"))
    }
}
