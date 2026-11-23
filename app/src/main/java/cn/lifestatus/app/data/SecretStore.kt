package cn.lifestatus.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The API configuration never enters the plain-text event/settings file. */
internal class SecretStore(context: Context, profile: String = "primary") {
    init { require(profile.matches(Regex("[a-z][a-z0-9-]{0,31}"))) }
    private val prefix = "$ALIAS.$profile"
    private val preferences = context.getSharedPreferences(
        if (profile == "primary") "ai-credentials-v1" else "ai-credentials-v1-$profile", Context.MODE_PRIVATE)

    fun load(): AiConfig = synchronized(lock) {
        val blob = preferences.getString("ciphertext", null) ?: return@synchronized AiConfig()
        try {
            val metadata = JSONObject(blob)
            val alias = metadata.getString("key_alias")
            require(alias.startsWith(prefix + "."))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(alias, create = false),
                GCMParameterSpec(128, Base64.decode(metadata.getString("iv"), Base64.NO_WRAP)))
            val plaintext = cipher.doFinal(Base64.decode(metadata.getString("encrypted"), Base64.NO_WRAP))
            val value = JSONObject(plaintext.toString(Charsets.UTF_8))
            AiConfig(value.getString("base_url"), value.getString("model"), value.getString("api_key"))
        } catch (_: Exception) {
            // Do not turn a lost Keystore key into empty credentials and overwrite the saved blob.
            throw IOException("AI 配置无法解密，原配置已保留。请在设置中重新填写并保存。")
        }
    }

    fun save(config: AiConfig) = synchronized(lock) {
        try {
            val oldAlias = preferences.getString("ciphertext", null)?.let {
                runCatching { JSONObject(it).getString("key_alias") }.getOrNull()
            }
            // A fresh alias lets an explicit reconfiguration recover from a lost/invalidated old key.
            // Keep the old alias until the new encrypted value has been committed successfully.
            val alias = prefix + "." + UUID.randomUUID().toString()
            val plaintext = JSONObject().put("base_url", config.baseUrl.trim())
                .put("model", config.model.trim()).put("api_key", config.apiKey.trim()).toString()
                .toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(alias, create = true))
            val encrypted = cipher.doFinal(plaintext)
            val blob = JSONObject().put("key_alias", alias)
                .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .put("encrypted", Base64.encodeToString(encrypted, Base64.NO_WRAP)).toString()
            if (!preferences.edit().putString("ciphertext", blob).commit()) throw IOException()
            if (oldAlias?.startsWith(prefix + ".") == true) {
                runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(oldAlias) }
            }
        } catch (_: Exception) {
            throw IOException("AI 配置保存失败，原配置已保留。")
        }
    }

    private fun key(alias: String, create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Missing encryption key" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    companion object {
        private const val ALIAS = "cn.lifestatus.app.ai.credentials.v1"
        private val lock = Any()
    }
}
