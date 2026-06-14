package com.example.data.remote

import com.squareup.moshi.JsonClass

/**
 * DTO для HTTP-обмена с backend (Vercel + Neon Postgres).
 * Имена полей совпадают с колонками Postgres (snake_case) —
 *  поэтому на бэкенде достаточно просто SELECT *.
 */

@JsonClass(generateAdapter = true)
data class UserDto(
    val id: Int,
    val email: String,
    val role: String
)

@JsonClass(generateAdapter = true)
data class LoginRequest(
    val email: String,
    val password: String
)

@JsonClass(generateAdapter = true)
data class LoginResponse(
    val token: String,
    val user: UserDto
)

@JsonClass(generateAdapter = true)
data class RegisterRequest(
    val email: String,
    val password: String
)

@JsonClass(generateAdapter = true)
data class RegisterResponse(
    val token: String,
    val user: UserDto
)

@JsonClass(generateAdapter = true)
data class RenterDto(
    val id: Int,
    val name: String,
    val phone_number: String,
    val debt_amount: Double,
    val rent_duration_days: Int,
    val rent_start_date_timestamp: Long,
    val is_returned: Boolean,
    val is_overdue_sms_sent: Boolean,
    val scooter_id: Int?,
    val scooter_name: String?,
    val last_payment_timestamp: Long?,
    val balance: Double
)

@JsonClass(generateAdapter = true)
data class CreateRenterRequest(
    val name: String,
    val phone_number: String,
    val debt_amount: Double,
    val rent_duration_days: Int,
    val rent_start_date_timestamp: Long,
    val is_returned: Boolean,
    val is_overdue_sms_sent: Boolean,
    val scooter_id: Int?,
    val scooter_name: String?,
    val last_payment_timestamp: Long?,
    val balance: Double
)

@JsonClass(generateAdapter = true)
data class UpdateRenterRequest(
    val name: String?,
    val phone_number: String?,
    val debt_amount: Double?,
    val rent_duration_days: Int?,
    val rent_start_date_timestamp: Long?,
    val is_returned: Boolean?,
    val is_overdue_sms_sent: Boolean?,
    val scooter_id: Int?,
    val scooter_name: String?,
    val last_payment_timestamp: Long?,
    val balance: Double?
)

@JsonClass(generateAdapter = true)
data class IdResponse(val id: Int)

@JsonClass(generateAdapter = true)
data class ScooterDto(
    val id: Int,
    val name: String,
    val documented_number: String?
)

@JsonClass(generateAdapter = true)
data class CreateScooterRequest(
    val name: String,
    val documented_number: String?
)

@JsonClass(generateAdapter = true)
data class UpdateScooterRequest(
    val name: String?,
    val documented_number: String?
)

@JsonClass(generateAdapter = true)
data class ContractHistoryDto(
    val id: Int,
    val renter_id: Int,
    val timestamp: Long,
    val type: String,
    val amount: Double,
    val notes: String?
)

@JsonClass(generateAdapter = true)
data class CreateContractHistoryRequest(
    val renter_id: Int,
    val timestamp: Long,
    val type: String,
    val amount: Double,
    val notes: String?
)

@JsonClass(generateAdapter = true)
data class NotificationDto(
    val id: Int,
    val timestamp: Long,
    val renter_id: Int?,
    val title: String,
    val message: String
)

@JsonClass(generateAdapter = true)
data class CreateNotificationRequest(
    val timestamp: Long,
    val renter_id: Int?,
    val title: String,
    val message: String
)
