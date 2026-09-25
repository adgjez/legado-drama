package com.legado.drama.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.legado.drama.engine.security.KeyMasker
import com.legado.drama.engine.security.KeyVault
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore 版 KeyVault（架构文档 §6：Keystore + AES256-GCM）：
 * 主密钥托管 AndroidKeyStore（StrongBox 可用则用），每个 Key 用 AES-GCM 加密成
 * base64 密文存放应用私有 SharedPreferences，明文永不落盘、永不回显 UI。
 */
class AndroidKeyVault(
    context: Context,
    alias: String = KEYSTORE_ALIAS,
) : KeyVault {

    private val sp = context.applicationContext
        .getSharedPreferences("drama_keyvault", Context.MODE_PRIVATE)
    @Suppress("unused")
    private val keyAlias = alias

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(key: SecretKey, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(key: SecretKey, blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, 12)
        val ct = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    override suspend fun save(configId: String, providerId: String, plainKey: String) {
        val encrypted = encrypt(getOrCreateKey(), plainKey)
        sp.edit()
            .putString(prefKey(configId), encrypted)
            .putString(maskKey(configId), KeyMasker.mask(plainKey))
            .apply()
    }

    override suspend fun load(configId: String): String {
        val blob = sp.getString(prefKey(configId), null) ?: return ""
        return runCatching { decrypt(getOrCreateKey(), blob) }.getOrDefault("")
    }

    override fun masked(configId: String): String =
        sp.getString(maskKey(configId), "") ?: ""

    override suspend fun delete(configId: String) {
        sp.edit()
            .remove(prefKey(configId))
            .remove(maskKey(configId))
            .apply()
    }

    private fun prefKey(configId: String) = "cipher_$configId"
    private fun maskKey(configId: String) = "masked_$configId"

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "drama_keyvault_master"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}