package kr.co.tesla.cameraalert.ble

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

object VehicleKey {
    @Synchronized fun remove(vin: String) {
        require(TeslaProtocol.validVin(vin))
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("camera_monitor_$vin")
    }
    @Synchronized fun publicKey(vin: String): ByteArray {
        require(TeslaProtocol.validVin(vin))
        val alias = "camera_monitor_$vin"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        val key = store.getCertificate(alias).publicKey as ECPublicKey
        fun coordinate(raw: ByteArray) = ByteArray(32).also {
            val source = raw.takeLast(32).toByteArray(); source.copyInto(it, 32 - source.size)
        }
        return byteArrayOf(4) + coordinate(key.w.affineX.toByteArray()) + coordinate(key.w.affineY.toByteArray())
    }
}
