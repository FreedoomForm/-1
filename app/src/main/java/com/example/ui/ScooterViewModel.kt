package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.Scooter
import com.example.data.ScooterRepository
import com.example.data.TimelineService
import com.example.data.TrashService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScooterViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ScooterRepository
    private val scooterDao: com.example.data.ScooterDao
    val scootersList: StateFlow<List<Scooter>>

    /**
     * UI feedback channel — emit (success, message) tuples for toast display.
     * Solves the "silent failure" bug: previously addScooter/updateScooter
     * logged duplicate conflicts but the user saw nothing — the dialog closed
     * and the scooter simply wasn't created.
     */
    private val _userMessage = MutableSharedFlow<Pair<Boolean, String>>(extraBufferCapacity = 8)
    val userMessage: SharedFlow<Pair<Boolean, String>> = _userMessage.asSharedFlow()

    init {
        val database = AppDatabase.getDatabase(application)
        scooterDao = database.scooterDao()
        repository = ScooterRepository(scooterDao)
        scootersList = repository.allScooters.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun addScooter(
        name: String,
        documentedNumber: String?,
        vinNumber: String = "",
        engineNumber: String = "",
        scooterSerialNumber: String = "",
        batteryId1: String = "",
        batteryId2: String = "",
        additionalInfo: String = "",
        nextServiceAt: Long? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // ── Validate name ─────────────────────────────────────────────
                val trimmedName = name.trim()
                if (trimmedName.isBlank()) {
                    _userMessage.emit(false to "Skuter nomi bo'sh bo'lishi mumkin emas")
                    return@launch
                }

                // ── Duplicate name check (case-insensitive) ───────────────────
                // Mirrors RenterViewModel.addRenter's check. The form also does
                // this live, but a hard block here is the last line of defense.
                val allScooters = scooterDao.getAllScootersOnce()
                val nameConflict = allScooters.any {
                    it.name.trim().equals(trimmedName, ignoreCase = true)
                }
                if (nameConflict) {
                    _userMessage.emit(false to "Bunday nomdagi skuter allaqachon mavjud: $trimmedName")
                    return@launch
                }

                // ── Duplicate VIN/engine/serial check ─────────────────────────
                val vinConflict = vinNumber.trim().isNotBlank() && allScooters.any {
                    it.vinNumber.trim().equals(vinNumber.trim(), ignoreCase = true)
                }
                val engineConflict = engineNumber.trim().isNotBlank() && allScooters.any {
                    it.engineNumber.trim().equals(engineNumber.trim(), ignoreCase = true)
                }
                val serialConflict = scooterSerialNumber.trim().isNotBlank() && allScooters.any {
                    it.scooterSerialNumber.trim().equals(scooterSerialNumber.trim(), ignoreCase = true)
                }
                if (vinConflict || engineConflict || serialConflict) {
                    val dup = buildString {
                        if (vinConflict) append("VIN ")
                        if (engineConflict) append("dvigatel ")
                        if (serialConflict) append("seriya ")
                    }.trim()
                    _userMessage.emit(false to "Bu $dup boshqa skuterga biriktirilgan")
                    return@launch
                }

                val scooter = Scooter(
                    name = trimmedName,
                    documentedNumber = documentedNumber,
                    vinNumber = vinNumber,
                    engineNumber = engineNumber,
                    scooterSerialNumber = scooterSerialNumber,
                    batteryId1 = batteryId1,
                    batteryId2 = batteryId2,
                    additionalInfo = additionalInfo,
                    nextServiceAt = nextServiceAt
                )
                val newId = repository.insert(scooter)
                if (newId <= 0L) {
                    _userMessage.emit(false to "Skuter saqlanmadi (konflikt)")
                    return@launch
                }
                _userMessage.emit(true to "Skuter yaratildi: $trimmedName")

                // §9.0: таймкод критического действия — SCOOTER_CREATE.
                try {
                    TimelineService(AppDatabase.getDatabase(getApplication()))
                        .recordCriticalAction(
                            actionType = "SCOOTER_CREATE",
                            screen = "SCOOTERS",
                            title = "Yangi skuter: $trimmedName",
                            entityType = "SCOOTER",
                            entityId = newId.toString(),
                            payloadJson = "{\"name\":\"$trimmedName\",\"vin\":\"$vinNumber\"}"
                        )
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "addScooter failed", e)
                _userMessage.emit(false to "Skuter yaratilmadi: ${e.message ?: "noma'lum xato"}")
            }
        }
    }

    fun updateScooter(scooter: Scooter) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val trimmedName = scooter.name.trim()
                if (trimmedName.isBlank()) {
                    _userMessage.emit(false to "Skuter nomi bo'sh bo'lishi mumkin emas")
                    return@launch
                }

                // ── Duplicate checks excluding self ───────────────────────────
                val allScooters = scooterDao.getAllScootersOnce()
                val nameConflict = allScooters.any {
                    it.id != scooter.id && it.name.trim().equals(trimmedName, ignoreCase = true)
                }
                if (nameConflict) {
                    _userMessage.emit(false to "Bunday nomdagi skuter allaqachon mavjud: $trimmedName")
                    return@launch
                }
                val vinConflict = scooter.vinNumber.trim().isNotBlank() && allScooters.any {
                    it.id != scooter.id && it.vinNumber.trim().equals(scooter.vinNumber.trim(), ignoreCase = true)
                }
                val engineConflict = scooter.engineNumber.trim().isNotBlank() && allScooters.any {
                    it.id != scooter.id && it.engineNumber.trim().equals(scooter.engineNumber.trim(), ignoreCase = true)
                }
                val serialConflict = scooter.scooterSerialNumber.trim().isNotBlank() && allScooters.any {
                    it.id != scooter.id && it.scooterSerialNumber.trim().equals(scooter.scooterSerialNumber.trim(), ignoreCase = true)
                }
                if (vinConflict || engineConflict || serialConflict) {
                    val dup = buildString {
                        if (vinConflict) append("VIN ")
                        if (engineConflict) append("dvigatel ")
                        if (serialConflict) append("seriya ")
                    }.trim()
                    _userMessage.emit(false to "Bu $dup boshqa skuterga biriktirilgan")
                    return@launch
                }

                repository.update(scooter)
                _userMessage.emit(true to "Skuter yangilandi: $trimmedName")

                // §9.0: таймкод критического действия — SCOOTER_UPDATE.
                try {
                    TimelineService(AppDatabase.getDatabase(getApplication()))
                        .recordCriticalAction(
                            actionType = "SCOOTER_UPDATE",
                            screen = "SCOOTERS",
                            title = "Skuter yangilandi: $trimmedName",
                            entityType = "SCOOTER",
                            entityId = scooter.id.toString(),
                            payloadJson = "{\"name\":\"$trimmedName\",\"status\":\"${scooter.lifecycleStatus}\"}"
                        )
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "updateScooter failed", e)
                _userMessage.emit(false to "Skuter yangilanmadi: ${e.message ?: "noma'lum xato"}")
            }
        }
    }

    fun sendToRepair(
        scooterId: Int,
        reason: String,
        scenario: String = com.example.data.RepairOrder.SCENARIO_RENTER_REPAIR
    ) {
        viewModelScope.launch {
            try { com.example.data.ScooterMaintenanceService(AppDatabase.getDatabase(getApplication()))
                .changeStatus(scooterId, Scooter.STATUS_REPAIR, reason, scenario)
                _userMessage.emit(true to "Skuter ta'mirga yuborildi")
            }
            catch (e: Exception) {
                Log.e(TAG, "Failed to pause rental for repair", e)
                _userMessage.emit(false to "Ta'mirga yuborish amalga oshmadi: ${e.message ?: "noma'lum xato"}")
            }
        }
    }

    fun replaceScooterForRental(oldScooterId: Int, newScooterId: Int, reason: String) {
        viewModelScope.launch {
            try { com.example.data.ScooterMaintenanceService(AppDatabase.getDatabase(getApplication()))
                .replaceScooterForActiveRental(oldScooterId, newScooterId, reason)
                _userMessage.emit(true to "Skuter almashtirildi")
            }
            catch (e: Exception) {
                Log.e(TAG, "Failed to replace scooter for rental", e)
                _userMessage.emit(false to "Skuter almashtirilmadi: ${e.message ?: "noma'lum xato"}")
            }
        }
    }

    fun resumeAfterRepair(scooterId: Int, reason: String) {
        viewModelScope.launch {
            try { com.example.data.ScooterMaintenanceService(AppDatabase.getDatabase(getApplication()))
                .resumeAfterRepair(scooterId, reason)
                _userMessage.emit(true to "Skuter ta'mirdan qaytdi")
            }
            catch (e: Exception) {
                Log.e(TAG, "Failed to resume rental after repair", e)
                _userMessage.emit(false to "Ta'mirdan qaytarish amalga oshmadi: ${e.message ?: "noma'lum xato"}")
            }
        }
    }

    companion object { private const val TAG = "ScooterViewModel" }

    /**
     * Каскадное удаление скутера.
     *
     * Шаги:
     * 1. Snapshot скутера в корзину (TrashService) — чтобы можно было восстановить.
     * 2. Найти всех арендаторов с scooterId = scooter.id.
     *    Для каждого: очистить scooterId и scooterName (разорвать связь),
     *    НЕ удаляя арендатора.
     * 3. Найти все ContractHistoryEntry с scooterName = scooter.name —
     *    snapshot в корзину, затем удалить (они больше не имеют смысла
     *    без скутера).
     * 4. Найти все Transaction с scooterId = scooter.id — snapshot в корзину,
     *    затем удалить.
     * 5. Найти все открытые RepairOrder с scooterId = scooter.id — закрыть их.
     * 6. Записать таймкод критического действия SCOOTER_DELETE.
     * 7. Удалить сам скутер.
     *
     * Раньше deleteScooter просто вызывал repository.delete(scooter) без
     * какого-либо каскада — оставались «осиротевшие» транзакции и контракты
     * со ссылками на несуществующий скутер.
     */
    fun deleteScooter(scooter: Scooter) {
        viewModelScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(getApplication())
            try {
                // 1. Snapshot в корзину
                TrashService(db).snapshotScooter(scooter, "Scooter deleted by user")

                // 2. Разорвать связь с арендаторами (не удаляя их)
                val rentersWithScooter = db.renterDao().getActiveRenters()
                    .filter { it.scooterId == scooter.id }
                rentersWithScooter.forEach { r ->
                    db.renterDao().updateRenter(r.copy(scooterId = null, scooterName = null))
                }
                if (rentersWithScooter.isNotEmpty()) {
                    Log.d(TAG, "deleteScooter: cleared scooter ref from ${rentersWithScooter.size} renters")
                }

                // 3. Удалить контракты с этим скутером (snapshot first)
                //    Для каждого контракта также реверсим и удаляем его
                //    CardTransaction и Transaction — без этого оставались бы
                //    осиротевшие финансовые записи со ссылкой на удалённый
                //    контракт (главная карта врал бы в балансе).
                val contracts = db.contractHistoryDao().getForScooterOnce(scooter.name)
                val trashSvc = TrashService(db)
                contracts.forEach { trashSvc.snapshotContract(it, "Removed with scooter #${scooter.id}") }
                for (contract in contracts) {
                    // Reverse + delete CardTransactions tied to this contract
                    val cardTxs = try { db.cardTransactionDao().getForContractOnce(contract.id) } catch (_: Exception) { emptyList() }
                    for (cardTx in cardTxs) {
                        try {
                            db.virtualCardDao().adjustBalance(cardTx.toCardId, -cardTx.amount)
                            try { db.businessOperationDao().markReversedByCardTransactionId(cardTx.id) } catch (_: Exception) {}
                        } catch (e: Exception) {
                            Log.w(TAG, "deleteScooter: failed to reverse cardTx #${cardTx.id}: ${e.message}")
                        }
                    }
                    if (cardTxs.isNotEmpty()) {
                        try { db.cardTransactionDao().deleteForContract(contract.id) } catch (_: Exception) {}
                    }

                    // Snapshot + delete Transaction rows for this contract
                    val contractTxs = db.transactionDao().getForContractOnce(contract.id)
                    contractTxs.forEach { trashSvc.snapshotTransaction(it, "Removed with scooter #${scooter.id}") }
                    if (contractTxs.isNotEmpty()) {
                        contractTxs.forEach { tx ->
                            try { db.businessOperationDao().markReversedByLegacyTransactionId(tx.id) } catch (_: Exception) {}
                        }
                        db.transactionDao().deleteForContract(contract.id)
                    }

                    // Per-contract: cancel RentPeriod, delete allocations,
                    // reverse ops, delete handover acts.
                    try { db.paymentAllocationDao().deleteByContractViaPeriod(contract.id) } catch (_: Exception) {}
                    try { db.rentPeriodDao().deleteByContract(contract.id) } catch (_: Exception) {}
                    try { db.businessOperationDao().markReversedByContract(contract.id) } catch (_: Exception) {}
                    try { db.handoverActDao().deleteByContract(contract.id) } catch (_: Exception) {}
                }
                if (contracts.isNotEmpty()) {
                    db.contractHistoryDao().deleteForScooter(scooter.name)
                    Log.d(TAG, "deleteScooter: deleted ${contracts.size} contracts for scooter ${scooter.name}")
                }

                // 4. Удалить транзакции с этим скутером (snapshot first)
                val txs = db.transactionDao().forScooterOnce(scooter.id)
                txs.forEach { trashSvc.snapshotTransaction(it, "Removed with scooter #${scooter.id}") }
                if (txs.isNotEmpty()) {
                    txs.forEach { tx ->
                        try { db.businessOperationDao().markReversedByLegacyTransactionId(tx.id) } catch (_: Exception) {}
                    }
                    db.transactionDao().deleteByIds(txs.map { it.id })
                    Log.d(TAG, "deleteScooter: deleted ${txs.size} transactions for scooter #${scooter.id}")
                }

                // 4b. Reverse any remaining BusinessOperations tied to this scooter
                // (e.g., REPAIR ops that were not linked to a Transaction row).
                try { db.businessOperationDao().markReversedByScooter(scooter.id) } catch (_: Exception) {}

                // 4c. Clean up orphaned PaymentAllocation + RentPeriod rows
                // (rent-periods created directly via calendar without a contract).
                try { db.paymentAllocationDao().deleteByScooterViaPeriod(scooter.id) } catch (_: Exception) {}
                try { db.rentPeriodDao().deleteByScooter(scooter.id) } catch (_: Exception) {}

                // 4d. Clean up HandoverActs + RepairOrders + LegacyMoneyAmount
                try { db.handoverActDao().deleteByScooter(scooter.id) } catch (_: Exception) {}
                // RepairOrder: close OPEN ones for history, then delete all rows
                try { db.repairOrderDao().closeOpenForScooter(scooter.id, "Scooter deleted") } catch (_: Exception) {}
                try { db.repairOrderDao().deleteByScooter(scooter.id) } catch (_: Exception) {}
                try { db.legacyMoneyAmountDao().deleteByEntity("SCOOTER", scooter.id.toLong()) } catch (_: Exception) {}

                // 5. (Skipped: RepairOrder cleanup is folded into 4d above.)

                // 6. Timeline critical action
                try {
                    TimelineService(db).recordCriticalAction(
                        actionType = "SCOOTER_DELETE",
                        screen = "SCOOTERS",
                        title = "Skuter o'chirildi: ${scooter.name}",
                        entityType = "SCOOTER",
                        entityId = scooter.id.toString(),
                        payloadJson = "{\"name\":\"${scooter.name}\",\"cascadeContracts\":${contracts.size},\"cascadeTransactions\":${txs.size}}"
                    )
                } catch (_: Exception) {}

                // 7. Удалить скутер
                repository.delete(scooter)
                _userMessage.emit(true to "Skuter o'chirildi: ${scooter.name}")

                // Update widgets
                try { com.example.widget.WidgetUpdater.updateAll(getApplication()) } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "deleteScooter cascade failed for #${scooter.id}", e)
                _userMessage.emit(false to "Skuterni o'chirish amalga oshmadi: ${e.message ?: ""}")
                // Fallback: still try plain delete
                try { repository.delete(scooter) } catch (_: Exception) {}
            }
        }
    }

    /** Bulk delete multiple scooters with the same cascade as [deleteScooter]. */
    fun deleteScooters(scooters: List<Scooter>) {
        scooters.forEach { deleteScooter(it) }
    }
}
