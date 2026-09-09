package kr.co.tesla.cameraalert.ble

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Bounded protobuf subset for the public Tesla VCSEC protocol. */
object TeslaProtocol {
    fun validVin(vin: String) = Regex("[A-HJ-NPR-Z0-9]{17}").matches(vin)
    fun localName(vin: String): String {
        require(validVin(vin))
        return "S" + MessageDigest.getInstance("SHA-1").digest(vin.toByteArray()).take(8)
            .joinToString("") { "%02x".format(it.toInt() and 255) } + "C"
    }
    private fun varint(value: Int): ByteArray {
        require(value >= 0)
        var n = value
        val out = ByteArrayOutputStream()
        while (n > 127) { out.write((n and 127) or 128); n = n ushr 7 }
        out.write(n)
        return out.toByteArray()
    }
    fun number(field: Int, value: Int) = varint(field shl 3) + varint(value)
    fun bytes(field: Int, value: ByteArray) = varint((field shl 3) or 2) + varint(value.size) + value
    fun addKey(publicKey: ByteArray): ByteArray {
        require(publicKey.size == 65 && publicKey[0] == 4.toByte())
        // Monitor role (5), Android device (7). Never request owner or driver privileges.
        val permission = bytes(1, bytes(1, publicKey)) + number(4, 5)
        val operation = bytes(5, permission) + bytes(6, number(1, 7))
        return bytes(1, bytes(2, bytes(16, operation)) + number(3, 2))
    }
    fun keyQuery(publicKey: ByteArray, route: ByteArray, uuid: ByteArray): ByteArray {
        val request = bytes(1, number(1, 6) + bytes(3, publicKey))
        // RoutableMessage.uuid is field 51. VCSEC may omit request_uuid (field
        // 50) in its BLE reply, so TeslaBleClient correlates replies by route.
        return bytes(6, number(1, 2)) + bytes(7, bytes(2, route)) + bytes(10, request) + bytes(51, uuid)
    }
    data class Field(val id: Int, val value: ByteArray = byteArrayOf(), val number: Int = 0)
    fun fields(data: ByteArray): List<Field> {
        require(data.size <= 4096)
        var pos = 0
        fun read(): Int {
            var value = 0
            for (shift in 0..28 step 7) {
                require(pos < data.size)
                val b = data[pos++].toInt() and 255
                require(shift != 28 || b < 8)
                value = value or ((b and 127) shl shift)
                if (b < 128) return value
            }
            error("잘못된 protobuf")
        }
        val result = mutableListOf<Field>()
        while (pos < data.size) {
            val tag = read(); require(tag ushr 3 > 0)
            when (tag and 7) {
                0 -> result.add(Field(tag ushr 3, number = read()))
                2 -> { val size = read(); require(size <= data.size - pos)
                    result.add(Field(tag ushr 3, data.copyOfRange(pos, pos + size))); pos += size }
                1, 5 -> { pos += if ((tag and 7) == 1) 8 else 4; require(pos <= data.size) }
                else -> error("지원하지 않는 protobuf")
            }
        }
        return result
    }
    class Frames {
        private var pending = byteArrayOf()
        fun feed(chunk: ByteArray): List<ByteArray> {
            require(pending.size + chunk.size <= 8192)
            pending += chunk
            val result = mutableListOf<ByteArray>()
            while (pending.size >= 2) {
                val size = ((pending[0].toInt() and 255) shl 8) or (pending[1].toInt() and 255)
                require(size in 1..4096)
                if (pending.size < size + 2) break
                result.add(pending.copyOfRange(2, size + 2)); pending = pending.copyOfRange(size + 2, pending.size)
            }
            return result
        }
    }
}
