package com.example.ui

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.AppDatabase
import com.example.data.ContractHistoryEntry
import com.example.data.Renter
import com.example.data.RenterRepository
import com.example.data.SettingsRepository
import com.example.data.Scooter
import com.example.data.Transaction
import com.example.worker.NotificationHelper
import com.example.worker.PaymentCheckWorker
import com.example.worker.SimHelper
import com.example.worker.SmsWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SmsResult(
    val success: Boolean,
    val message: String,
    val errorCode: String? = null,
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null
) {
    val fullDetails: String
        get() = buildString {
            appendLine(message)
            if (errorCode != null) appendLine("Xato kodi: $errorCode")
            if (exceptionClass != null) appendLine("Exception: $exceptionClass")
            if (exceptionMessage != null) appendLine("Tushuntirish: $exceptionMessage")
        }
}

class RenterViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: RenterRepository
    private val historyRepository: com.example.data.ContractHistoryRepository
    private val transactionRepository: com.example.data.TransactionRepository
    private val virtualCardRepository: com.example.data.VirtualCardRepository
    private val actionUseCase: com.example.data.RenterActionUseCase
    val rentersList: StateFlow<List<Renter>>
    /** Только активные арендаторы (isDeleted=0) — для обычного режима. */
    val liveRenters: StateFlow<List<Renter>>
    /** Только удалённые в корзину (isDeleted=1) — для trash mode. */
    val trashedRenters: StateFlow<List<Renter>>
    /**
     * Архивные арендаторы — активные в БД (isDeleted=0), НО у которых
     * есть STOP-маркер (TYPE_TERMINATED + notes="STOP_MARKER") в прошлом
     * (weekStart < сегодня), и ПОСЛЕ этой даты нет ни одного RESUME-маркера
     * (TYPE_RETURNED + notes="RESUME_MARKER").
     *
     * Это арендаторы, чья аренда приостановлена и не возобновлена —
     * пользователь переводит их в «архив» долгим нажатием на «✎».
     */
    val archivedRenters: StateFlow<List<Renter>>
    /** TrashService для каскадного soft-delete/restore/permanent-delete. */
    val trashService: com.example.data.TrashService

    private var smsSendCounter = 0

    private val _smsResults = MutableSharedFlow<SmsResult>(extraBufferCapacity = 10)
    val smsResults: SharedFlow<SmsResult> = _smsResults

    init {
        val database = AppDatabase.getDatabase(application)
        repository = RenterRepository(database.renterDao())
        historyRepository = com.example.data.ContractHistoryRepository(
            database.contractHistoryDao()
        )
        transactionRepository = com.example.data.TransactionRepository(database.transactionDao())
        virtualCardRepository = com.example.data.VirtualCardRepository(
            database.virtualCardDao(),
            database.cardTransactionDao()
        )
        actionUseCase = com.example.data.RenterActionUseCase.fromContext(application)
        rentersList = repository.allRenters.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        liveRenters = repository.liveRenters.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        trashedRenters = repository.trashedRenters.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        // ── Архивные арендаторы ────────────────────────────────────────────
        // Арендатор считается архивным по следующей логике (по требованию
        // пользователя, обновлённая версия):
        //   1. Есть STOP-маркер (TYPE_TERMINATED + notes="STOP_MARKER")
        //      с датой <= сегодня (STOP сегодня или в прошлом).
        //   2. Берём ПОСЛЕДНИЙ (самый поздний) такой STOP.
        //   3. После этого STOP НЕТ ни одного RESUME-маркера
        //      (TYPE_RETURNED + notes="RESUME_MARKER") — ни между STOP-днём
        //      и сегодня, ни сегодня, ни в будущем.
        // Если все три условия → арендатор в архиве.
        //
        // Это покрывает два сценария пользователя:
        //   A. STOP сегодня + нет RESUME в будущем → архив.
        //   B. STOP в прошлом + нет RESUME после него (между STOP и сегодня,
        //      сегодня, или в будущем) → архив.
        //   Если после последнего STOP есть RESUME (в любой день — прошлый
        //   между STOP и сегодня, сегодня, или будущий) → активен.
        //
        // Все STOP и RESUME маркеры сохраняются в БД — архивация не удаляет
        // их. Пользователь может вывести арендатора из архива, поставив
        // RESUME-маркер на сегодня (restoreRenterFromArchive).
        //
        // combine(liveRenters, liveContracts) — когда меняется любой из
        // источников, пересчитывается список архивных. liveContracts включает
        // в себя маркеры STOP/RESUME (это просто TYPE_TERMINATED/RETURNED
        // с isDeleted=0), поэтому реагирует на их добавление/удаление.
        archivedRenters = kotlinx.coroutines.flow.combine(
            repository.liveRenters,
            historyRepository.liveContracts
        ) { renters, contracts ->
            val todayStart = run {
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            // Группируем все записи по renterId — нужно для проверки
            // «есть ли RESUME после STOP».
            val byRenter: Map<Int, List<ContractHistoryEntry>> =
                contracts.groupBy { it.renterId }
            renters.filter { renter ->
                isRenterArchived(byRenter[renter.id] ?: emptyList(), todayStart)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        trashService = com.example.data.TrashService(
            renterRepository = repository,
            scooterRepository = com.example.data.ScooterRepository(database.scooterDao()),
            contractRepository = historyRepository,
            transactionRepository = transactionRepository,
            virtualCardRepository = virtualCardRepository
        )
    }

    fun startAutoSync() { /* no-op: local-only mode */ }
    fun stopAutoSync() { /* no-op: local-only mode */ }

    /**
     * Создаёт нового арендатора и один или несколько контрактов в зависимости
     * от выбранной даты начала аренды. Новая логика (по запросу пользователя):
     *
     *   • Если выбранная дата была БОЛЕЕ НЕДЕЛИ назад (now - start > 7 дней):
     *     создаётся ОДИН контракт с МИНУСОВЫМ значением (долг).
     *     balance = -weeklyPrice, isPaid = false. Transaction НЕ создаётся,
     *     на карту ничего не зачисляется (деньги ещё не пришли).
     *
     *   • Если выбранная дата МЕНЕЕ НЕДЕЛИ назад (0 < now - start ≤ 7 дней):
     *     создаётся ОДИН контракт с ПЛЮСОВЫМ значением (предоплата).
     *     balance = 0, isPaid = true. Transaction(+weeklyPrice) создаётся,
     *     на главную карту зачисляется weeklyPrice.
     *
     *   • Если выбранная дата = сегодня или в будущем (start ≥ now):
     *     создаётся НЕСКОЛЬКО контрактов с ПЛЮСОВЫМ значением — по одному
     *     на каждую неделю от today до выбранного дня. Все с isPaid = true,
     *     для каждой создаётся Transaction и зачисление на карту.
     *
     *   • Если при создании передан список групп контрактов (contractGroups),
     *     они имеют приоритет над автоматической логикой выше. Для каждой
     *     группы создаётся отдельный контракт с weekStart = группа.startMs,
     *     weekEnd = группа.endMs. Знак контракта (долг/предоплата) определяется
     *     по той же логике относительно конца этой группы.
     *
     * При удалении контракта (deleteContractWithCascade в ContractHistoryViewModel)
     * каскадно удаляются все связанные Transaction и CardTransaction, а баланс
     * арендатора и главной карты реверсится.
     */
    fun addRenter(
        name: String, phone: String, debt: Double, duration: Int,
        startTimestamp: Long, scooterId: Int?, scooterName: String?, weeklyPrice: Double,
        // PDF-реквизиты арендатора
        passportData: String = "",
        address: String = "",
        pinfl: String = "",
        // ── Режим авто-продления контракта ────────────────────────────────
        // AUTO (по умолчанию) — система создаёт AUTO_RENEW при окончании
        // последнего контракта. Пользователь может переключить в MANUAL.
        autoRenewMode: String = com.example.data.RenterAutoRenewMode.AUTO,
        // ── Группы контрактов (из календаря формы) ────────────────────────
        // Если список не пуст — он имеет приоритет над автогенерацией по
        // выбранной дате. Каждая группа = один контракт с weekStart/weekEnd
        // и isPaid из группы. Список — это List<Triple<startMs, endMs, isPaid>>.
        contractGroups: List<Triple<Long, Long, Boolean>> = emptyList(),
        // ── Полный список групп с маркерами Stop/Resume ──────────────────
        // Используется для обработки Stop/Resume-маркеров при создании
        // арендатора. Если список не пуст — он имеет приоритет над обычным
        // contractGroups: маркеры сохраняются как TERMINATED/RETURNED записи,
        // а для Resume-маркеров генерируются неоплаченные weekly-контракты
        // (см. applyResumeAutoContracts). Обычные контракты из этого списка
        // обрабатываются по той же логике, что и contractGroups.
        contractGroupsWithMarkers: List<com.example.RenterFormContractGroup> = emptyList()
    ) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val dayMs = 24L * 60 * 60 * 1000
            val weekMs = 7L * dayMs

            val expiryTime = startTimestamp + duration * dayMs

            val effectiveWeeklyPrice = if (weeklyPrice > 0) weeklyPrice else SettingsRepository.DEFAULT_WEEKLY_PRICE
            // ── Дневная ставка для сценария календаря (scenario 4) ──────────
            // Контракты из календаря могут быть любой длины (5, 9, 14 дней),
            // поэтому их сумма должна вычисляться по фактическим дням:
            //   contractAmount = dailyPrice × ceil((endMs - startMs) / dayMs)
            // Ранее использовался effectiveWeeklyPrice (= 7 дней), что приводило
            // к багу: контракт на 9 дней получал сумму 7 дней.
            val settingsRepoForDaily = SettingsRepository(getApplication())
            val effectiveDailyPrice = if (settingsRepoForDaily.dailyPrice > 0)
                settingsRepoForDaily.dailyPrice else SettingsRepository.DEFAULT_DAILY_PRICE

            // Хелпер: вычисляет сумму контракта по его длине в днях.
            // Для scenario 4 (календарные группы) — по дням.
            // Для остальных сценариев — effectiveWeeklyPrice (совместимость).
            fun contractAmountFor(startMs: Long, endMs: Long, isCalendarGroup: Boolean): Double {
                if (!isCalendarGroup) return effectiveWeeklyPrice
                // ── Модель «отель/ночь» ───────────────────────────────────────
                // Период 7→14 = 7 дней аренды (день выезда не оплачивается).
                // Используем ЦЕЛОЧИСЛЕННОЕ деление (floor), а не ceil:
                //   • Если endMs = start + N*dayMs (точное кратное) → days = N.
                //   • Если есть «хвост» (< 1 дня, например от старых контрактов
                //     с endMs = конец дня 23:59:59.999) → он отбрасывается,
                //     чтобы не было лишнего дня.
                // Раньше ceil давал 8 дней для периода 7→14 если endMs имел
                // любой ненулевой хвост (даже 1 мс) — пользователь видел 480000
                // вместо ожидаемых 420000.
                val days = if (endMs > startMs) {
                    ((endMs - startMs) / dayMs).toInt().coerceAtLeast(1)
                } else 1
                return effectiveDailyPrice * days
            }

            // ── Определяем сценарий создания ──────────────────────────────
            //   SCENARIO_OVERDUE   — выбранная дата была более недели назад
            //   SCENARIO_RECENT    — выбранная дата менее недели назад
            //   SCENARIO_FUTURE    — выбранная дата сегодня или в будущем
            //   SCENARIO_GROUPS    — передан явный список групп из календаря
            //   SCENARIO_GROUPS_WITH_MARKERS — передан список с Stop/Resume маркерами
            val diffMs = now - startTimestamp
            val scenario = when {
                contractGroupsWithMarkers.isNotEmpty() -> 5 // SCENARIO_GROUPS_WITH_MARKERS
                contractGroups.isNotEmpty() -> 4 // SCENARIO_GROUPS
                diffMs > weekMs -> 1             // SCENARIO_OVERDUE
                diffMs > 0L -> 2                 // SCENARIO_RECENT
                else -> 3                        // SCENARIO_FUTURE (today или future)
            }

            // ── Вычисляем список контрактов для создания ───────────────────
            // Каждый элемент: Triple<weekStart, weekEnd, isPaid>
            // isPaid = true → предоплата (плюс), balance += weeklyPrice,
            //               создаётся Transaction, depositContractIncome.
            // isPaid = false → долг (минус), balance -= weeklyPrice,
            //                Transaction НЕ создаётся, на карту ничего не падает.
            data class ContractSpec(val weekStart: Long, val weekEnd: Long, val isPaid: Boolean)

            // ── Маркеры Stop/Resume (сохраняются отдельно как TERMINATED/RETURNED) ──
            // Эти записи не являются контрактами — это однодневные сигналы для:
            //   • Stop — аренда приостановлена, скутер свободен (TYPE_TERMINATED).
            //   • Resume — аренда возобновлена, запускает авто-создание неоплаченных
            //     weekly-контрактов от этого дня вперёд (TYPE_RETURNED + notes=RESUME_MARKER).
            val stopMarkers = mutableListOf<Pair<Long, Long>>()
            val resumeMarkers = mutableListOf<Pair<Long, Long>>()

            val specs: List<ContractSpec> = when (scenario) {
                5 -> {
                    // ── Сценарий с маркерами: обрабатываем contractGroupsWithMarkers ──
                    // Разделяем группы на:
                    //   • Обычные контракты (isStopMarker=false && isResumeMarker=false)
                    //     — добавляются в specs как есть.
                    //   • Stop-маркеры — добавляются в stopMarkers, будут сохранены
                    //     как TYPE_TERMINATED.
                    //   • Resume-маркеры — добавляются в resumeMarkers, будут сохранены
                    //     как TYPE_RETURNED + notes=RESUME_MARKER. Для каждого Resume
                    //     маркера также генерируются неоплаченные weekly-контракты
                    //     (см. ниже).
                    val regularSpecs = mutableListOf<ContractSpec>()
                    for (g in contractGroupsWithMarkers) {
                        when {
                            g.isStopMarker -> stopMarkers.add(g.startMs to g.endMs)
                            g.isResumeMarker -> resumeMarkers.add(g.startMs to g.endMs)
                            else -> regularSpecs.add(ContractSpec(g.startMs, g.endMs, g.isPaid))
                        }
                    }

                    // ── Авто-создание неоплаченных weekly-контрактов от Resume-маркеров ──
                    // Алгоритм (по описанию пользователя):
                    //   Для каждого Resume-маркера на дате R:
                    //     1. Найти ближайший Stop-маркер на дате S > R (если есть).
                    //     2. Если S существует и S ∈ [R, R+7 дней]:
                    //        - Создать 1 неоплаченный контракт R → S-1 (или R → S,
                    //          если S-R < 1 дня — однодневный контракт).
                    //     3. Иначе: создать 1 неоплаченный контракт R → R+7 дней.
                    //     4. Если есть следующий Resume-маркер R' > S: повторить
                    //        шаги 1-3 от R'.
                    //   Дополнительно (заполнение назад):
                    //     - Если в [today, R-1] нет существующих контрактов и
                    //       нет Stop-маркеров — создать неоплаченные weekly-контракты
                    //       назад от R-1 до today (покрывая «пустоту»).
                    val allMarkerDays = (stopMarkers.map { it.first } + resumeMarkers.map { it.first })
                        .sorted()
                    val generated = mutableListOf<ContractSpec>()

                    for ((resumeStart, _) in resumeMarkers.sortedBy { it.first }) {
                        // ── Защита от дубликатов ──────────────────────────────
                        // Календарь в RenterFormDialog АВТОСОЗДАЁТ недельные
                        // неоплаченные контракты сразу при тапе на Resume-день
                        // (см. ContractCalendar.onDayClick → dayMarkerMode==2).
                        // Эти контракты попадают в regularSpecs как обычные
                        // группы. Если мы здесь снова сгенерируем контракты от
                        // того же Resume-маркера — получим дубликаты в БД.
                        // Поэтому: если в regularSpecs уже есть контракт,
                        // покрывающий resumeStart — пропускаем автогенерацию.
                        val alreadyCovered = regularSpecs.any { spec ->
                            resumeStart >= spec.weekStart && resumeStart <= spec.weekEnd
                        }
                        if (alreadyCovered) continue

                        // Forward: R → next Stop or R+7d
                        // Модель «отель/ночь»: endMs = start + N*dayMs (БЕЗ -1),
                        // дата окончания = «день выезда» (check-out).
                        // Если Stop в пределах недели — контракт до Stop-дня
                        // (Stop-день = check-out, не оплачивается).
                        val nextStop = stopMarkers.map { it.first }
                            .filter { it > resumeStart }
                            .minOrNull()
                        val forwardEnd = when {
                            nextStop != null && nextStop <= resumeStart + weekMs -> nextStop
                            else -> resumeStart + weekMs
                        }
                        if (forwardEnd > resumeStart) {
                            generated.add(ContractSpec(resumeStart, forwardEnd, isPaid = false))
                        }

                        // Backward: если нет контрактов в [today, R-1], заполнить
                        // Модель «отель/ночь»: каждый backward-контракт = 7 дней,
                        // end = start + 7d. Контракты делят конечные точки:
                        //   [R-7d, R], [R-14d, R-7d], [R-21d, R-14d], ...
                        // R-день = check-out последнего backward-контракта =
                        // check-in forward-контракта (разделяемый день).
                        val todayStart = run {
                            val cal = java.util.Calendar.getInstance()
                            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                            cal.set(java.util.Calendar.MINUTE, 0)
                            cal.set(java.util.Calendar.SECOND, 0)
                            cal.set(java.util.Calendar.MILLISECOND, 0)
                            cal.timeInMillis
                        }
                        if (resumeStart > todayStart) {
                            // Есть «пустота» между today и Resume-днём.
                            // Создаём weekly-контракты назад, НЕ заходя за Stop-маркер.
                            // Если в [today, R-1] есть Stop-маркер, то назад не заполняем
                            // (стоп означает «аренда была приостановлена» — пустота нормальна).
                            val stopInGap = stopMarkers.map { it.first }
                                .any { it in (todayStart until resumeStart) }
                            if (!stopInGap) {
                                var end = resumeStart
                                var guard = 0
                                while (end > todayStart && guard < 60) {
                                    val ws = end - 7 * dayMs
                                    val realWs = maxOf(ws, todayStart)
                                    generated.add(ContractSpec(realWs, end, isPaid = false))
                                    end = realWs
                                    guard++
                                }
                            }
                        }
                    }

                    regularSpecs + generated
                }
                4 -> {
                    // Сценарий групп: для каждой группы используем isPaid,
                    // который выбрал пользователь в календаре (кнопки статуса).
                    contractGroups.map { (gs, ge, isPaid) ->
                        ContractSpec(gs, ge, isPaid)
                    }
                }
                1 -> {
                    // Более недели назад → один контракт с минусом (долг)
                    listOf(ContractSpec(startTimestamp, startTimestamp + weekMs, isPaid = false))
                }
                2 -> {
                    // Менее недели назад → один контракт с плюсом (предоплата)
                    listOf(ContractSpec(startTimestamp, startTimestamp + weekMs, isPaid = true))
                }
                3 -> {
                    // Сегодня или в будущем → несколько контрактов с плюсом
                    // от today до выбранного дня. Каждая неделя = один контракт.
                    val specsList = mutableListOf<ContractSpec>()
                    var cursor = startTimestamp
                    val today = now
                    // Если старт в будущем, начинаем с сегодняшнего дня
                    if (cursor > today) {
                        cursor = today
                    }
                    // Создаём недели пока не достигнем выбранной даты
                    while (cursor < startTimestamp + dayMs) {
                        val ws = cursor
                        val we = cursor + weekMs
                        specsList.add(ContractSpec(ws, we, isPaid = true))
                        cursor = we
                        if (specsList.size > 52) break // защита от бесконечного цикла (макс 1 год)
                    }
                    specsList
                }
                else -> listOf(ContractSpec(startTimestamp, startTimestamp + weekMs, isPaid = true))
            }

            // ── Начальный баланс ───────────────────────────────────────────
            // Для каждого контракта: isPaid=true → +weeklyPrice (предоплата)
            //                        isPaid=false → -weeklyPrice (долг)
            // Также учитываем явный debt из формы (если указан).
            val contractsBalance = specs.fold(0.0) { acc, s ->
                val amt = contractAmountFor(s.weekStart, s.weekEnd, isCalendarGroup = (scenario == 4))
                acc + if (s.isPaid) amt else -amt
            }
            val initialBalance = when {
                debt > 0 -> -debt + contractsBalance
                else -> contractsBalance
            }
            val finalDebt = if (initialBalance < 0) -initialBalance else 0.0

            val provisional = Renter(
                name = name, phoneNumber = phone, debtAmount = finalDebt,
                rentDurationDays = duration, rentStartDateTimestamp = startTimestamp,
                scooterId = scooterId, scooterName = scooterName, balance = initialBalance,
                passportData = passportData, address = address, pinfl = pinfl,
                autoRenewMode = autoRenewMode
            )

            val localId = repository.insert(provisional).toInt()
            val savedRenter = provisional.copy(id = localId)

            // ── Подтягиваем реквизиты скутера из БД для PDF-договора ───────
            val scooter: Scooter? = scooterId?.let { fetchScooterById(it) }

            // ── Создание контрактов по specs ───────────────────────────────
            // Первый контракт — TYPE_CREATED, остальные — TYPE_AUTO_RENEW.
            // Для каждого контракта:
            //   • isPaid = true → создаём Transaction(+weeklyPrice) с contractId
            //     и depositContractIncome(+weeklyPrice, contractId) на главную карту.
            //   • isPaid = false → ничего, кроме записи контракта.
            // Это обеспечивает каскадную связь: при удалении контракта
            // (deleteContractWithCascade) автоматически удаляются Transaction
            // и реверсится CardTransaction главной карты.
            val shouldNotifyOverdue = specs.any { !it.isPaid }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val dateFmt = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
                    specs.forEachIndexed { idx, spec ->
                        val isFirst = idx == 0
                        val contractType = if (isFirst) ContractHistoryEntry.TYPE_CREATED
                                           else ContractHistoryEntry.TYPE_AUTO_RENEW
                        // ── Маркер календаря ─────────────────────────────────
                        // Если контракт создаётся через форму с contractGroups
                        // (т.е. пользователь выбрал периоды в календаре формы),
                        // добавляем подстроку "Kalendar orqali yaratildi" в notes.
                        // Это позволяет deleteContractWithCascade отличить
                        // календарные контракты от созданных через «To'lash»
                        // (applyWeeklyPayment) и применять к ним симметричную
                        // логику удаления (реверс баланса на ±amount).
                        val calendarMarker =
                            if (scenario == 4 || scenario == 5) "Kalendar orqali yaratildi — " else ""
                        val notes = when {
                            spec.isPaid && specs.size > 1 ->
                                "${calendarMarker}${idx + 1}-hafta oldindan to'lov"
                            spec.isPaid ->
                                "${calendarMarker}Yaratildi (oldindan to'langan)"
                            else ->
                                "${calendarMarker}Kechikkan holda yaratildi (qarz)"
                        }

                        // ── Сумма контракта зависит от сценария ──────────────
                        // Для scenario 4 и 5 (календарь) — по дням.
                        // Для остальных — effectiveWeeklyPrice (7 дней).
                        val contractAmount = contractAmountFor(
                            startMs = spec.weekStart,
                            endMs = spec.weekEnd,
                            isCalendarGroup = (scenario == 4 || scenario == 5)
                        )
                        val contractId = historyRepository.insert(ContractHistoryEntry(
                            renterId = savedRenter.id,
                            timestamp = now,
                            type = contractType,
                            amount = contractAmount,
                            notes = notes,
                            renterName = savedRenter.name,
                            renterPhone = savedRenter.phoneNumber,
                            scooterName = savedRenter.scooterName,
                            weekStart = spec.weekStart,
                            weekEnd = spec.weekEnd,
                            weeklyPrice = contractAmount,
                            passportData = savedRenter.passportData,
                            address = savedRenter.address,
                            pinfl = savedRenter.pinfl,
                            vinNumber = scooter?.vinNumber ?: "",
                            engineNumber = scooter?.engineNumber ?: "",
                            scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                            batteryId1 = scooter?.batteryId1 ?: "",
                            batteryId2 = scooter?.batteryId2 ?: "",
                            additionalInfo = scooter?.additionalInfo ?: "",
                            isPaid = spec.isPaid
                        )).toInt()

                        // ── Для оплаченного контракта: создаём Transaction ──
                        // и зачисляем сумму на главную карту (Glavnaya) через
                        // depositContractIncome. Это обеспечивает корректный
                        // каскадный реверс при удалении контракта.
                        if (spec.isPaid && contractId > 0) {
                            try {
                                val wsStr = dateFmt.format(java.util.Date(spec.weekStart))
                                val weStr = dateFmt.format(java.util.Date(spec.weekEnd))
                                val contractLabel = "#$contractId  $wsStr → $weStr"
                                transactionRepository.insert(
                                    com.example.data.Transaction(
                                        contractId = contractId,
                                        renterId = savedRenter.id,
                                        scooterId = savedRenter.scooterId,
                                        timestamp = now,
                                        type = com.example.data.Transaction.TYPE_PAYMENT,
                                        amount = contractAmount,
                                        notes = notes,
                                        renterName = savedRenter.name,
                                        renterPhone = savedRenter.phoneNumber,
                                        scooterName = savedRenter.scooterName ?: "",
                                        contractLabel = contractLabel
                                    )
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to insert prepaid Transaction for contract #$contractId: ${e.message}")
                            }

                            try {
                                virtualCardRepository.depositContractIncome(
                                    amount = contractAmount,
                                    note = "To'lov: ${savedRenter.name} — #$contractId",
                                    contractId = contractId
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "depositContractIncome failed for new contract #$contractId: ${e.message}")
                            }
                        }
                    }

                    // ── Сохранение Stop/Resume маркеров как отдельных записей ──
                    // (только для scenario == 5 — SCENARIO_GROUPS_WITH_MARKERS)
                    // Stop-маркер → ContractHistoryEntry(type=TERMINATED, isPaid=false,
                    //   notes="STOP_MARKER", weekStart=weekEnd=day)
                    // Resume-маркер → ContractHistoryEntry(type=RETURNED, isPaid=false,
                    //   notes="RESUME_MARKER", weekStart=weekEnd=day)
                    // Эти записи НЕ создают Transaction и НЕ меняют баланс —
                    // они служат только сигналом для scooterStatusOf (свободен
                    // ли скутер) и для отображения в календаре просмотра.
                    if (scenario == 5) {
                        for ((stopStart, stopEnd) in stopMarkers) {
                            try {
                                historyRepository.insert(ContractHistoryEntry(
                                    renterId = savedRenter.id,
                                    timestamp = now,
                                    type = ContractHistoryEntry.TYPE_TERMINATED,
                                    amount = 0.0,
                                    notes = "STOP_MARKER",
                                    renterName = savedRenter.name,
                                    renterPhone = savedRenter.phoneNumber,
                                    scooterName = savedRenter.scooterName,
                                    weekStart = stopStart,
                                    weekEnd = stopEnd,
                                    weeklyPrice = 0.0,
                                    passportData = savedRenter.passportData,
                                    address = savedRenter.address,
                                    pinfl = savedRenter.pinfl,
                                    vinNumber = scooter?.vinNumber ?: "",
                                    engineNumber = scooter?.engineNumber ?: "",
                                    scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                                    batteryId1 = scooter?.batteryId1 ?: "",
                                    batteryId2 = scooter?.batteryId2 ?: "",
                                    additionalInfo = scooter?.additionalInfo ?: "",
                                    isPaid = false
                                ))
                                Log.d(TAG, "addRenter: saved STOP_MARKER for renter #${savedRenter.id} at $stopStart")
                            } catch (e: Exception) {
                                Log.w(TAG, "addRenter: failed to save STOP_MARKER: ${e.message}")
                            }
                        }
                        for ((resStart, resEnd) in resumeMarkers) {
                            try {
                                historyRepository.insert(ContractHistoryEntry(
                                    renterId = savedRenter.id,
                                    timestamp = now,
                                    type = ContractHistoryEntry.TYPE_RETURNED,
                                    amount = 0.0,
                                    notes = "RESUME_MARKER",
                                    renterName = savedRenter.name,
                                    renterPhone = savedRenter.phoneNumber,
                                    scooterName = savedRenter.scooterName,
                                    weekStart = resStart,
                                    weekEnd = resEnd,
                                    weeklyPrice = 0.0,
                                    passportData = savedRenter.passportData,
                                    address = savedRenter.address,
                                    pinfl = savedRenter.pinfl,
                                    vinNumber = scooter?.vinNumber ?: "",
                                    engineNumber = scooter?.engineNumber ?: "",
                                    scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                                    batteryId1 = scooter?.batteryId1 ?: "",
                                    batteryId2 = scooter?.batteryId2 ?: "",
                                    additionalInfo = scooter?.additionalInfo ?: "",
                                    isPaid = false
                                ))
                                Log.d(TAG, "addRenter: saved RESUME_MARKER for renter #${savedRenter.id} at $resStart")
                            } catch (e: Exception) {
                                Log.w(TAG, "addRenter: failed to save RESUME_MARKER: ${e.message}")
                            }
                        }
                    }

                    // ── Гарантируем RESUME-маркер на последнем дне ────────
                    // последнего контракта для ВСЕХ сценариев (1-5).
                    // По требованию пользователя: при создании контракта
                    // (оплачено или не оплачено) на последнем дне самого
                    // последнего контракта нового арендатора должен быть
                    // день с «возобновлённым» статусом (RESUME_MARKER).
                    // ensureResumeMarkerOnLastContractDay сама проверит,
                    // не стоит ли уже маркер на эту дату — дубликатов не будет.
                    ensureResumeMarkerOnLastContractDay(
                        renterId = savedRenter.id,
                        renterName = savedRenter.name,
                        renterPhone = savedRenter.phoneNumber,
                        scooterName = savedRenter.scooterName ?: "",
                        passportData = savedRenter.passportData,
                        address = savedRenter.address,
                        pinfl = savedRenter.pinfl,
                        scooter = scooter
                    )
                } catch (e: Exception) { Log.w(TAG, "History save xato", e) }
            }

            // ── Уведомление и SMS только если есть неоплаченные контракты ──
            if (shouldNotifyOverdue) {
                val context = getApplication<Application>()
                try {
                    NotificationHelper.createChannel(context)
                    NotificationHelper.postPaymentDueNotification(context, savedRenter.id, savedRenter.name, savedRenter.phoneNumber)
                } catch (e: Exception) { Log.e(TAG, "Notification xato", e) }

                // ── SMS avto-yuborish rejimini tekshirish ─────────────────
                // Agar Settings'da "Qo'llanma" rejimi tanlangan bo'lsa (smsAutoSendEnabled=false),
                // SMS avtomatik yuborilmaydi — faqat bildirishnoma va tarix yozuvi saqlanadi.
                // Foydalanuvchi keyin "SMS" tugmasi orqali qo'lda yuborishi mumkin.
                val settingsRepo = SettingsRepository(context)
                if (settingsRepo.smsAutoSendEnabled) {
                    sendSmsWithFullRetry(context, savedRenter, expiryTime, now, initialBalance)
                } else {
                    Log.d(TAG, "Auto-SMS skipped for renter #${savedRenter.id}: manual mode is on")
                    _smsResults.tryEmit(SmsResult(
                        success = false,
                        message = "SMS avto-yuborish o'chirilgan (qo'llanma rejimi). Mijozga SMS qo'lda yuboring.",
                        errorCode = "SMS_AUTO_DISABLED",
                        exceptionClass = null,
                        exceptionMessage = "smsAutoSendEnabled=false"
                    ))
                }

                try {
                    val db = AppDatabase.getDatabase(context)
                    db.notificationHistoryDao().insert(com.example.data.NotificationHistoryEntity(
                        timestamp = now, renterId = savedRenter.id,
                        title = "To'lov muddati yetdi",
                        message = "Mijoz ${savedRenter.name} (${savedRenter.phoneNumber}) bugun to'lov qilishi kerak"
                    ))
                } catch (e: Exception) { Log.w(TAG, "Notif save xato", e) }

                val wm = WorkManager.getInstance(getApplication())
                wm.enqueueUniquePeriodicWork("PaymentCheckWork", androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    androidx.work.PeriodicWorkRequestBuilder<PaymentCheckWorker>(1, java.util.concurrent.TimeUnit.HOURS).build())
            }
            // Обновляем нативные виджеты Android после создания арендатора
            try { com.example.widget.WidgetUpdater.updateAll(getApplication()) } catch (_: Exception) {}
        }
    }

    /**
     * SMS yuborish — 3 ta usul bilan ketma-ket uriniladi.
     */
    private fun sendSmsWithFullRetry(
        context: android.content.Context,
        renter: Renter,
        expiryTime: Long,
        now: Long,
        initialBalance: Double
    ) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            _smsResults.tryEmit(SmsResult(false, "SMS yuborilmadi: ruxsat berilmagan", "SMS_PERMISSION_DENIED", "SecurityException", "SEND_SMS ruxsati berilmagan"))
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<SmsWorker>().build())
            return
        }

        val rawPhone = renter.phoneNumber
        val phone = SimHelper.normalizePhoneNumber(rawPhone)
        if (phone.isBlank()) {
            _smsResults.tryEmit(SmsResult(false, "SMS yuborilmadi: raqam bo'sh", "SMS_EMPTY_PHONE", "IllegalArgumentException", "Telefon raqami kiritilmagan"))
            return
        }

        val settingsRepo = SettingsRepository(context)
        val message = formatSmsMessage(settingsRepo, renter, expiryTime, now, -initialBalance)

        Log.d(TAG, "SMS yuborish boshlandi: to=$phone (${rawPhone}→$phone), ${message.length} chars")

        val attempts = SimHelper.getAllSmsManagers(context)
        val validAttempts = attempts.filter { it.smsManager != null }

        if (validAttempts.isEmpty()) {
            val diag = SimHelper.getDiagnostics(context, rawPhone)
            _smsResults.tryEmit(SmsResult(false, "SMS yuborilmadi: SmsManager topilmadi",
                "SMS_MANAGER_NULL", "IllegalStateException", "Hech qanday SmsManager topilmadi.\n${diag.fullReport}"))
            return
        }

        trySmsAttempts(context, renter, phone, message, validAttempts, 0)
    }

    /**
     * Формирует текст SMS с подстановкой всех тегов:
     * {name}, {days}, {debt}, {payme}, {call}.
     *
     * Имя приводится к нижнему регистру первой буквы — так, как в примере пользователя
     * (озодбек).
     */
    fun formatSmsMessage(
        settingsRepo: SettingsRepository,
        renter: Renter,
        expiryTime: Long,
        now: Long,
        debtAmount: Double
    ): String {
        val days = if (now > expiryTime) ((now - expiryTime) / (1000L * 60 * 60 * 24)).toInt() else 0
        // Надёжный источник долга — balance. Если переданный debtAmount = 0,
        // но balance < 0, используем -balance (debtAmount мог рассинхронизироваться).
        val effectiveDebt = if (debtAmount > 0) debtAmount else maxOf(0.0, -renter.balance)
        val debt = effectiveDebt.toBigDecimal().setScale(0, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()
        val nameLower = renter.name.trim().lowercase()
        return settingsRepo.smsTemplate
            .replace("{name}", nameLower)
            .replace("{days}", maxOf(1, days).toString())
            .replace("{debt}", debt)
            .replace("{payme}", settingsRepo.paymeLink)
            .replace("{call}", settingsRepo.callCenter)
    }

    private fun trySmsAttempts(
        context: android.content.Context,
        renter: Renter,
        phone: String,
        message: String,
        attempts: List<SimHelper.SmsSendAttempt>,
        currentIndex: Int
    ) {
        if (currentIndex >= attempts.size) {
            val lastManager = attempts.last().smsManager ?: return
            Log.w(TAG, "Barcha PendingIntentli urinishlar xato — fire-and-forget bilan urinilmoqda")
            val sent = SimHelper.sendSmsFireAndForget(lastManager, phone, message)
            if (sent) {
                viewModelScope.launch(Dispatchers.IO) {
                    repository.update(renter.copy(isOverdueSmsSent = true))
                }
                _smsResults.tryEmit(SmsResult(true, "SMS yuborildi (fire-and-forget): $phone"))
            } else {
                val diag = SimHelper.getDiagnostics(context, phone)
                _smsResults.tryEmit(SmsResult(false,
                    "SMS yuborilmadi: barcha usullar xato (${attempts.size} ta urinish)",
                    "SMS_ALL_METHODS_FAILED", "GENERIC_FAILURE",
                    buildString {
                        appendLine("Ilova ${attempts.size} xil usul bilan urinib ko'rdi — hammasida GENERIC_FAILURE.")
                        appendLine()
                        appendLine("Eng ehtimol sabablar:")
                        appendLine("1) SIM balans YETARLI EMAS — ${diag.networkOperatorName ?: "operator"} balansini tekshiring!")
                        appendLine("2) Operator SMS ni rad etmoqda — operatorga qo'ng'iroq qiling")
                        appendLine("3) Ilova DEFAULT SMS APP emas — Android sozlamalaridan 'Default SMS app' qiling")
                        appendLine()
                        appendLine(diag.fullReport)
                    }))
            }
            return
        }

        val attempt = attempts[currentIndex]
        val smsManager = attempt.smsManager ?: return trySmsAttempts(context, renter, phone, message, attempts, currentIndex + 1)

        val isLastAttempt = currentIndex == attempts.size - 1

        if (isLastAttempt) {
            val sent = SimHelper.sendSmsFireAndForget(smsManager, phone, message)
            if (sent) {
                viewModelScope.launch(Dispatchers.IO) {
                    repository.update(renter.copy(isOverdueSmsSent = true))
                }
                _smsResults.tryEmit(SmsResult(true, "SMS yuborildi (${attempt.method}): $phone"))
            } else {
                val diag = SimHelper.getDiagnostics(context, phone)
                _smsResults.tryEmit(SmsResult(false,
                    "SMS yuborilmadi: barcha usullar xato",
                    "SMS_ALL_METHODS_FAILED", "GENERIC_FAILURE",
                    buildString {
                        appendLine("${attempts.size} ta usul bilan urinildi — hammasi GENERIC_FAILURE.")
                        appendLine()
                        appendLine("Sabablar:")
                        appendLine("1) SIM BALANS yetarli emas — ${diag.networkOperatorName ?: "operator"} balansini tekshiring!")
                        appendLine("2) Operator SMS ni rad etmoqda")
                        appendLine("3) Ilova Default SMS app emas")
                        appendLine()
                        appendLine(diag.fullReport)
                    }))
            }
            return
        }

        val actionId = "com.example.SMS_SENT_${++smsSendCounter}_A${attempt.attempt}"
        val sentIntent = PendingIntent.getBroadcast(
            context, smsSendCounter, Intent(actionId),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                ctx.unregisterReceiver(this)
                val code = resultCode
                Log.d(TAG, "SMS result [${attempt.method}]: code=$code for $phone")

                if (code == android.app.Activity.RESULT_OK) {
                    viewModelScope.launch(Dispatchers.IO) {
                        repository.update(renter.copy(isOverdueSmsSent = true))
                    }
                    _smsResults.tryEmit(SmsResult(true, "SMS muvaffaqiyatli yuborildi (${attempt.method}): $phone"))
                } else if (code == SmsManager.RESULT_ERROR_GENERIC_FAILURE) {
                    Log.w(TAG, "GENERIC_FAILURE [${attempt.method}] → keyingi usulga o'tilmoqda")
                    _smsResults.tryEmit(SmsResult(true, "SMS usul ${attempt.attempt} xato, keyingi usul sinab ko'rilmoca..."))
                    trySmsAttempts(context, renter, phone, message, attempts, currentIndex + 1)
                } else {
                    val (errorName, _) = smsErrorCodeToText(code)
                    Log.w(TAG, "$errorName [${attempt.method}] → keyingi usulga o'tilmoqda")
                    trySmsAttempts(context, renter, phone, message, attempts, currentIndex + 1)
                }
            }
        }

        try {
            context.registerReceiver(receiver, IntentFilter(actionId))
        } catch (e: Exception) {
            Log.e(TAG, "Receiver ro'yxatdan o'tkazilmadi", e)
        }

        try {
            SimHelper.sendSmsAuto(smsManager, phone, message, sentIntent, null)
            Log.d(TAG, "SMS ${attempt.attempt}-urinish [${attempt.method}]: → $phone (${message.length} chars)")
        } catch (e: Exception) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            Log.w(TAG, "SMS ${attempt.attempt}-urinishda exception: ${e.message}")
            trySmsAttempts(context, renter, phone, message, attempts, currentIndex + 1)
        }
    }

    private fun smsErrorCodeToText(code: Int): Pair<String, String> {
        return when (code) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "GENERIC_FAILURE" to "Umumiy xato (kod 1)"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "RADIO_OFF" to "Radio o'chiq (kod 2)"
            SmsManager.RESULT_ERROR_NULL_PDU -> "NULL_PDU" to "PDU xatosi (kod 3)"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "NO_SERVICE" to "Tarmoq yo'q (kod 4)"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "LIMIT_EXCEEDED" to "SMS limiti (kod 5)"
            else -> "UNKNOWN_$code" to "Noma'lum ($code)"
        }
    }

    fun updateRenter(renter: Renter) {
        viewModelScope.launch { repository.update(renter) }
    }

    /**
     * Обновление арендатора с автоматической корректировкой контрактов.
     *
     * Если арендодатель изменил дату начала аренды **назад** (старая дата новее новой),
     * то на каждый дополнительный период в 7 дней:
     *   • создаётся одна запись AUTO_RENEW в истории контрактов
     *   • баланс уменьшается на weeklyPrice
     *
     * Если дата сдвинута **вперёд** — наоборот, AUTO_RENEW-записи за эти недели
     * удаляются (последние N), баланс восстанавливается.
     *
     * Если изменилась длительность (без смены даты) — аналогично для новых недель.
     */
    fun updateRenterWithContracts(
        existing: Renter,
        newName: String,
        newPhone: String,
        newDebt: Double,
        newDuration: Int,
        newStartTimestamp: Long,
        newScooterId: Int?,
        newScooterName: String?,
        newIsActive: Boolean,
        weeklyPrice: Double,
        // PDF-реквизиты арендатора
        passportData: String = existing.passportData,
        address: String = existing.address,
        pinfl: String = existing.pinfl,
        // ── Режим авто-продления контракта ────────────────────────────────
        // MANUAL (по умолчанию) — система НЕ создаёт контракты автоматически.
        // AUTO — система создаёт AUTO_RENEW при окончании последнего контракта.
        autoRenewMode: String = existing.autoRenewMode,
        /**
         * Полный список групп контрактов из формы (с existingContractId).
         *
         * Если список не пуст — функция переключается в НОВЫЙ режим
         * реконсиалирования: старая логика по датам/длительности пропускается,
         * и вместо неё выполняется:
         *   1. Загрузка всех текущих контрактов арендатора из БД.
         *   2. Удаление контрактов, которых нет в новом списке (каскадно —
         *      с реверсом баланса, удалением Transaction и CardTransaction).
         *   3. Добавление новых контрактов (existingId == null) как AUTO_RENEW
         *      с балансом и Transaction.
         *   4. Для контрактов с изменившимся isPaid или датами — удалить старый
         *      и создать новый с обновлёнными полями.
         *   5. Обновление renter.rentStartDateTimestamp и rentDurationDays
         *      на основе самого раннего startMs и общего диапазона из groups.
         *
         * Если список пуст — выполняется старая логика по date-shift/duration-shift
         * (для обратной совместимости со старыми вызовами без групп).
         */
        contractGroupsWithIds: List<com.example.RenterFormContractGroup> = emptyList()
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // ── Новый режим: реконсиалирование контрактов из формы ────────
            // Применяется, когда пользователь редактирует арендатора через
            // RenterFormDialog с календарём контрактов.
            //
            // ВАЖНО: reconcile вызывается в двух случаях:
            //   1. contractGroupsWithIds не пуст — обычное редактирование
            //      (пользователь добавил/изменил/удалил часть контрактов).
            //   2. contractGroupsWithIds пуст, НО у арендатора в БД есть
            //      контракты — это означает, что пользователь удалил ВСЕ
            //      контракты через кнопку «х» в списке под календарём.
            //      Без этой ветки сработала бы старая логика по date-shift,
            //      которая НЕ удаляет контракты каскадно, и они остались
            //      бы в БД как «осиротевшие».
            if (contractGroupsWithIds.isNotEmpty()) {
                reconcileContractsFromGroups(
                    existing = existing,
                    newName = newName,
                    newPhone = newPhone,
                    newScooterId = newScooterId,
                    newScooterName = newScooterName,
                    newIsActive = newIsActive,
                    weeklyPrice = weeklyPrice,
                    passportData = passportData,
                    address = address,
                    pinfl = pinfl,
                    autoRenewMode = autoRenewMode,
                    groups = contractGroupsWithIds
                )
                return@launch
            }

            // ── Проверка: есть ли у арендатора контракты в БД? ────────────
            // Если да, а contractGroupsWithIds пуст → пользователь удалил все
            // контракты. Вызываем reconcile с пустым списком, чтобы каскадно
            // удалить все контракты (с реверсом баланса, Transaction, CardTx).
            val existingContractsForReconcile = historyRepository.contractsForRenterOnce(existing.id)
            if (existingContractsForReconcile.isNotEmpty()) {
                Log.d(TAG, "updateRenterWithContracts: contractGroupsWithIds is empty but " +
                        "renter #${existing.id} has ${existingContractsForReconcile.size} " +
                        "contract(s) in DB — calling reconcile with empty groups to cascade-delete")
                reconcileContractsFromGroups(
                    existing = existing,
                    newName = newName,
                    newPhone = newPhone,
                    newScooterId = newScooterId,
                    newScooterName = newScooterName,
                    newIsActive = newIsActive,
                    weeklyPrice = weeklyPrice,
                    passportData = passportData,
                    address = address,
                    pinfl = pinfl,
                    autoRenewMode = autoRenewMode,
                    groups = emptyList()
                )
                return@launch
            }

            val effectivePrice = if (weeklyPrice > 0) weeklyPrice else SettingsRepository.DEFAULT_WEEKLY_PRICE
            val settingsRepo = SettingsRepository(getApplication())
            val realWeeklyPrice = if (settingsRepo.weeklyPrice > 0) settingsRepo.weeklyPrice else effectivePrice

            val oldStart = existing.rentStartDateTimestamp
            val newStart = newStartTimestamp
            val oldDuration = existing.rentDurationDays
            val newDuration = newDuration

            val oldEnd = oldStart + oldDuration * 24L * 60 * 60 * 1000
            val newEnd = newStart + newDuration * 24L * 60 * 60 * 1000

            val now = System.currentTimeMillis()
            var balanceAdjust = 0.0

            // ── Подтягиваем реквизиты скутера из БД (для новых AUTO_RENEW) ─
            val scooter: Scooter? = newScooterId?.let { fetchScooterById(it) }

            // ── Сдвиг даты назад → дополнительные недели ───────────────────
            // Используем FLOOR, а не CEIL — чтобы не создавать перекрытий с
            // уже существующим CREATED. Если deltaMillis точно кратно 7 дням,
            // FLOOR и CEIL дают одинаковый результат. Если есть «хвост»
            // (< 7 дней), FLOOR его отбрасывает, и пользователь должен сам
            // решать, нужна ли дополнительная неделя (через ручное создание).
            if (newStart < oldStart) {
                val deltaMillis = oldStart - newStart
                val extraWeeks = (deltaMillis / (7L * 24 * 60 * 60 * 1000)).toInt()
                if (extraWeeks > 0) {
                    for (i in 1..extraWeeks) {
                        val weekStart = newStart + (i - 1) * 7L * 24 * 60 * 60 * 1000
                        val weekEnd = weekStart + 7L * 24 * 60 * 60 * 1000
                        historyRepository.insert(ContractHistoryEntry(
                            renterId = existing.id,
                            timestamp = now,
                            type = ContractHistoryEntry.TYPE_AUTO_RENEW,
                            amount = realWeeklyPrice,
                            notes = "$i-hafta (muddat orqaga surildi)",
                            renterName = newName,
                            renterPhone = newPhone,
                            scooterName = newScooterName,
                            weekStart = weekStart,
                            weekEnd = weekEnd,
                            weeklyPrice = realWeeklyPrice,
                            passportData = passportData,
                            address = address,
                            pinfl = pinfl,
                            vinNumber = scooter?.vinNumber ?: "",
                            engineNumber = scooter?.engineNumber ?: "",
                            scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                            batteryId1 = scooter?.batteryId1 ?: "",
                            batteryId2 = scooter?.batteryId2 ?: "",
                            additionalInfo = scooter?.additionalInfo ?: ""
                        ))
                        balanceAdjust -= realWeeklyPrice
                    }
                }
            }

            // ── Сдвиг даты вперёд → возврат баланса за «лишние» недели ──────
            // Удаляем AUTO_RENEW по weekStart DESC (не по timestamp!).
            // Это гарантирует, что удаляются именно самые поздние по неделе
            // контракты, а не те, что были созданы последними по времени
            // (что может быть результатом ручных операций).
            // CREATED никогда не удаляется — это первичная запись.
            if (newStart > oldStart) {
                val deltaMillis = newStart - oldStart
                val removedWeeks = (deltaMillis / (7L * 24 * 60 * 60 * 1000)).toInt()
                if (removedWeeks > 0) {
                    val history = historyRepository.getForRenterOnce(existing.id)
                    val autoRenew = history.filter { it.type == ContractHistoryEntry.TYPE_AUTO_RENEW }
                        .sortedByDescending { it.weekStart ?: 0L }
                        .take(removedWeeks)
                    autoRenew.forEach { entry ->
                        historyRepository.deleteById(entry.id)
                        balanceAdjust += realWeeklyPrice
                    }
                }
            }

            // ── Изменение длительности ─────────────────────────────────────
            if (oldStart == newStart && newDuration > oldDuration) {
                val deltaDays = newDuration - oldDuration
                val extraWeeks = ((deltaDays + 6) / 7).coerceAtLeast(1)
                for (i in 1..extraWeeks) {
                    val weekStart = oldEnd + (i - 1) * 7L * 24 * 60 * 60 * 1000
                    val weekEnd = weekStart + 7L * 24 * 60 * 60 * 1000
                    historyRepository.insert(ContractHistoryEntry(
                        renterId = existing.id,
                        timestamp = now,
                        type = ContractHistoryEntry.TYPE_AUTO_RENEW,
                        amount = realWeeklyPrice,
                        notes = "$i-hafta (muddat uzaytirildi)",
                        renterName = newName,
                        renterPhone = newPhone,
                        scooterName = newScooterName,
                        weekStart = weekStart,
                        weekEnd = weekEnd,
                        weeklyPrice = realWeeklyPrice,
                        passportData = passportData,
                        address = address,
                        pinfl = pinfl,
                        vinNumber = scooter?.vinNumber ?: "",
                        engineNumber = scooter?.engineNumber ?: "",
                        scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                        batteryId1 = scooter?.batteryId1 ?: "",
                        batteryId2 = scooter?.batteryId2 ?: "",
                        additionalInfo = scooter?.additionalInfo ?: ""
                    ))
                    balanceAdjust -= realWeeklyPrice
                }
            }

            val newBalance = existing.balance + balanceAdjust
            // debtAmount должен быть СТРОГО max(0, -newBalance) — это
            // автоматически синхронизирует поле с реальным балансом.
            // Раньше здесь было max(newDebt, -newBalance.coerceAtMost(0.0)),
            // что приводило к парадоксу: пользователь мог ввести долг 300,
            // а реальный по контрактам — 200, и сохранялось 300 (лишний долг).
            val newDebtAmount = maxOf(0.0, -newBalance)
            val updated = existing.copy(
                name = newName,
                phoneNumber = newPhone,
                debtAmount = newDebtAmount,
                rentDurationDays = newDuration,
                rentStartDateTimestamp = newStart,
                scooterId = newScooterId,
                scooterName = newScooterName,
                isReturned = !newIsActive,
                balance = newBalance,
                isOverdueSmsSent = if (newIsActive && newStart != oldStart) false else existing.isOverdueSmsSent,
                passportData = passportData,
                address = address,
                pinfl = pinfl,
                autoRenewMode = autoRenewMode
            )
            repository.update(updated)
        }
    }

    /** Подгружает скутер из БД по его id (для денормализации в ContractHistoryEntry). */
    private suspend fun fetchScooterById(id: Int): Scooter? {
        return AppDatabase.getDatabase(getApplication()).scooterDao().getScooterById(id)
    }

    /**
     * Реконсиалирование контрактов арендатора на основе групп из формы.
     *
     * Вызывается из [updateRenterWithContracts] когда пользователь редактирует
     * арендатора через RenterFormDialog с календарём контрактов. Алгоритм:
     *
     * 1. Загружаем все текущие контракты (CREATED + AUTO_RENEW) из БД.
     * 2. Сравниваем с новым списком групп:
     *    - Контракты, которых НЕТ в новом списке → каскадно удаляем (с реверсом
     *      баланса, Transaction, CardTransaction).
     *    - Контракты с изменившимся isPaid или датами → удаляем старый и
     *      создаём новый с обновлёнными полями (баланс и Transaction создаются
     *      заново для нового контракта).
     *    - Контракты без изменений → пропускаем.
     *    - Новые группы (existingId == null) → вставляем как AUTO_RENEW.
     * 3. Обновляем renter.rentStartDateTimestamp и rentDurationDays на основе
     *    самого раннего startMs и общего диапазона из groups.
     * 4. Обновляем остальные поля арендатора (имя, телефон, скутер и т.д.).
     *
     * Все операции выполняются последовательно в одной coroutine на Dispatchers.IO.
     * Если что-то упадёт посередине, останутся «осиротевшие» записи, но UI
     * фильтрует их по contractId/renterId, поэтому критичных ошибок не будет.
     */
    private suspend fun reconcileContractsFromGroups(
        existing: Renter,
        newName: String,
        newPhone: String,
        newScooterId: Int?,
        newScooterName: String?,
        newIsActive: Boolean,
        weeklyPrice: Double,
        passportData: String,
        address: String,
        pinfl: String,
        autoRenewMode: String,
        groups: List<com.example.RenterFormContractGroup>
    ) {
        val settingsRepo = SettingsRepository(getApplication())
        // ── Дневная ставка — ЕДИНСТВЕННЫЙ источник истины для суммы контракта ──
        // Ранее здесь использовался realWeeklyPrice (= daily × 7), что приводило
        // к багу: контракт на 9 дней получал сумму 7 дней (420000 вместо 540000).
        // Теперь сумма вычисляется ПО ФАКТИЧЕСКОЙ ДЛИНЕ контракта в днях:
        //   amount = dailyPrice × ceil((endMs - startMs) / dayMs)
        val dailyPrice = if (settingsRepo.dailyPrice > 0) settingsRepo.dailyPrice
                         else SettingsRepository.DEFAULT_DAILY_PRICE
        val dayMs = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()

        // ── 1. Загружаем все текущие контракты арендатора ──────────────
        // ВАЖНО: используем getForRenterOnce (а НЕ contractsForRenterOnce),
        // потому что getForRenterOnce возвращает ВСЕ записи — включая
        // Stop/Resume маркеры (TYPE_TERMINATED с notes="STOP_MARKER" и
        // TYPE_RETURNED с notes="RESUME_MARKER"). contractsForRenterOnce
        // фильтрует только CREATED/AUTO_RENEW — и тогда currentMarkers (ниже)
        // всегда пуст, старые маркеры не удаляются и накапливаются в БД.
        val currentContracts = historyRepository.getForRenterOnce(existing.id)

        // ── 1а. Разделяем группы на обычные контракты и маркеры Stop/Resume ──
        // Маркеры обрабатываются ОТДЕЛЬНО от обычных контрактов:
        //   • Stop-маркер → TYPE_TERMINATED, isPaid=false, notes="STOP_MARKER"
        //   • Resume-маркер → TYPE_RETURNED, isPaid=false, notes="RESUME_MARKER"
        //   • Для Resume-маркеров также генерируются неоплаченные weekly-контракты
        //     (вперёд до ближайшего Stop и назад до сегодня при отсутствии Stop).
        val stopMarkers = groups.filter { it.isStopMarker }
        val resumeMarkers = groups.filter { it.isResumeMarker }
        val regularGroups = groups.filter { !it.isStopMarker && !it.isResumeMarker }

        // ── 1б. Загружаем текущие Stop/Resume маркеры из БД ────────────
        // Это нужно, чтобы корректно удалить старые маркеры, которых больше
        // нет в новом списке групп (пользователь их стёр в календаре).
        // ВАЖНО: фильтруем ТОЛЬКО по notes="STOP_MARKER" / "RESUME_MARKER" —
        // обычные TYPE_TERMINATED / TYPE_RETURNED записи (без маркерных notes)
        // НЕ должны удаляться здесь, они обрабатываются в regularGroups.
        val currentMarkers = currentContracts.filter {
            (it.type == ContractHistoryEntry.TYPE_TERMINATED && it.notes == "STOP_MARKER") ||
            (it.type == ContractHistoryEntry.TYPE_RETURNED && it.notes == "RESUME_MARKER")
        }

        // ── 2. Разделяем на удаление / обновление / добавление ─────────
        val keepIds = regularGroups.mapNotNull { it.existingId }.toSet()
        val toDelete = currentContracts.filter { it.id !in keepIds && it.id !in currentMarkers.map { m -> m.id } }

        // ── 2а. Удаляем только те маркеры, которых БОЛЬШЕ НЕТ в форме ──
        // Раньше здесь удалялись ВСЕ текущие маркеры и пересоздавались из
        // формы. Это приводило к багу: если форма по какой-то причине не
        // содержала RESUME-маркер (например, при редактировании арендатора
        // с STOP+RESUME последовательностью), RESUME безвозвратно терялся.
        //
        // Новый подход: удаляем только маркеры, которых НЕТ в новом списке
        // групп (сопоставление по типу + дате). Маркеры, которые есть в
        // форме, остаются в БД как есть (не нужно удалять и пересоздавать).
        //
        // Это также предотвращает потерю RESUME-маркера после STOP-маркера:
        // даже если форма не пересоздаёт RESUME, он остаётся в БД.
        val newMarkerKeys = (stopMarkers.map { "STOP" to it.startMs } +
                             resumeMarkers.map { "RESUME" to it.startMs }).toSet()
        val markersToDelete = currentMarkers.filter { marker ->
            val key = when {
                marker.type == ContractHistoryEntry.TYPE_TERMINATED &&
                marker.notes == "STOP_MARKER" -> "STOP" to (marker.weekStart ?: 0L)
                marker.type == ContractHistoryEntry.TYPE_RETURNED &&
                marker.notes == "RESUME_MARKER" -> "RESUME" to (marker.weekStart ?: 0L)
                else -> return@filter false
            }
            key !in newMarkerKeys
        }
        for (marker in markersToDelete) {
            deleteContractWithCascadeInternal(marker)
            Log.d(TAG, "reconcile: deleted removed marker #${marker.id} (type=${marker.type})")
        }

        // Для обновления: existingId есть в regularGroups, но isPaid или даты изменились.
        // Логика: удалить старый + создать новый (баланс и Transaction пересоздаются).
        data class UpdatePair(
            val oldContract: ContractHistoryEntry,
            val newGroup: com.example.RenterFormContractGroup
        )
        val toUpdate = mutableListOf<UpdatePair>()
        for (g in regularGroups) {
            val exId = g.existingId ?: continue
            val existingContract = currentContracts.find { it.id == exId } ?: continue
            val statusChanged = existingContract.isPaid != g.isPaid
            val datesChanged = existingContract.weekStart != g.startMs || existingContract.weekEnd != g.endMs
            if (statusChanged || datesChanged) {
                toUpdate.add(UpdatePair(existingContract, g))
            }
        }

        // Добавляем: existingId == null (новые группы, созданные в календаре)
        val toAdd = regularGroups.filter { it.existingId == null }

        // ── 3. Удаляем контракты с каскадом ────────────────────────────
        // Сначала удаляем, потом добавляем — чтобы не было временного дублирования
        // периодов. Каскад: реверс баланса арендатора, удаление Transaction,
        // реверс CardTransaction на главной карте.
        for (contract in toDelete) {
            deleteContractWithCascadeInternal(contract)
            Log.d(TAG, "reconcile: deleted contract #${contract.id} (renter=${existing.id})")
        }
        for (pair in toUpdate) {
            deleteContractWithCascadeInternal(pair.oldContract)
            Log.d(TAG, "reconcile: deleted (for update) contract #${pair.oldContract.id}")
        }

        // ── 4. Перечитываем арендатора — баланс мог измениться после удалений ─
        var renter = repository.getById(existing.id) ?: existing

        // ── 5. Добавляем новые контракты (включая обновлённые) ──────────
        // Все они создаются как TYPE_AUTO_RENEW с актуальными isPaid и датами.
        // Если isPaid=true → создаём Transaction и зачисляем на главную карту.
        val scooter: Scooter? = newScooterId?.let { fetchScooterById(it) }
        val allToAdd: List<com.example.RenterFormContractGroup> = toAdd + toUpdate.map { it.newGroup }

        for (g in allToAdd) {
            // ── Вычисляем сумму по фактической длине контракта в днях ──────
            // Модель «отель/ночь»: 7→14 = 7 дней (день выезда не оплачивается).
            // Используем ЦЕЛОЧИСЛЕННОЕ деление (floor), а не ceil, чтобы:
            //   • Точное кратное 7 дней → 7 дней (420000 при 60000/день).
            //   • Хвост < 1 дня (например от старых контрактов с endMs =
            //     23:59:59.999) отбрасывался — не давал лишний 8-й день.
            val daysCount = if (g.endMs > g.startMs) {
                ((g.endMs - g.startMs) / dayMs).toInt().coerceAtLeast(1)
            } else 1
            val contractAmount = dailyPrice * daysCount

            val entry = ContractHistoryEntry(
                renterId = existing.id,
                timestamp = now,
                type = ContractHistoryEntry.TYPE_AUTO_RENEW,
                amount = contractAmount,
                notes = "Kalendar orqali tahrirlandi${if (g.isPaid) " (to'langan)" else " (to'lanmagan)"} — $daysCount kun",
                renterName = newName,
                renterPhone = newPhone,
                scooterName = newScooterName ?: scooter?.name ?: "",
                weekStart = g.startMs,
                weekEnd = g.endMs,
                weeklyPrice = contractAmount,
                passportData = passportData,
                address = address,
                pinfl = pinfl,
                vinNumber = scooter?.vinNumber ?: "",
                engineNumber = scooter?.engineNumber ?: "",
                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                batteryId1 = scooter?.batteryId1 ?: "",
                batteryId2 = scooter?.batteryId2 ?: "",
                additionalInfo = scooter?.additionalInfo ?: "",
                isPaid = g.isPaid
            )
            val newContractId = historyRepository.insert(entry).toInt()

            // Если оплачен — создаём Transaction и зачисляем на карту
            if (g.isPaid && newContractId > 0) {
                try {
                    transactionRepository.insert(
                        Transaction(
                            contractId = newContractId,
                            renterId = existing.id,
                            scooterId = newScooterId,
                            timestamp = now,
                            type = Transaction.TYPE_PAYMENT,
                            amount = contractAmount,
                            notes = "Tahrir orqali to'lov ($daysCount kun)",
                            renterName = newName,
                            renterPhone = newPhone,
                            scooterName = newScooterName ?: "",
                            contractLabel = "#$newContractId"
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "reconcile: Transaction insert failed: ${e.message}")
                }
                try {
                    virtualCardRepository.depositContractIncome(
                        amount = contractAmount,
                        note = "To'lov: $newName (tahrir) — #$newContractId ($daysCount kun)",
                        contractId = newContractId
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "reconcile: depositContractIncome failed: ${e.message}")
                }
            }

            // Обновляем баланс арендатора
            val balanceDelta = if (g.isPaid) contractAmount else -contractAmount
            val newBalance = renter.balance + balanceDelta
            renter = renter.copy(
                balance = newBalance,
                debtAmount = maxOf(0.0, -newBalance),
                lastPaymentTimestamp = if (g.isPaid) now else renter.lastPaymentTimestamp
            )
            Log.d(TAG, "reconcile: added/updated contract #$newContractId (isPaid=${g.isPaid})")
        }

        // ── 5а. Сохраняем Stop/Resume маркеры + авто-контракты от Resume ──
        // Аналогично addRenter scenario 5:
        //   • Stop → TYPE_TERMINATED, notes="STOP_MARKER", однодневный.
        //   • Resume → TYPE_RETURNED, notes="RESUME_MARKER", однодневный.
        //   • Для каждого Resume генерируем неоплаченные weekly-контракты
        //     вперёд до ближайшего Stop (или +7 дней) и назад до сегодня,
        //     если в [today, R-1] нет Stop-маркера.
        //
        // ВАЖНО: маркеры, которые уже есть в БД (не были удалены на шаге 2а),
        // НЕ пересоздаём — чтобы не плодить дубликаты. Проверяем по ключу
        // (тип + weekStart). Это предотвращает потерю RESUME-маркера после
        // STOP-маркера при редактировании формы.
        val weekMs = 7L * dayMs
        val existingStopKeys = currentMarkers
            .filter { it.type == ContractHistoryEntry.TYPE_TERMINATED }
            .map { it.weekStart ?: 0L }
            .toSet()
        val existingResumeKeys = currentMarkers
            .filter { it.type == ContractHistoryEntry.TYPE_RETURNED }
            .map { it.weekStart ?: 0L }
            .toSet()
        for (sm in stopMarkers) {
            // Пропускаем, если маркер с этой датой уже есть в БД.
            if (sm.startMs in existingStopKeys) {
                Log.d(TAG, "reconcile: STOP_MARKER at ${sm.startMs} already in DB — skip re-insert")
                continue
            }
            try {
                historyRepository.insert(ContractHistoryEntry(
                    renterId = existing.id,
                    timestamp = now,
                    type = ContractHistoryEntry.TYPE_TERMINATED,
                    amount = 0.0,
                    notes = "STOP_MARKER",
                    renterName = newName,
                    renterPhone = newPhone,
                    scooterName = newScooterName ?: scooter?.name ?: "",
                    weekStart = sm.startMs,
                    weekEnd = sm.endMs,
                    weeklyPrice = 0.0,
                    passportData = passportData,
                    address = address,
                    pinfl = pinfl,
                    vinNumber = scooter?.vinNumber ?: "",
                    engineNumber = scooter?.engineNumber ?: "",
                    scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                    batteryId1 = scooter?.batteryId1 ?: "",
                    batteryId2 = scooter?.batteryId2 ?: "",
                    additionalInfo = scooter?.additionalInfo ?: "",
                    isPaid = false
                ))
                Log.d(TAG, "reconcile: saved STOP_MARKER for renter #${existing.id} at ${sm.startMs}")
            } catch (e: Exception) {
                Log.w(TAG, "reconcile: failed to save STOP_MARKER: ${e.message}")
            }
        }

        // Авто-контракты от Resume-маркеров (вперёд до Stop и назад до сегодня).
        // Используем тот же алгоритм, что и в addRenter (scenario 5).
        val stopDays = stopMarkers.map { it.startMs }.sorted()
        for (rm in resumeMarkers.sortedBy { it.startMs }) {
            val resumeStart = rm.startMs
            // ── Защита от дубликатов ──────────────────────────────────
            // Календарь в RenterFormDialog АВТОСОЗДАЁТ недельные неоплаченные
            // контракты сразу при тапе на Resume-день. Эти контракты
            // попадают в regularGroups как обычные группы и сохраняются в
            // БД на шаге 5 выше. Если мы здесь снова сгенерируем контракты
            // от того же Resume-маркера — получим дубликаты. Пропускаем
            // автогенерацию, но продолжаем сохранять сам Resume-маркер.
            val alreadyCovered = regularGroups.any { g ->
                resumeStart >= g.startMs && resumeStart <= g.endMs
            }
            if (!alreadyCovered) {
                // Forward — модель «отель/ночь»: endMs = start + N*dayMs (БЕЗ -1).
                // Stop-день = check-out (не оплачивается).
                val nextStop = stopDays.firstOrNull { it > resumeStart }
                val forwardEnd = when {
                    nextStop != null && nextStop <= resumeStart + weekMs -> nextStop
                    else -> resumeStart + weekMs
                }
                if (forwardEnd > resumeStart) {
                    // Модель «отель/ночь»: целочисленное деление (floor).
                    val daysCount = ((forwardEnd - resumeStart) / dayMs).toInt().coerceAtLeast(1)
                    val amount = dailyPrice * daysCount
                    historyRepository.insert(ContractHistoryEntry(
                        renterId = existing.id,
                        timestamp = now,
                        type = ContractHistoryEntry.TYPE_AUTO_RENEW,
                        amount = amount,
                        notes = "Kalendar orqali yaratildi — Resume dan avtomatik (oldinga) — $daysCount kun",
                        renterName = newName,
                        renterPhone = newPhone,
                        scooterName = newScooterName ?: scooter?.name ?: "",
                        weekStart = resumeStart,
                        weekEnd = forwardEnd,
                        weeklyPrice = amount,
                        passportData = passportData,
                        address = address,
                        pinfl = pinfl,
                        vinNumber = scooter?.vinNumber ?: "",
                        engineNumber = scooter?.engineNumber ?: "",
                        scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                        batteryId1 = scooter?.batteryId1 ?: "",
                        batteryId2 = scooter?.batteryId2 ?: "",
                        additionalInfo = scooter?.additionalInfo ?: "",
                        isPaid = false
                    ))
                    renter = renter.copy(
                        balance = renter.balance - amount,
                        debtAmount = maxOf(0.0, -(renter.balance - amount))
                    )
                }
                // Backward (только если нет Stop в [today, R-1])
                // Модель «отель/ночь»: каждый backward-контракт = 7 дней,
                // end = start + 7d. Контракты делят конечные точки:
                //   [R-7d, R], [R-14d, R-7d], [R-21d, R-14d], ...
                val todayStart = run {
                    val cal = java.util.Calendar.getInstance()
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                    cal.set(java.util.Calendar.MINUTE, 0)
                    cal.set(java.util.Calendar.SECOND, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    cal.timeInMillis
                }
                if (resumeStart > todayStart) {
                    val stopInGap = stopDays.any { it in (todayStart until resumeStart) }
                    if (!stopInGap) {
                        var end = resumeStart
                        var guard = 0
                        while (end > todayStart && guard < 60) {
                            val ws = end - 7 * dayMs
                            val realWs = maxOf(ws, todayStart)
                            // Модель «отель/ночь»: целочисленное деление (floor).
                            val daysCount = ((end - realWs) / dayMs).toInt().coerceAtLeast(1)
                            val amount = dailyPrice * daysCount
                            historyRepository.insert(ContractHistoryEntry(
                                renterId = existing.id,
                                timestamp = now,
                                type = ContractHistoryEntry.TYPE_AUTO_RENEW,
                                amount = amount,
                                notes = "Kalendar orqali yaratildi — Resume dan avtomatik (orqaga) — $daysCount kun",
                                renterName = newName,
                                renterPhone = newPhone,
                                scooterName = newScooterName ?: scooter?.name ?: "",
                                weekStart = realWs,
                                weekEnd = end,
                                weeklyPrice = amount,
                                passportData = passportData,
                                address = address,
                                pinfl = pinfl,
                                vinNumber = scooter?.vinNumber ?: "",
                                engineNumber = scooter?.engineNumber ?: "",
                                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                                batteryId1 = scooter?.batteryId1 ?: "",
                                batteryId2 = scooter?.batteryId2 ?: "",
                                additionalInfo = scooter?.additionalInfo ?: "",
                                isPaid = false
                            ))
                            renter = renter.copy(
                                balance = renter.balance - amount,
                                debtAmount = maxOf(0.0, -(renter.balance - amount))
                            )
                            end = realWs
                            guard++
                        }
                    }
                }
            } else {
                Log.d(TAG, "reconcile: Resume marker at $resumeStart already covered by regularGroups — skipping auto-generation")
            }
            // Сохраняем сам Resume-маркер (однодневный).
            // Пропускаем, если маркер с этой датой уже есть в БД — чтобы
            // не плодить дубликаты (маркеры, не удалённые на шаге 2а,
            // остаются в БД и не нуждаются в пересоздании).
            if (rm.startMs in existingResumeKeys) {
                Log.d(TAG, "reconcile: RESUME_MARKER at ${rm.startMs} already in DB — skip re-insert")
            } else
            try {
                historyRepository.insert(ContractHistoryEntry(
                    renterId = existing.id,
                    timestamp = now,
                    type = ContractHistoryEntry.TYPE_RETURNED,
                    amount = 0.0,
                    notes = "RESUME_MARKER",
                    renterName = newName,
                    renterPhone = newPhone,
                    scooterName = newScooterName ?: scooter?.name ?: "",
                    weekStart = rm.startMs,
                    weekEnd = rm.endMs,
                    weeklyPrice = 0.0,
                    passportData = passportData,
                    address = address,
                    pinfl = pinfl,
                    vinNumber = scooter?.vinNumber ?: "",
                    engineNumber = scooter?.engineNumber ?: "",
                    scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                    batteryId1 = scooter?.batteryId1 ?: "",
                    batteryId2 = scooter?.batteryId2 ?: "",
                    additionalInfo = scooter?.additionalInfo ?: "",
                    isPaid = false
                ))
                Log.d(TAG, "reconcile: saved RESUME_MARKER for renter #${existing.id} at ${rm.startMs}")
            } catch (e: Exception) {
                Log.w(TAG, "reconcile: failed to save RESUME_MARKER: ${e.message}")
            }
        }

        // ── 6. Сохраняем актуальный баланс арендатора в БД ─────────────
        // (он мог измениться в шаге 3 через каскад и в шаге 5 через вставку)
        repository.update(renter)

        // ── 7. Обновляем остальные поля арендатора + start/duration ────
        // Вычисляем новые startDate и duration на основе групп.
        val sortedGroups = groups.sortedBy { it.startMs }
        val newStart = sortedGroups.firstOrNull()?.startMs ?: existing.rentStartDateTimestamp
        val newEnd = sortedGroups.lastOrNull()?.endMs ?: (newStart + 7L * 24 * 60 * 60 * 1000)
        val newDurationDays = ((newEnd - newStart) / (24L * 60 * 60 * 1000))
            .toInt().coerceAtLeast(1)

        // Перечитываем ещё раз — вдруг баланс изменился между шагами 5 и 6
        val finalRenter = repository.getById(existing.id) ?: renter
        val updated = finalRenter.copy(
            name = newName,
            phoneNumber = newPhone,
            rentDurationDays = newDurationDays,
            rentStartDateTimestamp = newStart,
            scooterId = newScooterId,
            scooterName = newScooterName,
            isReturned = !newIsActive,
            passportData = passportData,
            address = address,
            pinfl = pinfl,
            autoRenewMode = autoRenewMode
        )
        repository.update(updated)

        // ── 8. Обновляем виджеты ───────────────────────────────────────
        try {
            com.example.widget.WidgetUpdater.updateAll(getApplication())
        } catch (_: Exception) {}

        Log.d(TAG, "reconcile: completed for renter #${existing.id}, " +
                "deleted=${toDelete.size}, updated=${toUpdate.size}, added=${toAdd.size}")

        // ── 9. Гарантируем наличие RESUME-маркера на последнем дне ────────
        // последнего контракта арендатора. По требованию пользователя:
        // у каждого арендатора на последнем дне окончания его последнего
        // контракта должна быть отметка «Davom» (RESUME). Это работает
        // как визуальный сигнал «контракт закончился, готов к продлению».
        // Если RESUME-маркер на эту дату уже существует (пользователь
        // сам поставил или он остался с прошлой версии) — пропускаем.
        ensureResumeMarkerOnLastContractDay(
            renterId = existing.id,
            renterName = newName,
            renterPhone = newPhone,
            scooterName = newScooterName ?: scooter?.name ?: "",
            passportData = passportData,
            address = address,
            pinfl = pinfl,
            scooter = scooter
        )
    }

    /**
     * Гарантирует наличие RESUME-маркера на последний день (weekEnd)
     * последнего контракта арендатора.
     *
     * По требованию пользователя: у каждого арендатора на последнем дне
     * окончания его последнего контракта должна быть отметка «Davom»
     * (RESUME_MARKER). Это визуальный сигнал «контракт закончился,
     * готов к продлению».
     *
     * Алгоритм:
     *   1. Загружаем все контракты арендатора (TYPE_CREATED + TYPE_AUTO_RENEW).
     *   2. Находим тот, у которого max(weekEnd) — это «последний контракт».
     *   3. Загружаем все существующие RESUME-маркеры (TYPE_RETURNED +
     *      notes="RESUME_MARKER").
     *   4. Если уже есть маркер на дату weekEnd последнего контракта —
     *      ничего не делаем.
     *   5. Иначе вставляем новый RESUME_MARKER с weekStart=weekEnd=
     *      lastContractWeekEnd.
     *
     * Не создаёт Transaction и не меняет баланс — маркеры только
     * визуальные. Дату храним как есть (без нормализации на полночь),
     * чтобы совпадение с датой контракта было точным.
     *
     * Эта функция вызывается:
     *   • Из addRenter (после сохранения всех specs) — для нового арендатора.
     *   • Из reconcileContractsFromGroups — после редактирования.
     *   • Из одноразовой миграции backfillResumeMarkersForAllRenters()
     *     в MainActivity.onCreate — для существующих арендаторов.
     */
    private suspend fun ensureResumeMarkerOnLastContractDay(
        renterId: Int,
        renterName: String,
        renterPhone: String,
        scooterName: String,
        passportData: String,
        address: String,
        pinfl: String,
        scooter: Scooter?
    ) {
        try {
            // ── 1. Загружаем все обычные контракты ──────────────────────
            // contractsForRenterOnce возвращает только TYPE_CREATED и
            // TYPE_AUTO_RENEW (без маркеров STOP/RESUME).
            val contracts = historyRepository.contractsForRenterOnce(renterId)
            if (contracts.isEmpty()) {
                Log.d(TAG, "ensureResumeMarker: renter #$renterId has no contracts — skip")
                return
            }

            // ── 2. Находим последний контракт (max weekEnd) ─────────────
            // weekEnd nullable в модели, но для обычных контрактов
            // (TYPE_CREATED/AUTO_RENEW) он всегда заполнен. Если по какой-то
            // причине weekEnd = null — пропускаем такой контракт.
            val lastContract = contracts
                .filter { it.weekEnd != null }
                .maxByOrNull { it.weekEnd!! } ?: return
            val lastDayMs: Long = lastContract.weekEnd!!

            // ── 3. Загружаем все RESUME-маркеры ────────────────────────
            // getForRenterOnce возвращает ВСЕ записи — включая маркеры.
            val allEntries = historyRepository.getForRenterOnce(renterId)
            val existingResumeMarkers = allEntries.filter {
                it.type == ContractHistoryEntry.TYPE_RETURNED &&
                it.notes == "RESUME_MARKER"
            }

            // ── 3a. Проверяем «активный» STOP (без последующего RESUME) ──
            // Если у арендатора есть STOP_MARKER, после которого нет
            // RESUME_MARKER — значит аренда приостановлена, и автоматически
            // добавлять RESUME на последний день контракта НЕЛЬЗЯ: это
            // выведет арендатора из архива и сломает логику. Пользователь
            // должен явно поставить Resume, когда захочет возобновить аренду.
            //
            // Алгоритм:
            //   1. Находим самый свежий STOP_MARKER.
            //   2. Если есть RESUME_MARKER с датой СТРОГО больше — STOP не
            //      активен, можно добавлять новый RESUME.
            //   3. Иначе — STOP активен, пропускаем автоматическое добавление.
            val stopMarkers = allEntries.filter {
                it.type == ContractHistoryEntry.TYPE_TERMINATED &&
                it.notes == "STOP_MARKER" &&
                it.weekStart != null
            }
            if (stopMarkers.isNotEmpty()) {
                val latestStop = stopMarkers.maxByOrNull { it.weekStart!! }
                if (latestStop != null) {
                    val hasResumeAfterStop = existingResumeMarkers.any {
                        (it.weekStart ?: 0L) > (latestStop.weekStart ?: 0L)
                    }
                    if (!hasResumeAfterStop) {
                        Log.d(TAG, "ensureResumeMarker: renter #$renterId has active STOP " +
                                "at ${latestStop.weekStart} without RESUME after — skip " +
                                "(renter is archived, do not auto-add RESUME)")
                        return
                    }
                }
            }

            // ── 4. Проверяем, есть ли уже маркер на эту дату ────────────
            // Используем день-гранулярность (сравниваем по Y/M/D, а не по
            // миллисекундам — на случай если маркер и контракт сохранены
            // с разным смещением от полуночи).
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = lastDayMs
            val lastYear = cal.get(java.util.Calendar.YEAR)
            val lastMonth = cal.get(java.util.Calendar.MONTH)
            val lastDay = cal.get(java.util.Calendar.DAY_OF_MONTH)

            val alreadyExists = existingResumeMarkers.any { m ->
                val ms = m.weekStart ?: return@any false
                val mc = java.util.Calendar.getInstance()
                mc.timeInMillis = ms
                mc.get(java.util.Calendar.YEAR) == lastYear &&
                mc.get(java.util.Calendar.MONTH) == lastMonth &&
                mc.get(java.util.Calendar.DAY_OF_MONTH) == lastDay
            }
            if (alreadyExists) {
                Log.d(TAG, "ensureResumeMarker: renter #$renterId already has RESUME_MARKER " +
                        "on $lastDay-$lastMonth-$lastYear — skip")
                return
            }

            // ── 5. Вставляем новый RESUME_MARKER ────────────────────────
            // weekStart = weekEnd = lastDayMs (однодневный маркер).
            // amount = 0, isPaid = false — маркеры не влияют на баланс.
            val now = System.currentTimeMillis()
            historyRepository.insert(ContractHistoryEntry(
                renterId = renterId,
                timestamp = now,
                type = ContractHistoryEntry.TYPE_RETURNED,
                amount = 0.0,
                notes = "RESUME_MARKER",
                renterName = renterName,
                renterPhone = renterPhone,
                scooterName = scooterName,
                weekStart = lastDayMs,
                weekEnd = lastDayMs,
                weeklyPrice = 0.0,
                passportData = passportData,
                address = address,
                pinfl = pinfl,
                vinNumber = scooter?.vinNumber ?: "",
                engineNumber = scooter?.engineNumber ?: "",
                scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                batteryId1 = scooter?.batteryId1 ?: "",
                batteryId2 = scooter?.batteryId2 ?: "",
                additionalInfo = scooter?.additionalInfo ?: "",
                isPaid = false
            ))
            Log.d(TAG, "ensureResumeMarker: inserted RESUME_MARKER for renter #$renterId " +
                    "at last contract day $lastDayMs")
        } catch (e: Exception) {
            Log.w(TAG, "ensureResumeMarker failed for renter #$renterId: ${e.message}")
        }
    }

    /**
     * Одноразовая миграция: добавляет RESUME-маркеры на последние дни
     * последних контрактов ВСЕХ существующих арендаторов.
     *
     * Запускается из MainActivity.onCreate (через LaunchedEffect), используя
     * SharedPreferences-флаг "resume_marker_backfill_v1" для гарантии
     * одноразовости. Повторные запуски пропускаются, чтобы не плодить
     * дубликаты при обновлениях приложения.
     *
     * Для каждого арендатора:
     *   • Если есть хотя бы один обычный контракт (TYPE_CREATED/AUTO_RENEW)
     *     — находит max(weekEnd) и вызывает ensureResumeMarkerOnLastContractDay.
     *   • Если контрактов нет — пропускает.
     *
     * Поток: Dispatchers.IO (миграция может затронуть много арендаторов).
     */
    fun backfillResumeMarkersForAllRenters() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val prefs = context.getSharedPreferences("resume_marker_backfill", Context.MODE_PRIVATE)
                if (prefs.getBoolean("v1_done", false)) {
                    Log.d(TAG, "backfillResumeMarkers: already done — skip")
                    return@launch
                }

                val allRenters = repository.getActiveRenters()
                Log.d(TAG, "backfillResumeMarkers: processing ${allRenters.size} renters")

                var processed = 0
                var inserted = 0
                for (renter in allRenters) {
                    // getActiveRenters уже фильтрует isDeleted=1 (арендаторов
                    // в корзине), так что отдельная проверка не нужна.

                    val contracts = historyRepository.contractsForRenterOnce(renter.id)
                    if (contracts.isEmpty()) continue

                    // Считаем количество RESUME-маркеров до и после —
                    // чтобы залогировать, сколько реально вставлено.
                    val beforeCount = historyRepository.getForRenterOnce(renter.id)
                        .count { it.type == ContractHistoryEntry.TYPE_RETURNED &&
                                 it.notes == "RESUME_MARKER" }

                    ensureResumeMarkerOnLastContractDay(
                        renterId = renter.id,
                        renterName = renter.name,
                        renterPhone = renter.phoneNumber,
                        scooterName = renter.scooterName ?: "",
                        passportData = renter.passportData,
                        address = renter.address,
                        pinfl = renter.pinfl,
                        scooter = renter.scooterId?.let { fetchScooterById(it) }
                    )

                    val afterCount = historyRepository.getForRenterOnce(renter.id)
                        .count { it.type == ContractHistoryEntry.TYPE_RETURNED &&
                                 it.notes == "RESUME_MARKER" }
                    if (afterCount > beforeCount) inserted++

                    processed++
                }

                prefs.edit().putBoolean("v1_done", true).apply()
                Log.d(TAG, "backfillResumeMarkers: done — processed=$processed, " +
                        "inserted_new=$inserted out of ${allRenters.size} renters")

                // Обновляем виджеты — в календаре могут появиться новые маркеры
                try { com.example.widget.WidgetUpdater.updateAll(context) } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "backfillResumeMarkers failed", e)
            }
        }
    }

    /**
     * Пересчитывает ЭФФЕКТИВНЫЙ баланс для ВСЕХ активных арендаторов на основе
     * их контрактов и текущего времени. Использует новую модель «отель/ночь»
     * (см. [ContractHistoryEntry.computeEffectiveBalance]):
     *
     *   • Оплаченный контракт, период которого полностью прошёл → 0
     *     (скутер отработал оплату, никто никому не должен).
     *   • Оплаченный контракт, период ещё идёт или в будущем → +amount
     *     (предоплата — мы должны услугу).
     *   • Неоплаченный контракт, период начался → −amount (долг).
     *   • Неоплаченный контракт, период не начался → 0 (платить рано).
     *
     * Запускается:
     *   • Из MainActivity.onCreate (через LaunchedEffect) — мгновенное
     *     обновление при старте приложения.
     *   • Из PaymentCheckWorker — ежечасно.
     *
     * Это критично для сценария «первый день последнего неоплаченного
     * контракта = сегодня» — баланс должен стать минусом, а статус — красным.
     * Раньше баланс оставался «плюсом» из-за прошлых оплаченных контрактов.
     */
    fun refreshAllBalances() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val allRenters = repository.getActiveRenters()
                val now = System.currentTimeMillis()
                var updated = 0
                for (renter in allRenters) {
                    try {
                        val contracts = historyRepository.contractsForRenterOnce(renter.id)
                        val effective = ContractHistoryEntry.computeEffectiveBalance(contracts, now)
                        if (kotlin.math.abs(effective - renter.balance) > 1.0) {
                            repository.update(renter.copy(
                                balance = effective,
                                debtAmount = maxOf(0.0, -effective)
                            ))
                            updated++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "refreshAllBalances: failed for renter #${renter.id}", e)
                    }
                }
                if (updated > 0) {
                    Log.d(TAG, "refreshAllBalances: updated $updated/${allRenters.size} renters")
                    // Обновляем виджеты — балансы могли измениться.
                    try {
                        com.example.widget.WidgetUpdater.updateAll(getApplication())
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "refreshAllBalances failed", e)
            }
        }
    }

    /**
     * Проверяет, является ли арендатор архивным по его history entries.
     *
     * Логика архивации (по требованию пользователя, обновлённая версия):
     *   Каждый день проверяем два сценария — оба сводятся к одному правилу:
     *   "после последнего STOP-маркера нет ни одного RESUME-маркера".
     *
     *   Сценарий A (STOP сегодня):
     *     1. Есть ли STOP-маркер сегодня или в прошлом?
     *     2. После последнего STOP есть ли RESUME (сегодня или в будущем)?
     *        — Если есть → не архивный (ожидаем возобновление или уже возобновлён).
     *        — Если нет → архивный.
     *
     *   Сценарий B (STOP был в прошлом, без возобновления):
     *     1. Есть ли STOP-маркер в прошлом (weekStart < today)?
     *     2. После этого STOP есть ли RESUME — между STOP-днём и сегодня,
     *        сегодня, или в будущем?
     *        — Если есть → не архивный (возобновление уже было или запланировано).
     *        — Если нет → архивный (STOP был, возобновления нет и не предвидится).
     *
     *   Объединённое правило: берём последний STOP с датой <= сегодня;
     *   если после него нет ни одного RESUME (в любой день — прошлый между
     *   STOP и сегодня, сегодня, или будущий) → архивный. Иначе → активен.
     *
     * ВАЖНО: все STOP и RESUME маркеры СОХРАНЯЮТСЯ в БД при архивации —
     * архивация это вычисляемое состояние (StateFlow), а не изменение в БД.
     * Пользователь может вывести арендатора из архива, поставив RESUME-маркер
     * на сегодня (см. restoreRenterFromArchive) — RESUME сегодня (timestamp
     * больше timestamp STOP'а) считается "возобновлённым" и выводит из архива.
     *
     * todayStart передаётся снаружи, чтобы не вычислять его на каждой
     * итерации в filter (экономия при обработке больших списков).
     */
    private fun isRenterArchived(
        entries: List<ContractHistoryEntry>,
        todayStart: Long
    ): Boolean {
        // ── Шаг 1: Найти все STOP-маркеры с датой <= сегодня ─────────────
        // STOP "сегодня или в прошлом" — аренда была приостановлена. STOP в
        // будущем не считаем (аренда ещё активна, STOP только запланирован).
        val stopMarkersTodayOrPast = entries
            .filter {
                it.type == ContractHistoryEntry.TYPE_TERMINATED &&
                it.notes == "STOP_MARKER" &&
                (it.weekStart ?: 0L) <= todayStart
            }
        if (stopMarkersTodayOrPast.isEmpty()) return false

        // ── Шаг 2: Найти последний (самый поздний) STOP-маркер ──────────
        // Если последний STOP уже был "перекрыт" RESUME'ом, и после не было
        // нового STOP — арендатор активен. Если после последнего STOP нет
        // RESUME — арендатор архивный (независимо от того, был ли STOP сегодня
        // или в прошлом — главное, что возобновления не было до сих пор).
        val lastStop = stopMarkersTodayOrPast.maxByOrNull { it.weekStart ?: 0L }!!

        // ── Шаг 3: Есть ли RESUME-маркер ПОСЛЕ последнего STOP? ──────────
        // RESUME должен быть строго после STOP — он "возобновляет" аренду.
        // Сравнение по weekStart (начало дня). Если STOP и RESUME в один
        // день (weekStart равны) — сравниваем по timestamp создания записи,
        // чтобы корректно обработать restoreRenterFromArchive (RESUME сегодня
        // ставится ПОСЛЕ STOP'а в тот же день).
        //
        // Это покрывает оба сценария по требованию пользователя:
        //   • STOP сегодня + нет RESUME в будущем → архив.
        //   • STOP в прошлом + нет RESUME между STOP-днём и сегодня → архив.
        //   • STOP сегодня/в прошлом + есть RESUME после → активен.
        val lastStopWeekStart = lastStop.weekStart ?: 0L
        val lastStopTimestamp = lastStop.timestamp
        val hasResumeAfterLastStop = entries.any {
            if (it.type != ContractHistoryEntry.TYPE_RETURNED ||
                it.notes != "RESUME_MARKER"
            ) return@any false
            val rStart = it.weekStart ?: 0L
            when {
                rStart > lastStopWeekStart -> true           // RESUME в более поздний день
                rStart < lastStopWeekStart -> false          // RESUME раньше STOP — не считается
                else -> it.timestamp > lastStopTimestamp     // тот же день — по времени создания
            }
        }
        // Если есть RESUME после последнего STOP → не архивный. Иначе → архивный.
        return !hasResumeAfterLastStop
    }

    /**
     * Восстанавливает арендатора из архива.
     *
     * По логике архива: арендатор в архиве, потому что у него есть
     * STOP-маркер в прошлом без RESUME после. Чтобы вернуть его в
     * активные, нужно поставить RESUME-маркер на СЕГОДНЯ — это
     * сигнализирует «аренда возобновлена с сегодняшнего дня».
     *
     * После вставки RESUME-маркера archivedRenters StateFlow автоматически
     * пересчитается (он зависит от liveContracts), и арендатор исчезнет
     * из архива и появится в активных.
     *
     * Дополнительно ensureResumeMarkerOnLastContractDay обновит
     * RESUME-маркер на последнем дне последнего контракта (если
     * последний контракт изменился после STOP'а).
     */
    fun restoreRenterFromArchive(renterId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val renter = repository.getById(renterId) ?: return@launch
                val now = System.currentTimeMillis()
                val todayStart = run {
                    val cal = java.util.Calendar.getInstance()
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                    cal.set(java.util.Calendar.MINUTE, 0)
                    cal.set(java.util.Calendar.SECOND, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    cal.timeInMillis
                }
                val scooter: Scooter? = renter.scooterId?.let { fetchScooterById(it) }

                // Вставляем RESUME-маркер на сегодня — это выведет
                // арендатора из архива (появится RESUME после STOP).
                historyRepository.insert(ContractHistoryEntry(
                    renterId = renterId,
                    timestamp = now,
                    type = ContractHistoryEntry.TYPE_RETURNED,
                    amount = 0.0,
                    notes = "RESUME_MARKER",
                    renterName = renter.name,
                    renterPhone = renter.phoneNumber,
                    scooterName = renter.scooterName ?: "",
                    weekStart = todayStart,
                    weekEnd = todayStart,
                    weeklyPrice = 0.0,
                    passportData = renter.passportData,
                    address = renter.address,
                    pinfl = renter.pinfl,
                    vinNumber = scooter?.vinNumber ?: "",
                    engineNumber = scooter?.engineNumber ?: "",
                    scooterSerialNumber = scooter?.scooterSerialNumber ?: "",
                    batteryId1 = scooter?.batteryId1 ?: "",
                    batteryId2 = scooter?.batteryId2 ?: "",
                    additionalInfo = scooter?.additionalInfo ?: "",
                    isPaid = false
                ))
                Log.d(TAG, "restoreRenterFromArchive: inserted RESUME_MARKER at $todayStart " +
                        "for renter #$renterId — moved back to active")

                try { com.example.widget.WidgetUpdater.updateAll(getApplication()) } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "restoreRenterFromArchive failed for renter #$renterId", e)
            }
        }
    }

    /**
     * Каскадное удаление одного контракта — упрощённая версия для reconcile.
     *
     * Делает то же самое, что и ContractHistoryViewModel.deleteContractWithCascade,
     * но в приватном виде внутри RenterViewModel (чтобы не плодить меж-VM вызовы).
     *
     * Шаги:
     * 1. Удаляет все Transaction с contractId = contract.id.
     * 2. Реверсит баланс главной карты для каждой CardTransaction с этим contractId
     *    и удаляет сами CardTransaction.
     * 3. Корректирует баланс арендатора: если контракт был isPaid → balance -= amount,
     *    если долг → balance += amount.
     * 4. Удаляет сам контракт.
     *
     * @param contract Контракт для удаления (должен быть загружен из БД).
     */
    private suspend fun deleteContractWithCascadeInternal(contract: ContractHistoryEntry) {
        // ── 1. Удаляем Transaction-записи ──────────────────────────────
        val relatedTx = transactionRepository.forContractOnce(contract.id)
        if (relatedTx.isNotEmpty()) {
            transactionRepository.deleteForContract(contract.id)
        }

        // ── 2. Реверсим и удаляем CardTransaction-записи ───────────────
        val relatedCardTx = virtualCardRepository.getCardTxForContract(contract.id)
        for (cardTx in relatedCardTx) {
            try {
                virtualCardRepository.adjustCardBalance(
                    cardId = cardTx.toCardId,
                    delta = -cardTx.amount
                )
            } catch (e: Exception) {
                Log.w(TAG, "deleteContractWithCascadeInternal: cardTx reverse failed: ${e.message}")
            }
        }
        if (relatedCardTx.isNotEmpty()) {
            virtualCardRepository.deleteCardTxForContract(contract.id)
        }

        // ── 3. Корректируем баланс арендатора ──────────────────────────
        // isPaid=true → balance -= amount (платёж был зачислен, откатываем).
        // isPaid=false → balance += amount (долг списывается, контракт удалён).
        val renter = repository.getById(contract.renterId) ?: return
        val balanceDelta = if (contract.isPaid) -contract.amount else contract.amount
        val newBalance = renter.balance + balanceDelta
        val updatedRenter = renter.copy(
            balance = newBalance,
            debtAmount = maxOf(0.0, -newBalance)
        )
        repository.update(updatedRenter)

        // ── 4. Удаляем сам контракт ────────────────────────────────────
        historyRepository.deleteById(contract.id)
    }

    fun deleteRenter(id: Int) {
        viewModelScope.launch {
            // ── Каскадное удаление арендатора ──────────────────────────────
            // Полная цепочка:
            //   1. Найти все контракты (ContractHistoryEntry) этого арендатора
            //   2. Для каждого контракта:
            //      a) Найти все CardTransaction с contractId = contract.id
            //         (это TYPE_CONTRACT_INCOME — деньги, упавшие на Glavnaya)
            //      b) Реверснуть баланс главной карты: adjustBalance(toCardId, -amount)
            //      c) Удалить эти CardTransaction
            //   3. Удалить все Transaction арендатора (renterId = id)
            //   4. Удалить все ContractHistoryEntry арендатора (renterId = id)
            //   5. Удалить самого арендатора
            //
            // Без шагов 2-3 деньги «зависнут» на главной карте, а в списке
            // транзакций останутся «осиротевшие» записи без арендатора.
            try {
                val contracts = historyRepository.getForRenterOnce(id)
                for (contract in contracts) {
                    // Реверсим и удаляем CardTransaction для этого контракта
                    val cardTxList = virtualCardRepository.getCardTxForContract(contract.id)
                    for (cardTx in cardTxList) {
                        try {
                            // cardTx.toCardId обычно = MAIN_CARD_ID (1).
                            // Реверс: вычитаем amount из баланса карты-получателя.
                            virtualCardRepository.adjustCardBalance(
                                cardId = cardTx.toCardId,
                                delta = -cardTx.amount
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "deleteRenter: failed to reverse cardTx #${cardTx.id}: ${e.message}")
                        }
                    }
                    if (cardTxList.isNotEmpty()) {
                        virtualCardRepository.deleteCardTxForContract(contract.id)
                    }
                }

                // Удаляем все Transaction арендатора (PAYMENT, PENALTY, REPAIR и т.д.)
                val renterTransactions = transactionRepository.forRenterOnce(id)
                if (renterTransactions.isNotEmpty()) {
                    transactionRepository.deleteByIds(renterTransactions.map { it.id })
                    Log.d(TAG, "deleteRenter: deleted ${renterTransactions.size} transactions for renter #$id")
                }

                // Удаляем все контракты арендатора
                historyRepository.deleteForRenter(id)
                Log.d(TAG, "deleteRenter: deleted ${contracts.size} contracts for renter #$id")

                // Удаляем самого арендатора
                repository.delete(id)
                Log.d(TAG, "deleteRenter: renter #$id deleted with full cascade")
            } catch (e: Exception) {
                Log.e(TAG, "deleteRenter cascade failed for #$id", e)
                // Fallback: хотя бы удалить арендатора (старое поведение)
                try {
                    historyRepository.deleteForRenter(id)
                    repository.delete(id)
                } catch (_: Exception) {}
            }

            // Обновляем нативные виджеты Android
            try {
                com.example.widget.WidgetUpdater.updateAll(getApplication())
            } catch (_: Exception) {}
        }
    }

    fun markPaymentReceived(renter: Renter) {
        viewModelScope.launch { applyWeeklyPayment(renter, "Bitta to'lov") }
    }

    /**
     * Пакетная оплата одной недели (7 дней) для выбранных арендаторов.
     * Legacy-метод, оставлен для совместимости со старыми вызовами.
     * Делегирует в [payForDaysForRenters] с days=7.
     */
    fun payWeeklyForRenters(renterIds: Set<Int>) {
        payForDaysForRenters(renterIds, 7)
    }

    /**
     * Пакетная оплата N дней для выбранных арендаторов.
     *
     * Каждый арендатор из [renterIds] оплачивает [days] дней аренды через
     * единый use-case [com.example.data.RenterActionUseCase.payForDays],
     * который:
     *   • Если есть неоплаченные контракты — гасит их по порядку (от раннего).
     *   • Если осталась сдача — создаёт новый оплаченный контракт на эту сдачу.
     *   • Если неоплаченных нет — создаёт предоплаченный контракт на N дней.
     */
    fun payForDaysForRenters(renterIds: Set<Int>, days: Int) {
        viewModelScope.launch {
            renterIds.forEach { id ->
                repository.getById(id)?.let { renter ->
                    actionUseCase.payForDays(
                        renter = renter,
                        days = days,
                        notes = "To'lov ($days kun)"
                    )
                }
            }
        }
    }

    fun terminateRenters(renterIds: Set<Int>) {
        viewModelScope.launch {
            val weeklyPrice = SettingsRepository(getApplication()).weeklyPrice
            renterIds.forEach { id -> repository.getById(id)?.let { applyTermination(it, weeklyPrice) } }
        }
    }

    /**
     * Оплата одной недели. Делегирует в [com.example.data.RenterActionUseCase.payWeekly] —
     * единый источник истины, который также используется action-кнопками
     * системного уведомления (см. NotificationActionReceiver).
     */
    private suspend fun applyWeeklyPayment(renter: Renter, notes: String, weeklyPriceOverride: Double? = null) {
        actionUseCase.payWeekly(renter, notes, weeklyPriceOverride)
    }

    /**
     * Расторжение контракта. Делегирует в [com.example.data.RenterActionUseCase.terminate] —
     * единый источник истины, который также используется action-кнопкой
     * «Kontraktni uzish» системного уведомления.
     */
    private suspend fun applyTermination(renter: Renter, weeklyPrice: Double) {
        actionUseCase.terminate(renter, weeklyPrice)
    }

    // ── Trash-mode operations (v36+) ─────────────────────────────────────
    /**
     * Каскадно помещает арендатора в корзину: сам арендатор + все его контракты
     * + все его транзакции + реверс card_transactions. Не удаляет данные
     * физически — только помечает isDeleted=1.
     */
    fun moveRenterToTrash(id: Int) {
        viewModelScope.launch {
            try {
                trashService.moveRenterToTrash(id)
                com.example.widget.WidgetUpdater.updateAll(getApplication())
            } catch (e: Exception) {
                Log.e(TAG, "moveRenterToTrash failed for #$id", e)
            }
        }
    }

    /**
     * Восстанавливает арендатора из корзины (вместе с дочерними сущностями).
     */
    fun restoreRenterFromTrash(id: Int) {
        viewModelScope.launch {
            try {
                trashService.restoreRenterFromTrash(id)
                com.example.widget.WidgetUpdater.updateAll(getApplication())
            } catch (e: Exception) {
                Log.e(TAG, "restoreRenterFromTrash failed for #$id", e)
            }
        }
    }

    /**
     * Окончательно удаляет арендатора из БД (вместе с контрактами, транзакциями
     * и card_transactions). Необратимо.
     */
    fun permanentlyDeleteRenter(id: Int) {
        viewModelScope.launch {
            try {
                trashService.permanentlyDeleteRenter(id)
                com.example.widget.WidgetUpdater.updateAll(getApplication())
            } catch (e: Exception) {
                Log.e(TAG, "permanentlyDeleteRenter failed for #$id", e)
            }
        }
    }

    companion object {
        private const val TAG = "RenterViewModel"
    }
}