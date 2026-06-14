package com.example.data.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Хранилище JWT, server URL и роли.
 *
 * Использует EncryptedSharedPreferences (AES256_GCM), чтобы токен
 * не лежал открытым текстом в /data/data/.../shared_prefs/.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var jwt: String?
        get() = prefs.getString(KEY_JWT, null)
        set(value) = prefs.edit().putString(KEY_JWT, value).apply()

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var userEmail: String?
        get() = prefs.getString(KEY_USER_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_USER_EMAIL, value).apply()

    var role: String?
        get() = prefs.getString(KEY_ROLE, null)
        set(value) = prefs.edit().putString(KEY_ROLE, value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun isLoggedIn(): Boolean = !jwt.isNullOrBlank() && !serverUrl.isNullOrBlank()

    companion object {
        private const val FILE_NAME = "scooter_secure_prefs"
        private const val KEY_JWT = "jwt"
        const val DEFAULT_SERVER_URL = "https://city1bike.vercel.app"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_ROLE = "role"
    }
}
