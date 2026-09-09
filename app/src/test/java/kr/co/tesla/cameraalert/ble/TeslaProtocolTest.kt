package kr.co.tesla.cameraalert.ble

import org.junit.Assert.*
import org.junit.Test

class TeslaProtocolTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun registrationMatchesVcsecWireSchema() {
        val key = byteArrayOf(4) + ByteArray(64) { it.toByte() }
        val expected = hex("0a54125082014d2a470a430a41") + key + hex("2005320208071802")
        assertArrayEquals(expected, TeslaProtocol.addKey(key))
    }
    @Test fun vinRejectsTyposAndNormalizesNothingImplicitly() {
        assertTrue(TeslaProtocol.validVin("5YJ3E1EA7JF000001"))
        assertFalse(TeslaProtocol.validVin("5YJ3E1EA7JF00000I"))
        assertFalse(TeslaProtocol.validVin("5yj3e1ea7jf000001"))
        assertFalse(TeslaProtocol.validVin("123"))
    }
    @Test fun framesAcceptEveryPossibleSplitAndMultipleMessages() {
        val packet = hex("00030a010700020800")
        for (split in 0..packet.size) {
            val decoder = TeslaProtocol.Frames()
            val messages = decoder.feed(packet.copyOfRange(0, split)) + decoder.feed(packet.copyOfRange(split, packet.size))
            assertEquals(2, messages.size)
            assertArrayEquals(hex("0a0107"), messages[0])
            assertArrayEquals(hex("0800"), messages[1])
        }
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedFrameIsRejected() {
        TeslaProtocol.Frames().feed(hex("ffff"))
    }
    @Test(expected = IllegalArgumentException::class) fun truncatedProtobufIsRejected() {
        TeslaProtocol.fields(hex("0a050102"))
    }
    @Test(expected = IllegalArgumentException::class) fun overflowingVarintIsRejected() {
        TeslaProtocol.fields(hex("08ffffffff7f"))
    }
    @Test fun queryContainsRoutingAndCorrelation() {
        val key = ByteArray(65)
        val route = ByteArray(16) { 7 }
        val uuid = ByteArray(16) { 3 }
        val fields = TeslaProtocol.fields(TeslaProtocol.keyQuery(key, route, uuid))
        assertArrayEquals(uuid, fields.single { it.id == 51 }.value)
        val from = TeslaProtocol.fields(fields.single { it.id == 7 }.value)
        assertArrayEquals(route, from.single { it.id == 2 }.value)
        val unsigned = TeslaProtocol.fields(fields.single { it.id == 10 }.value)
        val request = TeslaProtocol.fields(unsigned.single { it.id == 1 }.value)
        assertEquals(6, request.single { it.id == 1 }.number)
        assertArrayEquals(key, request.single { it.id == 3 }.value)
    }
}

