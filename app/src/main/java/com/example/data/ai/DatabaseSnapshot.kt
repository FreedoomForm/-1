package com.example.data.ai

import android.content.Context
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.SettingsRepository
import com.example.data.VirtualCard
import com.example.data.isExternal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Снимок текущего состояния приложения в JSON-формате.
 *
 * Используется в pipeline'е Mistral: перед генерацией команд агент
 * получает полный снимок базы данных (арендаторы, скутеры, карты,
 * недавние транзакции, настройки) и на его основе решает:
 *   • что СОЗДАВАТЬ (новые сущности, которых ещё нет в БД)
 *   • что НЕ создавать (дубликаты — по имени/телефону/VIN/серии паспорта)
 *   • что ОБНОВЛЯТЬ (UPDATE_RENTER вместо CREATE_RENTER для уже существующих)
 *   • о чём СООБЩИТЬ пользователю в поле "summary"
 *
 * Снимок строится на Dispatchers.IO — все DAO-вызовы suspend.
 *
 * Размер снимка ограничен:
 *   • renters — все активные + последние 50 возвращённых
 *   • scooters — все (обычно их немного)
 *   • virtualCards — все (4 системные + пользовательские)
 *   • transactions — последние 100
 *   • contractHistory — последние 100
 *   • cardTransactions — последние 50
 *
 * Этого достаточно, чтобы модель увидела актуальное состояние и
 * не пропустила дубликаты, без превышения лимита токенов промпта.
 */
object DatabaseSnapshot {

    private const val TAG = "DatabaseSnapshot"

    /** Лимиты, чтобы не раздувать промпт. */
    private const val MAX_RECENT_TRANSACTIONS = 30
    private const val MAX_RECENT_CONTRACTS = 30
    private const val MAX_RECENT_CARD_TX = 20
    private const val MAX_RETURNED_RENTERS = 20
    /** Жёсткий лимит размера JSON-снимка в символах (~16KB ≈ 4K токенов). */
    private const val MAX_SNAPSHOT_CHARS = 16000

    /**
     * Построить JSON-снимок базы данных и настроек приложения.
     *
     * @param context любой контекст приложения
     * @return отформатированная (с отступами) JSON-строка со снимком.
     *         В случае ошибки вернёт минимальный объект {"error": "..."} —
     *         Mistral всё равно сможет работать, просто без контекста.
     */
    suspend fun buildJson(context: Context): String = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(context.applicationContext)
            val settings = SettingsRepository(context.applicationContext)

            val renters = try { db.renterDao().getAllRentersOnce() } catch (e: Exception) {
                Log.w(TAG, "renters fetch failed", e); emptyList()
            }
            val scooters = try { db.scooterDao().getAllScootersOnce() } catch (e: Exception) {
                Log.w(TAG, "scooters fetch failed", e); emptyList()
            }
            val virtualCards = try { db.virtualCardDao().getAllCardsOnce() } catch (e: Exception) {
                Log.w(TAG, "virtualCards fetch failed", e); emptyList()
            }
            val transactions = try { db.transactionDao().getAllOnce() } catch (e: Exception) {
                Log.w(TAG, "transactions fetch failed", e); emptyList()
            }
            val contracts = try { db.contractHistoryDao().getAllOnce() } catch (e: Exception) {
                Log.w(TAG, "contracts fetch failed", e); emptyList()
            }
            val cardTx = try { db.cardTransactionDao().getRecentTransactions(MAX_RECENT_CARD_TX) } catch (e: Exception) {
                Log.w(TAG, "cardTx fetch failed", e); emptyList()
            }

            val now = System.currentTimeMillis()
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            val dateTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }

            // ── Арендаторы ────────────────────────────────────────────────
            // Активные идут первыми (isReturned=false), затем последние
            // возвращённые (с лимитом). Так модель сразу видит «живой» список.
            val activeRenters = renters.filter { !it.isReturned }
            val returnedRenters = renters.filter { it.isReturned }.take(MAX_RETURNED_RENTERS)
            val rentersArray = JSONArray()
            for (r in (activeRenters + returnedRenters)) {
                rentersArray.put(JSONObject().apply {
                    put("id", r.id)
                    put("name", r.name)
                    put("phone", r.phoneNumber)
                    put("debt", round2(r.debtAmount))
                    put("balance", round2(r.balance))
                    put("isReturned", r.isReturned)
                    put("scooterId", r.scooterId ?: JSONObject.NULL)
                    put("scooterName", r.scooterName ?: JSONObject.NULL)
                    put("rentDurationDays", r.rentDurationDays)
                    put("rentStartDate", if (r.rentStartDateTimestamp > 0) dateFmt.format(Date(r.rentStartDateTimestamp)) else JSONObject.NULL)
                    put("passportData", r.passportData.ifEmpty { JSONObject.NULL })
                    put("pinfl", r.pinfl.ifEmpty { JSONObject.NULL })
                    put("address", r.address.ifEmpty { JSONObject.NULL })
                    put("lastPaymentDate", r.lastPaymentTimestamp?.let { dateFmt.format(Date(it)) } ?: JSONObject.NULL)
                })
            }

            // ── Скутеры ───────────────────────────────────────────────────
            val scootersArray = JSONArray()
            for (s in scooters) {
                scootersArray.put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("documentedNumber", s.documentedNumber ?: JSONObject.NULL)
                    put("vin", s.vinNumber.ifEmpty { JSONObject.NULL })
                    put("engine", s.engineNumber.ifEmpty { JSONObject.NULL })
                    put("serial", s.scooterSerialNumber.ifEmpty { JSONObject.NULL })
                    put("battery1", s.batteryId1.ifEmpty { JSONObject.NULL })
                    put("battery2", s.batteryId2.ifEmpty { JSONObject.NULL })
                    put("info", s.additionalInfo.ifEmpty { JSONObject.NULL })
                })
            }

            // ── Виртуальные карты ─────────────────────────────────────────
            val cardsArray = JSONArray()
            for (c in virtualCards) {
                cardsArray.put(JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("balance", round2(c.balance))
                    put("kind", c.kind)
                    put("isExternal", c.isExternal)
                    put("isDefault", c.isDefault)
                    put("info", c.info ?: JSONObject.NULL)
                })
            }

            // ── Последние транзакции ──────────────────────────────────────
            val txArray = JSONArray()
            for (t in transactions.take(MAX_RECENT_TRANSACTIONS)) {
                txArray.put(JSONObject().apply {
                    put("id", t.id)
                    put("date", dateFmt.format(Date(t.timestamp)))
                    put("type", t.type)
                    put("amount", round2(t.amount))
                    put("renterName", t.renterName.ifEmpty { JSONObject.NULL })
                    put("scooterName", t.scooterName.ifEmpty { JSONObject.NULL })
                    put("notes", t.notes ?: JSONObject.NULL)
                })
            }

            // ── Последние записи истории контрактов ───────────────────────
            val contractsArray = JSONArray()
            for (c in contracts.take(MAX_RECENT_CONTRACTS)) {
                contractsArray.put(JSONObject().apply {
                    put("id", c.id)
                    put("date", dateFmt.format(Date(c.timestamp)))
                    put("type", c.type)
                    put("amount", round2(c.amount))
                    put("renterName", c.renterName.ifEmpty { JSONObject.NULL })
                    put("scooterName", c.scooterName ?: JSONObject.NULL)
                    put("weekStart", c.weekStart?.let { dateFmt.format(Date(it)) } ?: JSONObject.NULL)
                    put("weekEnd", c.weekEnd?.let { dateFmt.format(Date(it)) } ?: JSONObject.NULL)
                    put("isPaid", c.isPaid)
                    put("notes", c.notes ?: JSONObject.NULL)
                })
            }

            // ── Последние переводы между картами ──────────────────────────
            val cardTxArray = JSONArray()
            val cardNameById = virtualCards.associateBy { it.id }
            for (ct in cardTx) {
                cardTxArray.put(JSONObject().apply {
                    put("id", ct.id)
                    put("date", dateFmt.format(Date(ct.timestamp)))
                    put("type", ct.type)
                    put("amount", round2(ct.amount))
                    put("fromCard", cardNameById[ct.fromCardId]?.name ?: "external")
                    put("toCard", cardNameById[ct.toCardId]?.name ?: "external")
                    put("note", ct.note ?: JSONObject.NULL)
                })
            }

            // ── Настройки приложения ──────────────────────────────────────
            val settingsObj = JSONObject().apply {
                put("weeklyPrice", settings.weeklyPrice)
                put("monthlyPrice", settings.monthlyPrice)
                put("scooterPriceUsd", settings.scooterPriceUsd)
                put("usdToUzsRate", settings.usdToUzsRate)
                put("paymeLink", settings.paymeLink)
                put("callCenter", settings.callCenter)
                put("smsAutoSendEnabled", settings.smsAutoSendEnabled)
            }

            // ── Сводная статистика (для быстрого понимания масштаба) ──────
            val stats = JSONObject().apply {
                put("totalRenters", renters.size)
                put("activeRenters", activeRenters.size)
                put("returnedRenters", returnedRenters.size)
                put("totalScooters", scooters.size)
                put("totalVirtualCards", virtualCards.size)
                put("totalTransactions", transactions.size)
                put("totalContracts", contracts.size)
                put("totalCardTransactions", cardTx.size)
            }

            // ── Финальная сборка ──────────────────────────────────────────
            val root = JSONObject().apply {
                put("snapshotTime", dateTimeFmt.format(Date(now)))
                put("todayDate", dateFmt.format(Date(now)))
                put("timezone", TimeZone.getDefault().id)
                put("stats", stats)
                put("settings", settingsObj)
                put("renters", rentersArray)
                put("scooters", scootersArray)
                put("virtualCards", cardsArray)
                put("recentTransactions", txArray)
                put("recentContracts", contractsArray)
                put("recentCardTransactions", cardTxArray)
            }

            val fullJson = root.toString(2)
            // Жёсткий лимит: если снимок слишком большой, обрезаем.
            // Это предотвратит ошибки парсинга ответа Mistral (когда вход
            // слишком длинный, модель может выдать пустой/обрезанный ответ).
            if (fullJson.length > MAX_SNAPSHOT_CHARS) {
                Log.w(TAG, "buildJson: snapshot too large (${fullJson.length} chars), truncating to $MAX_SNAPSHOT_CHARS")
                // Обрезаем и закрываем JSON вручную, чтобы оставался валидным
                val truncated = fullJson.substring(0, MAX_SNAPSHOT_CHARS)
                // Находим последнюю закрывающую скобку объекта/массива
                val lastBrace = truncated.lastIndexOf('}')
                if (lastBrace > 0) {
                    // Обрезаем до последней полной записи + закрываем root-объект
                    val safeCut = truncated.substring(0, lastBrace + 1)
                    val result = safeCut + "\n  \"_truncated\": true\n}"
                    Log.d(TAG, "buildJson: truncated to ${result.length} chars")
                    return@withContext result
                }
            }
            Log.d(TAG, "buildJson: snapshot size = ${fullJson.length} chars")
            fullJson
        } catch (e: Exception) {
            Log.e(TAG, "buildJson failed", e)
            JSONObject().apply {
                put("error", e.message ?: "unknown")
                put("snapshotTime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            }.toString(2)
        }
    }

    /** Округление до 2 знаков — чтобы в JSON не было 0.30000000000000004. */
    private fun round2(v: Double): Double {
        return Math.round(v * 100.0) / 100.0
    }
}
