package com.example.data.remote

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Singleton Retrofit-клиент.
 *
 * ⚠️ baseUrl читается динамически из TokenStore при каждом
 *    вызове getService(), потому что URL может поменяться после
 *    повторного логина. Если URL изменился — клиент пересоздаётся.
 */
object ApiClient {

    @Volatile private var cached: ApiService? = null
    @Volatile private var cachedUrl: String? = null
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Synchronized
    fun getService(context: Context): ApiService {
        val tokenStore = TokenStore(context.applicationContext)
        val baseUrl = tokenStore.serverUrl?.trim()?.let {
            if (it.endsWith("/")) it else "$it/"
        }

        if (baseUrl == null) {
            throw IllegalStateException(
                "Server URL не задан. Войдите в систему сначала."
            )
        }

        if (cached != null && cachedUrl == baseUrl) return cached!!

        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        cached = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ApiService::class.java)
        cachedUrl = baseUrl
        return cached!!
    }

    /** Сброс кэша — после logout или смены сервера. */
    @Synchronized
    fun reset() {
        cached = null
        cachedUrl = null
    }
}
