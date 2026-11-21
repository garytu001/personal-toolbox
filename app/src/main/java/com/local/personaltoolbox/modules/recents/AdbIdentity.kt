package com.local.personaltoolbox.modules.recents

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** ADB credentials stay on this device, encrypted by an Android Keystore AES key, outside backups.
 * These are local ADB credentials, unrelated to any repository token or APK signing key. */
internal data class AdbIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    companion object {
        @Synchronized fun load(context: Context): AdbIdentity {
            val file = AtomicFile(File(context.noBackupFilesDir, "recents_control_identity"))
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val alias = "recents_control_adb_storage"
            val existing = store.getKey(alias, null) as? SecretKey
            if (file.baseFile.exists()) {
                val key = existing ?: error("identity_key_missing") // Never silently replace a paired identity.
                val encrypted = file.openRead().use { it.readBytes() }
                require(encrypted.size in 32..16_384)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
                val clear = cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
                try {
                    DataInputStream(ByteArrayInputStream(clear)).use { input ->
                        val privateBytes = input.readNBytesChecked()
                        try {
                            val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateBytes))
                            val certificate = CertificateFactory.getInstance("X.509")
                                .generateCertificate(ByteArrayInputStream(input.readNBytesChecked())) as X509Certificate
                            require(input.available() == 0)
                            return AdbIdentity(privateKey, certificate)
                        } finally { privateBytes.fill(0) }
                    }
                } finally { clear.fill(0) }
            }
            val key = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=Toolbox Local ADB")
            val provider = BouncyCastleProvider()
            val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(
                JcaX509v3CertificateBuilder(name, BigInteger(128, SecureRandom()).setBit(127),
                    Date(System.currentTimeMillis() - 86_400_000L), Date(System.currentTimeMillis() + 3_153_600_000_000L),
                    name, pair.public).build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.private)))
            val clear = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { output ->
                    val encoded = pair.private.encoded
                    try { output.writeInt(encoded.size); output.write(encoded) } finally { encoded.fill(0) }
                    output.writeInt(certificate.encoded.size); output.write(certificate.encoded)
                }
            }.toByteArray()
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
                val output = file.startWrite()
                try { output.write(cipher.iv); output.write(cipher.doFinal(clear)); file.finishWrite(output) }
                catch (e: Exception) { file.failWrite(output); throw e }
            } finally { clear.fill(0) }
            return AdbIdentity(pair.private, certificate)
        }

        private fun DataInputStream.readNBytesChecked(): ByteArray {
            val length = readInt()
            require(length in 1..8_192 && length <= available())
            return ByteArray(length).also(::readFully)
        }
    }
}
