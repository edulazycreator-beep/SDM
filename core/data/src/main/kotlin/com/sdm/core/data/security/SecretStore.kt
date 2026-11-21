package com.sdm.core.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.sdm.core.database.SecretDao
import com.sdm.core.database.SecretEntity
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores per-download request headers (cookies, authorization) encrypted with a non-exportable
 * AES-256-GCM key in the Android Keystore (spec sections 10 and 29). Never exported.
 */
@Singleton
class SecretStore @Inject constructor(private val dao: SecretDao) {
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    suspend fun put(downloadId: Long, headers: Map<String, String>) {
        if (headers.isEmpty()) return
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(Json.encodeToString(serializer, headers).toByteArray())
        dao.put(SecretEntity(downloadId, cipher.iv, ct))
    }

    suspend fun get(downloadId: Long): Map<String, String> {
        val e = dao.get(downloadId) ?: return emptyMap()
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, e.iv)) }
            Json.decodeFromString(serializer, String(cipher.doFinal(e.ciphertext)))
        }.getOrDefault(emptyMap())
    }

    private companion object {
        const val ALIAS = "sdm_download_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
