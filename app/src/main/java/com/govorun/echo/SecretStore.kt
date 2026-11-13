package com.govorun.echo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets (a voice command's token, the smart functions' key) live encrypted with a key that never leaves the phone's key store, in
 * preferences of their own ([file]) that no backup and no export takes.
 */
internal class SecretStore(
    context: Context,
    file: String,
    private val alias: String,
) {
    private val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)

    fun hasEntries(prefix: String): Boolean = prefs.all.keys.any { it.startsWith(prefix) }

    fun get(name: String): String? =
        prefs.getString(name, null)?.let {
            runCatching {
                val bytes = Base64.decode(it, Base64.NO_WRAP)
                val cipher = Cipher.getInstance(TRANSFORM)
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
                String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
            }.getOrNull()
        }

    fun put(
        name: String,
        value: String,
    ) {
        if (value.isEmpty()) return prefs.edit().remove(name).apply()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/** Recipes that ship with the app (assets) and the ones a person added (files/recipes). */
