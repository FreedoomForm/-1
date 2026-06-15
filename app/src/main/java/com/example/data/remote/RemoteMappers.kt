package com.example.data.remote

import com.example.data.ContractHistoryEntry
import com.example.data.NotificationHistoryEntity
import com.example.data.Renter
import com.example.data.Scooter

/**
 * Маппинги между локальными Room-сущностями и DTO бэкенда.
 * snake_case (DTO, Postgres) ↔ camelCase (Kotlin).
 */

fun RenterDto.toEntity(): Renter = Renter(
    id = id,
    name = name,
    phoneNumber = phone_number,
    debtAmount = debt_amount,
    rentDurationDays = rent_duration_days,
    rentStartDateTimestamp = rent_start_date_timestamp,
    isReturned = is_returned,
    isOverdueSmsSent = is_overdue_sms_sent,
    scooterId = scooter_id,
    scooterName = scooter_name,
    lastPaymentTimestamp = last_payment_timestamp,
    balance = balance
)

fun Renter.toCreateDto(): CreateRenterRequest = CreateRenterRequest(
    name = name,
    phone_number = phoneNumber,
    debt_amount = debtAmount,
    rent_duration_days = rentDurationDays,
    rent_start_date_timestamp = rentStartDateTimestamp,
    is_returned = isReturned,
    is_overdue_sms_sent = isOverdueSmsSent,
    scooter_id = scooterId,
    scooter_name = scooterName,
    last_payment_timestamp = lastPaymentTimestamp,
    balance = balance
)

fun Renter.toUpdateDto(): UpdateRenterRequest = UpdateRenterRequest(
    name = name,
    phone_number = phoneNumber,
    debt_amount = debtAmount,
    rent_duration_days = rentDurationDays,
    rent_start_date_timestamp = rentStartDateTimestamp,
    is_returned = isReturned,
    is_overdue_sms_sent = isOverdueSmsSent,
    scooter_id = scooterId,
    scooter_name = scooterName,
    last_payment_timestamp = lastPaymentTimestamp,
    balance = balance
)

fun ScooterDto.toEntity(): Scooter = Scooter(
    id = id,
    name = name,
    documentedNumber = documented_number
)

fun Scooter.toCreateDto(): CreateScooterRequest = CreateScooterRequest(
    name = name,
    documented_number = documentedNumber
)

fun Scooter.toUpdateDto(): UpdateScooterRequest = UpdateScooterRequest(
    name = name,
    documented_number = documentedNumber
)

fun ContractHistoryDto.toEntity(): ContractHistoryEntry = ContractHistoryEntry(
    id = id,
    renterId = renter_id,
    timestamp = timestamp,
    type = type,
    amount = amount,
    notes = notes
)

fun ContractHistoryEntry.toCreateDto(): CreateContractHistoryRequest =
    CreateContractHistoryRequest(
        renter_id = renterId,
        timestamp = timestamp,
        type = type,
        amount = amount,
        notes = notes
    )

fun NotificationDto.toEntity(): NotificationHistoryEntity = NotificationHistoryEntity(
    id = id,
    timestamp = timestamp,
    renterId = renter_id,
    title = title,
    message = message
)

fun NotificationHistoryEntity.toCreateDto(): CreateNotificationRequest =
    CreateNotificationRequest(
        timestamp = timestamp,
        renter_id = renterId,
        title = title,
        message = message
    )
