package com.nimbo.ui.telegram

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// Pura, testeable: api_id admite espacios/prefijos, solo dígitos valen.
fun parseApiId(s: String): Int? =
    s.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toIntOrNull()

// Keys TDLib: SOLO en este prefs cifrado del celu. Nunca en código ni repo.
object TgSecrets {
    const val API_ID = "tg_api_id"
    const val API_HASH = "tg_api_hash"
    private const val DB_KEY = "tg_db_key"

    private fun prefs(ctx: Context): SharedPreferences {
        val mk = MasterKey.Builder(ctx, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx, "tg_secrets", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun load(ctx: Context): Pair<Int?, String> = try {
        val p = prefs(ctx)
        val id = p.getString(API_ID, "") ?: ""
        parseApiId(id) to (p.getString(API_HASH, "") ?: "")
    } catch (_: Exception) {
        null to ""
    }

    fun hasKeys(ctx: Context): Boolean {
        val (id, hash) = load(ctx)
        return id != null && hash.isNotBlank()
    }

    fun save(ctx: Context, apiId: Int, apiHash: String) {
        prefs(ctx).edit()
            .putString(API_ID, apiId.toString())
            .putString(API_HASH, apiHash.trim())
            .apply()
    }

    // Clave de la base TDLib (sesión = acceso a la cuenta): 32 bytes
    // aleatorios, creados una vez y guardados aquí, cifrados por Keystore.
    fun dbKey(ctx: Context): ByteArray {
        val p = prefs(ctx)
        p.getString(DB_KEY, null)?.let { return android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        val k = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        p.edit().putString(DB_KEY, android.util.Base64.encodeToString(k, android.util.Base64.NO_WRAP)).commit()
        return k
    }

    fun clear(ctx: Context) {
        try {
            prefs(ctx).edit().remove(API_ID).remove(API_HASH).apply()
        } catch (_: Exception) {
        }
    }
}
