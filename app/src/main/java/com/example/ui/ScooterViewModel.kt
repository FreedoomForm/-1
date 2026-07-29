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
        }
    }

    fun updateScooter(scooter: Scooter) {
        viewModelScope.launch(Dispatchers.IO) {
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
        }
    }

    fun sendToRepair(
        scooterId: Int,
        reason: String,
        scenario: String = com.example.data.RepairOrder.SCENARIO_RENTER_REPAIR
    ) {
        viewModelScope.launch {
            try { com.example.data.ScooterMaintenanceService(AppDatabase.getDatabase(getApplication()))
                .changeStatus(scooterId, Scooter.STATUS_REPAIR, reason, scenario) }
            catch (e: Exception) { Log.e(TAG, "Failed to pause rental for repair", e) }
        }
    }

    fun replaceScooterForRental(oldScooterId: Int, newScooterId: Int, reason: String) {
        viewModelScope.launch {
            try { com.example.data.ScooterMaintenanceService(AppDatabase.getDatabase(getApplication()))
                .replaceScooterForActiveRental(oldScooterId, newScooterId, reason) }
            catch (e: Exception) { Log.e(TAG, "Failed to replace scooter for rental", e) }
        }
    }

    fun resumeAfterRepair(scooterId: Int, reason: String) {
        viewModelScope.launch {
            try { com.example.data.ScooterMaintenanceService(AppDatabase.getDatabase(getApplication()))
                .resumeAfterRepair(scooterId, reason) }
            catch (e: Exception) { Log.e(TAG, "Failed to resume rental after repair", e) }
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
                val contracts = db.contractHistoryDao().getForScooterOnce(scooter.name)
                val trashSvc = TrashService(db)
                contracts.forEach { trashSvc.snapshotContract(it, "Removed with scooter #${scooter.id}") }
                if (contracts.isNotEmpty()) {
                    db.contractHistoryDao().deleteForScooter(scooter.name)
                    Log.d(TAG, "deleteScooter: deleted ${contracts.size} contracts for scooter ${scooter.name}")
                }

                // 4. Удалить транзакции с этим скутером (snapshot first)
                val txs = db.transactionDao().forScooterOnce(scooter.id)
                txs.forEach { trashSvc.snapshotTransaction(it, "Removed with scooter #${scooter.id}") }
                if (txs.isNotEmpty()) {
                    db.transactionDao().deleteByIds(txs.map { it.id })
                    Log.d(TAG, "deleteScooter: deleted ${txs.size} transactions for scooter #${scooter.id}")
                }

                // 5. Закрыть открытые repair orders
                try {
                    db.repairOrderDao().closeOpenForScooter(scooter.id, "Scooter deleted")
                } catch (_: Exception) {}

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
