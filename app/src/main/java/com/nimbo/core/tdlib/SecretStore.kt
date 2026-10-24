package com.nimbo.core.tdlib

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// Pura, testeable: api_id admite espacios/prefijos, solo dígitos valen.
fun parseApiId(s: String): Int? =
    s.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toIntOrNull()

data class ApiKeys(val id: Int, val hash: String)

// Keys TDLib + clave de la base: SOLO en este prefs cifrado del teléfono.
// Nunca en código ni repo.
@Singleton
class SecretStore @Inject constructor(@ApplicationContext private val ctx: Context) {
    // Keystore + Tink una sola vez (antes en cada llamada, en el hilo principal).
    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val mk = MasterKey.Builder(ctx, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx, FILE, mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrNull()
    }

    fun load(): ApiKeys? {
        val p = prefs ?: return null
        val id = parseApiId(p.getString(API_ID, "").orEmpty()) ?: return null
        val hash = p.getString(API_HASH, "").orEmpty()
        return if (hash.isBlank()) null else ApiKeys(id, hash)
    }

    fun save(keys: ApiKeys) {
        prefs?.edit {
            putString(API_ID, keys.id.toString())
            putString(API_HASH, keys.hash.trim())
        }
    }

    fun clear() {
        prefs?.edit { remove(API_ID).remove(API_HASH) }
    }

    // Clave de la base TDLib (sesión = acceso a la cuenta): 32 bytes
    // aleatorios, creados una vez y guardados aquí, cifrados por Keystore.
    fun dbKey(): ByteArray {
        val p = prefs ?: error("secret store unavailable")
        p.getString(DB_KEY, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val k = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        p.edit(commit = true) { putString(DB_KEY, Base64.encodeToString(k, Base64.NO_WRAP)) }
        return k
    }

    private companion object {
        const val FILE = "tg_secrets"
        const val API_ID = "tg_api_id"
        const val API_HASH = "tg_api_hash"
        const val DB_KEY = "tg_db_key"
    }
}
