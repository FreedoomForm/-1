package com.example.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * Retrofit-интерфейс для всех эндпоинтов backend (Vercel + Neon).
 * Полная документация — в backend/README.md.
 */
interface ApiService {

    // ── Auth ────────────────────────────────────────────────
    @POST("api/auth/login")
    suspend fun login(@Body req: LoginRequest): LoginResponse

    @POST("api/auth/register")
    suspend fun register(@Body req: RegisterRequest): RegisterResponse

    // ── Renters ────────────────────────────────────────────
    @GET("api/renters")
    suspend fun getRenters(): List<RenterDto>

    @POST("api/renters")
    suspend fun createRenter(@Body req: CreateRenterRequest): IdResponse

    @GET("api/renters/{id}")
    suspend fun getRenter(@Path("id") id: Int): RenterDto

    @PUT("api/renters/{id}")
    suspend fun updateRenter(
        @Path("id") id: Int,
        @Body req: UpdateRenterRequest
    ): IdResponse

    @DELETE("api/renters/{id}")
    suspend fun deleteRenter(@Path("id") id: Int): IdResponse

    // ── Scooters ───────────────────────────────────────────
    @GET("api/scooters")
    suspend fun getScooters(): List<ScooterDto>

    @POST("api/scooters")
    suspend fun createScooter(@Body req: CreateScooterRequest): IdResponse

    @PUT("api/scooters/{id}")
    suspend fun updateScooter(
        @Path("id") id: Int,
        @Body req: UpdateScooterRequest
    ): IdResponse

    @DELETE("api/scooters/{id}")
    suspend fun deleteScooter(@Path("id") id: Int): IdResponse

    // ── Contract history ───────────────────────────────────
    @GET("api/contract-history")
    suspend fun getContractHistory(): List<ContractHistoryDto>

    @POST("api/contract-history")
    suspend fun createContractHistory(
        @Body req: CreateContractHistoryRequest
    ): IdResponse

    // ── Notifications ─────────────────────────────────────
    @GET("api/notifications")
    suspend fun getNotifications(): List<NotificationDto>

    @POST("api/notifications")
    suspend fun createNotification(
        @Body req: CreateNotificationRequest
    ): IdResponse

    // ── Health ─────────────────────────────────────────────
    @GET("api/health")
    suspend fun health(): retrofit2.Response<okhttp3.ResponseBody>
}
