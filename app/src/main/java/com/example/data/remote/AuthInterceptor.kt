package com.example.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Добавляет «Authorization: Bearer <jwt>» ко всем запросам,
 * если токен сохранён в TokenStore. Если нет — пропускает
 * (для эндпоинтов /api/auth/*, которые не требуют авторизации).
 */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = tokenStore.jwt
        val newRequest = if (!token.isNullOrBlank()) {
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            request
        }
        return chain.proceed(newRequest)
    }
}
