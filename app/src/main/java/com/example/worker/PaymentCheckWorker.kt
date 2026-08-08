package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.AppDatabase
import com.example.data.ContractHistoryEntry
import com.example.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * • Периодически (раз в час) проверяет арендаторов:
 *     — если срок аренды истёк → автоматически продлевает контракт на 1 неделю
 *       (rentStartDateTimestamp += 7 дней, rentDurationDays += 7),
 *       списывает weeklyPrice с баланса, создаёт запись AUTO_RENEW
 *       с полными денормализованными полями (для PDF и истории).
 *       Баланс уходит в минус → это и есть долг.
 *     — если после продления баланс < 0 (есть долг) И SMS ещё не отправлено →
 *       планируется уведомление на 01:00 следующего дня.
 * • Одноразовый режим (KEY_ONE_TIME=true) — шлёт уведомление
 *   сразу (используется при создании арендатора с просрочкой
 *   и для запланированных 01:00 напоминаний).
 */
class PaymentCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(applicationContext)
            val settingsRepo = SettingsRepository(applicationContext)
            val now = System.currentTimeMillis()

            NotificationHelper.createChannel(applicationContext)

            val renterId = inputData.getInt(KEY_RENTER_ID, -1)
            val isOneTime = inputData.getBoolean(KEY_ONE_TIME, false)

            if (isOneTime && renterId != -1) {
                handleOneTimeNotification(db, renterId)
                return@withContext Result.success()
            }

            val activeRenters = db.renterDao().getActiveRenters()
            for (renter in activeRenters) {
                // ── Пропускаем арендаторов в режиме MANUAL ───────────────────
                // Пользователь добавил переключатель «Статус» в форму арендатора:
                //   • MANUAL — система НЕ создаёт контракты автоматически при
                //     окончании последнего контракта по дате.
                //   • AUTO   — система автоматически продлевает контракт на 1
                //     неделю при наступлении дня окончания (прежнее поведение).
                // Значение по умолчанию для всех арендаторов — MANUAL, поэтому
                // авто-продление выполняется только для тех, кого пользователь
                // явно переключил в AUTO.
                if (renter.autoRenewMode != com.example.data.RenterAutoRenewMode.AUTO) {
                    continue
                }

                // ── Проверка STOP_MARKER без последующего RESUME_MARKER ──────
                // Если у арендатора есть STOP-маркер (TYPE_TERMINATED + notes=
                // "STOP_MARKER") и после него НЕТ RESUME-маркера (TYPE_RETURNED
                // + notes="RESUME_MARKER") — значит аренда приостановлена и
                // авто-продление НЕ должно создаваться. Это критично, иначе
                // Worker будет плодить неоплаченные контракты даже после того,
                // как пользователь явно остановил аренду через календарь.
                //
                //   • Если последний STOP в прошлом (дата уже наступила) —
                //     аренда остановлена, пропускаем авто-продление.
                //   • Если последний STOP в будущем — аренда ещё активна, но
                //     мы не должны создавать контракты ЗА пределами STOP.
                //     Сравниваем expiryTime (конец последнего контракта по
                //     расчёту) с ближайшим будущим STOP: если expiry >= STOP,
                //     не продлеваем.
                val allEntries = db.contractHistoryDao().getForRenter(renter.id)
                val stopMarkers = allEntries.filter {
                    it.type == ContractHistoryEntry.TYPE_TERMINATED &&
                        it.notes == "STOP_MARKER" &&
                        it.weekStart != null
                }
                val resumeMarkers = allEntries.filter {
                    it.type == ContractHistoryEntry.TYPE_RETURNED &&
                        it.notes == "RESUME_MARKER" &&
                        it.weekStart != null
                }

                // Ищем «активный» STOP: такой, после которого нет RESUME.
                // Берём последний STOP по дате и проверяем, есть ли RESUME
                // с датой СТРОГО больше STOP. Если нет — STOP активен.
                val lastStop = stopMarkers.maxByOrNull { it.weekStart!! }
                val activeStop = if (lastStop != null) {
                    val hasResumeAfter = resumeMarkers.any { it.weekStart!! > lastStop.weekStart!! }
                    if (!hasResumeAfter) lastStop else null
                } else null

                if (activeStop != null) {
                    val stopDayStart = activeStop.weekStart!!
                    val expiryTime = renter.rentStartDateTimestamp +
                        (renter.rentDurationDays * 24L * 60 * 60 * 1000)
                    // Если аренда уже истекла по сроку И STOP уже наступил —
                    // это остановленный арендатор, не продлеваем.
                    // Если срок ещё идёт, но STOP в будущем — продлеваем только
                    // если expiry < STOP (новая неделя полностью до STOP).
                    if (now >= expiryTime && stopDayStart <= now) {
                        Log.d(TAG, "Skip auto-renew for renter #${renter.id}: " +
                            "active STOP_MARKER at ${stopDayStart} (past, no RESUME)")
                        continue
                    }
                    if (expiryTime >= stopDayStart) {
                        Log.d(TAG, "Skip auto-renew for renter #${renter.id}: " +
                            "new week would cross STOP_MARKER at ${stopDayStart}")
                        continue
                    }
                }

                val expiryTime = renter.rentStartDateTimestamp +
                    (renter.rentDurationDays * 24L * 60 * 60 * 1000)

                if (now >= expiryTime) {
                    // Всегда продлеваем на 1 неделю и списываем weeklyPrice.
                    // Баланс уходит в минус → это долг. Так появляется новый контракт
                    // в истории и обновляются даты в таблице арендатора.
                    val newBalance = autoRenew(db, settingsRepo, renter, now)

                    if (newBalance < 0.0 && !renter.isOverdueSmsSent) {
                        // Есть долг — планируем уведомление на 01:00.
                        scheduleNextOneAmNotification(applicationContext, renter.id)
                        Log.d(TAG, "Scheduled 01:00 notification for renter #${renter.id}")
                    }
                }
            }

            // ── Пересчёт ЭФФЕКТИВНОГО баланса для ВСЕХ активных арендаторов ──
            // По требованию пользователя: баланс должен отражать ТЕКУЩЕЕ
            // состояние долгов и предоплат, а не «историческую» сумму всех
            // контрактов. Логика (см. ContractHistoryEntry.computeEffectiveBalance):
            //   • Оплаченный контракт с weekEnd > now  → +amount (предоплата).
            //   • Оплаченный контракт с weekEnd <= now → 0 (скутер отработал).
            //   • Неоплаченный контракт с weekStart <= now → −amount (долг).
            //   • Неоплаченный контракт с weekStart > now → 0 (платить рано).
            //
            // Это критично для сценария «первый день последнего неоплаченного
            // контракта = сегодня» — баланс должен стать минусом, а статус —
            // красным. Раньше баланс оставался «плюсом» из-за прошлых
            // оплаченных контрактов, и статус был зелёным.
            //
            // Запускается КАЖДЫЙ час (вместе с PaymentCheckWorker), поэтому
            // баланс всегда свежий (с погрешностью до 1 часа).
            for (renter in activeRenters) {
                try {
                    val contracts = db.contractHistoryDao().getContractsForRenterOnce(renter.id)
                    val effectiveBalance = ContractHistoryEntry.computeEffectiveBalance(contracts, now)
                    // Сравниваем с сохранённым балансом. Если отличается
                    // больше чем на 1 сум — обновляем (защита от дробления).
                    if (kotlin.math.abs(effectiveBalance - renter.balance) > 1.0) {
                        val updated = renter.copy(
                            balance = effectiveBalance,
                            debtAmount = maxOf(0.0, -effectiveBalance)
                        )
                        db.renterDao().updateRenter(updated)
                        Log.d(TAG, "refreshBalance: renter #${renter.id} " +
                            "${renter.balance} → $effectiveBalance")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "refreshBalance failed for renter #${renter.id}", e)
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "PaymentCheckWorker failed", e)
            Result.retry()
        }
    }

    /**
     * Продлевает контракт арендатора на 1 неделю:
     *   • rentStartDateTimestamp НЕ МЕНЯЕТСЯ — это первоначальная дата начала
     *     аренды, она должна оставаться неизменной для корректного отображения
     *     в PDF-договоре и UI. Раньше здесь было += 7 дней, что приводило к
     *     «поползанию» даты начала вперёд после каждого продления и потере
     *     первоначальной информации об аренде.
     *   • rentDurationDays += 7 (срок аренды растёт — это правильно)
     *   • balance -= weeklyPrice (уходит в минус = долг)
     *   • debtAmount = max(0, -balance) (синхронизация)
     *   • isOverdueSmsSent = false (новая неделя — можно снова слать SMS)
     *   • Создаёт запись AUTO_RENEW со всеми денормализованными полями.
     *
     * ── ВАЖНО: расчёт newWeekStart через последний контракт ──────────
     * Раньше newWeekStart вычислялся как rentStartDateTimestamp +
     * rentDurationDays * dayMs. Это работало для классического сценария,
     * но ломалось при наличии RESUME_MARKER:
     *
     *   • Пользователь поставил RESUME_MARKER на прошлую дату (например,
     *     через ensureResumeMarkerOnLastContractDay или вручную).
     *   • rentStartDateTimestamp + rentDurationDays * dayMs указывает на
     *     дату в далёком прошлом (когда закончился бы «идеальный» контракт).
     *   • Защита от дубликатов findContractForWeek находит существующий
     *     контракт на эту дату → skip → новых контрактов не создаётся.
     *
     * Новая логика:
     *   1. Загружаем все обычные контракты (CREATED + AUTO_RENEW).
     *   2. Находим max(weekEnd) — это «последний контракт».
     *   3. newWeekStart = lastContractWeekEnd + 1ms (т.е. сразу после).
     *   4. Если нет контрактов (только что создан renter без CREATED) —
     *      используем rentStartDateTimestamp как fallback.
     *   5. Если newWeekStart > now (новая неделя ещё не началась) —
     *      не создаём, выходим.
     *   6. Иначе создаём AUTO_RENEW с newWeekStart → newWeekStart + 7d
     *      и ЦИКЛИЧЕСКИ продолжаем, пока новая неделя уже началась.
     *      Это позволяет «догнать» пропущенные недели за один запуск Worker,
     *      вместо того чтобы создавать по одному контракту в час.
     *
     * Возвращает новый баланс (для решения о SMS-уведомлении).
     */
    private suspend fun autoRenew(
        db: AppDatabase,
        settingsRepo: SettingsRepository,
        renter: com.example.data.Renter,
        now: Long
    ): Double {
        val dayMs = 24L * 60 * 60 * 1000
        val sevenDays = 7L * dayMs
        val weeklyPrice = settingsRepo.weeklyPrice.let {
            if (it > 0) it else SettingsRepository.DEFAULT_WEEKLY_PRICE
        }

        var currentBalance = renter.balance
        var currentDuration = renter.rentDurationDays
        var iterations = 0
        val maxIterations = 60  // защита от бесконечного цикла (~1 год)

        // ── Цикл создания контрактов «догоняющим» режимом ──────────────
        // На каждой итерации:
        //   1. Загружаем все обычные контракты (CREATED + AUTO_RENEW).
        //   2. Находим последний (max weekEnd).
        //   3. newWeekStart = lastContract.weekEnd + 1ms.
        //   4. Проверяем активный STOP_MARKER — если newWeekStart >= STOP,
        //      прекращаем (не создаём контракты за пределами STOP).
        //   5. Если newWeekStart > now — новая неделя ещё не началась,
        //      прекращаем (раньше здесь было newWeekEnd > now, что заставляло
        //      Worker ждать неделю после RESUME_MARKER — это и было источником
        //      жалобы «auto-RESUME_MARKER не работает»).
        //   6. Если для этой недели уже есть контракт (дедупликация) — прекращаем.
        //   7. Создаём AUTO_RENEW, обновляем баланс и длительность арендатора.
        //   8. Повторяем.
        while (iterations < maxIterations) {
            iterations++

            // ── Загружаем все обычные контракты арендатора ──────────────
            val allContracts = db.contractHistoryDao().getContractsForRenterOnce(renter.id)
            val lastContract = allContracts
                .filter { it.weekEnd != null }
                .maxByOrNull { it.weekEnd!! }

            // ── Вычисляем начало новой недели ───────────────────────────
            val newWeekStart: Long = if (lastContract != null) {
                lastContract.weekEnd!! + 1L  // +1ms — не накладывается на последний день
            } else {
                renter.rentStartDateTimestamp + currentDuration * dayMs
            }
            val newWeekEnd = newWeekStart + sevenDays

            // ── Проверка STOP_MARKER ────────────────────────────────────
            // Если есть активный STOP_MARKER (без последующего RESUME_MARKER),
            // и newWeekStart >= STOP — прекращаем создание контрактов.
            // Это prevents создание контрактов после STOP.
            val allEntries = db.contractHistoryDao().getForRenter(renter.id)
            val stopMarkers = allEntries.filter {
                it.type == ContractHistoryEntry.TYPE_TERMINATED &&
                    it.notes == "STOP_MARKER" &&
                    it.weekStart != null
            }
            val resumeMarkers = allEntries.filter {
                it.type == ContractHistoryEntry.TYPE_RETURNED &&
                    it.notes == "RESUME_MARKER" &&
                    it.weekStart != null
            }
            val lastStop = stopMarkers.maxByOrNull { it.weekStart!! }
            val activeStop = if (lastStop != null) {
                val hasResumeAfter = resumeMarkers.any { it.weekStart!! > lastStop.weekStart!! }
                if (!hasResumeAfter) lastStop else null
            } else null
            if (activeStop != null && newWeekStart >= activeStop.weekStart!!) {
                Log.d(TAG, "autoRenew: stop creating for renter #${renter.id} at " +
                    "newWeekStart=$newWeekStart — would cross STOP_MARKER " +
                    "at ${activeStop.weekStart}")
                break
            }

            // ── Если новая неделя ещё НЕ началась — прекращаем ─────────
            // Раньше: if (newWeekEnd > now) — это заставляло Worker ждать
            // неделю после RESUME_MARKER, и пользователь не видел новых
            // контрактов. Теперь: создаём контракт для любой начавшейся
            // недели — даже если она ещё не закончилась.
            if (newWeekStart > now) {
                Log.d(TAG, "autoRenew: stop for renter #${renter.id} — newWeekStart " +
                    "$newWeekStart is in the future (now=$now), iter=$iterations")
                break
            }

            // ── Защита от дубликатов ───────────────────────────────────
            val existing = db.contractHistoryDao().getContractForWeek(renter.id, newWeekStart)
            if (existing != null) {
                Log.d(TAG, "autoRenew: stop for renter #${renter.id} — contract " +
                    "#${existing.id} for weekStart=$newWeekStart already exists")
                break
            }

            // ── Создаём AUTO_RENEW контракт ────────────────────────────
            currentBalance = currentBalance - weeklyPrice
            currentDuration = currentDuration + 7
            val renewed = renter.copy(
                // ВАЖНО: rentStartDateTimestamp НЕ трогаем — это первоначальная дата.
                rentDurationDays = currentDuration,
                balance = currentBalance,
                debtAmount = maxOf(0.0, -currentBalance),
                isOverdueSmsSent = false
            )
            db.renterDao().updateRenter(renewed)

            // ── Подтягиваем реквизиты скутера для PDF-денормализации ──────
            val scooter = renter.scooterId?.let { db.scooterDao().getScooterById(it) }

            db.contractHistoryDao().insert(
                ContractHistoryEntry(
                    renterId = renter.id,
                    timestamp = now,
                    type = ContractHistoryEntry.TYPE_AUTO_RENEW,
                    amount = weeklyPrice,
                    notes = "Avtomatik yangilanish +7 kun",
                    renterName = renter.name,
                    renterPhone = renter.phoneNumber,
                    scooterName = renter.scooterName,
                    weekStart = newWeekStart,
                    weekEnd = newWeekEnd,
                    weeklyPrice = weeklyPrice,
                    passportData = renter.passportData,
                    address = renter.address,
                    pinfl = renter.pinfl,
                    vinNumber = scooter?.vinNumber ?: "",
                    engineNumber = scooter?.engineNumber ?: "",
                    scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                    batteryId1 = scooter?.batteryId1 ?: "",
                    batteryId2 = scooter?.batteryId2 ?: "",
                    additionalInfo = scooter?.additionalInfo ?: "",
                    isPaid = false  // авто-продление создаёт НЕОПЛАЧЕННЫЙ контракт (долг)
                )
            )
            Log.d(TAG, "autoRenew: renter #${renter.id} week $newWeekStart → $newWeekEnd " +
                    "created (iter=$iterations), balance now $currentBalance")
        }

        if (iterations > 1) {
            Log.d(TAG, "autoRenew: created ${iterations - 1} contract(s) for renter #${renter.id} " +
                    "in one worker run")
        }
        return currentBalance
    }

    private suspend fun handleOneTimeNotification(
        db: AppDatabase,
        renterId: Int
    ) {
        val renter = db.renterDao().getRenterById(renterId) ?: return
        if (renter.isReturned) return
        val lastPayment = renter.lastPaymentTimestamp ?: 0L
        if (lastPayment < renter.rentStartDateTimestamp) {
            NotificationHelper.postPaymentDueNotification(
                applicationContext, renter.id, renter.name, renter.phoneNumber
            )
        }
    }

    companion object {
        private const val TAG = "PaymentCheckWorker"
        const val KEY_RENTER_ID = "renterId"
        const val KEY_ONE_TIME = "isOneTimeReminder"

        fun scheduleNextOneAmNotification(context: Context, renterId: Int) {
            val nextOneAm = nextOneAmMillis()
            val delay = (nextOneAm - System.currentTimeMillis()).coerceAtLeast(0L)
            val work = OneTimeWorkRequestBuilder<PaymentCheckWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(
                        KEY_RENTER_ID to renterId,
                        KEY_ONE_TIME to true
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "scheduled_1am_$renterId",
                ExistingWorkPolicy.REPLACE,
                work
            )
            Log.d(TAG, "Scheduled 01:00 notification for renter #$renterId in ${delay / 1000}s")
        }

        private fun nextOneAmMillis(): Long {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 1)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }
    }
}
