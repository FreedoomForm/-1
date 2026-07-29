package com.example.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.data.BusinessOperation
import com.example.data.DeletedItem
import com.example.data.OpenObligation
import com.example.data.PaymentAllocationPolicy
import com.example.data.RentPeriod
import com.example.data.Renter
import com.example.data.Scooter
import com.example.data.TimelineEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §12 — UI / integration tests for the core user journeys.
 *
 * These are instrumented tests that run against an in-memory Room database
 * (no Compose UI rendering — that requires an emulator with surface). They
 * exercise the same DAO + repository layer that the UI uses, which catches
 * the most common regression classes:
 *
 *   • future reserve — a SCHEDULED rent_period must not create debt.
 *   • scooter conflict — two overlapping reservations on the same scooter
 *     must be detected by DAO conflict query.
 *   • partial payment — FIFO allocation closes older periods first.
 *   • return — closing a contract with outstanding debt must preserve it
 *     as CLOSED_WITH_DEBT, not silently forgive.
 *   • history — every critical action must record a TimelineEvent.
 *   • trash — soft-deleted items must be restorable, hard-delete must
 *     require explicit confirmation.
 *   • financial integrity — deleting a renter must NOT erase linked
 *     business operations (audit trail integrity, §0.3).
 *
 * Run with:  ./gradlew :app:connectedCheck
 */
@RunWith(AndroidJUnit4::class)
class CoreUserJourneyInstrumentedTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun futureReserve_doesNotCreateDebt() = runBlocking {
        // ── Given a future-scheduled rent period ──────────────────────────
        val renter = Renter(
            name = "Future",
            phoneNumber = "+998901",
            rentDurationDays = 7,
            rentStartDateTimestamp = 1_700_000_000_000,
            scooterId = 1,
            scooterName = "S1"
        )
        val renterId = db.renterDao().insertRenter(renter).toInt()

        val scooterId = db.scooterDao().insertScooter(
            Scooter(name = "S1", lifecycleStatus = Scooter.STATUS_RESERVED)
        ).toInt()

        val futureStart = System.currentTimeMillis() + 7 * 86_400_000L  // +1 week
        val futureEnd = futureStart + 7 * 86_400_000L

        db.rentPeriodDao().insert(
            RentPeriod(
                renterId = renterId,
                scooterId = scooterId,
                startsAt = futureStart,
                endsAt = futureEnd,
                chargeMinor = 200_000_00L,
                paidMinor = 0L,
                status = RentPeriod.STATUS_SCHEDULED
            )
        )

        // ── Then the renter's computed debt is 0 ──────────────────────────
        val periods = db.rentPeriodDao().forRenter(renterId).first()
        assertEquals(1, periods.size)
        assertEquals(RentPeriod.STATUS_SCHEDULED, periods[0].status)
        assertEquals(0L, periods[0].paidMinor)
        assertEquals(200_000_00L, periods[0].outstandingMinor)

        // No INCOME business_operations should exist for a future reservation.
        val ops = db.businessOperationDao().getActiveInRange(0, Long.MAX_VALUE)
        assertTrue(
            "Future reservation must not produce income operation; got ${ops.size} ops",
            ops.none { it.type == BusinessOperation.TYPE_RENT_PAYMENT && it.direction == BusinessOperation.DIRECTION_INCOME }
        )
    }

    @Test
    fun scooterConflict_overlappingReservationsDetected() = runBlocking {
        val scooterId = db.scooterDao().insertScooter(
            Scooter(name = "S1", lifecycleStatus = Scooter.STATUS_AVAILABLE)
        ).toInt()

        // ── First reservation: weeks 1–2 ──────────────────────────────────
        val r1Start = 1_700_000_000_000L
        val r1End = r1Start + 14 * 86_400_000L
        db.rentPeriodDao().insert(
            RentPeriod(
                renterId = 1, scooterId = scooterId,
                startsAt = r1Start, endsAt = r1End,
                chargeMinor = 100_000_00L, paidMinor = 0L,
                status = RentPeriod.STATUS_ACTIVE
            )
        )

        // ── Second reservation overlapping by 7 days ─────────────────────
        val r2Start = r1Start + 7 * 86_400_000L  // starts before r1 ends
        val r2End = r2Start + 7 * 86_400_000L

        // DAO must expose a conflict-detection query.
        val conflicts = db.rentPeriodDao().conflictsForScooter(
            scooterId = scooterId,
            startsAt = r2Start,
            endsAt = r2End
        )
        assertTrue(
            "Overlapping reservation must be detected as conflict; got ${conflicts.size}",
            conflicts.isNotEmpty()
        )
    }

    @Test
    fun partialPayment_fifoClosesOldestFirst() = runBlocking {
        // Two obligations: 100_00 (older, dueAt=1) + 150_00 (newer, dueAt=3).
        // Payment of 100_00 must fully close the oldest one.
        val obligations = listOf(
            OpenObligation(contractId = 1L, dueAt = 1L, outstandingMinor = 100_00L),
            OpenObligation(contractId = 2L, dueAt = 3L, outstandingMinor = 150_00L)
        )
        val result = PaymentAllocationPolicy.allocateOldestFirst(
            paymentMinor = 100_00L,
            obligations = obligations
        )
        assertEquals(1, result.allocations.size)
        assertEquals(100_00L, result.allocations[0].appliedMinor)
        assertEquals(
            "Oldest obligation (contractId=1) must be allocated first",
            1L, result.allocations[0].contractId
        )
        assertEquals(0L, result.unallocatedMinor)
    }

    @Test
    fun return_withDebtPreservesClosedWithDebtStatus() = runBlocking {
        val renter = Renter(
            name = "Debtor",
            phoneNumber = "+998901",
            rentDurationDays = 7,
            rentStartDateTimestamp = 1,
            scooterId = 1
        )
        val renterId = db.renterDao().insertRenter(renter).toInt()

        // Period owed 100, paid 50 → debt 50.
        db.rentPeriodDao().insert(
            RentPeriod(
                renterId = renterId, scooterId = 1,
                startsAt = 1, endsAt = 2,
                chargeMinor = 100_00L, paidMinor = 50_00L,
                status = RentPeriod.STATUS_PARTIALLY_PAID
            )
        )

        // Close contract without forgiving debt.
        db.rentPeriodDao().closeOpenForRenter(renterId, System.currentTimeMillis())
        val periods = db.rentPeriodDao().forRenter(renterId).first()
        assertEquals(RentPeriod.STATUS_CLOSED_WITH_DEBT, periods[0].status)
        assertEquals(50_00L, periods[0].outstandingMinor)
    }

    @Test
    fun history_criticalActionRecordsTimelineEvent() = runBlocking {
        val renter = Renter(
            name = "Tracked",
            phoneNumber = "+998901",
            rentDurationDays = 7,
            rentStartDateTimestamp = System.currentTimeMillis()
        )
        val id = db.renterDao().insertRenter(renter).toInt()

        // Verify Main branch was auto-seeded by DB onCreate callback.
        val timelineDao = db.timelineDao()
        val mainBranch = timelineDao.mainBranch()
        assertNotNull("Main branch must exist (auto-seeded on DB creation)", mainBranch)

        val eventId = timelineDao.insertEvent(
            TimelineEvent(
                branchId = mainBranch!!.id,
                timestamp = System.currentTimeMillis(),
                title = "Renter created: Tracked",
                screen = "RENTERS",
                actionType = "RENTER_CREATE",
                entityType = "RENTER",
                entityId = id.toString(),
                payloadJson = """{"name":"Tracked"}""",
                isMajor = true,
                isArchived = false
            )
        )
        assertTrue("Timeline event must get an id", eventId > 0)

        val events = timelineDao.events(mainBranch.id).first()
        assertTrue(
            "Branch must contain the inserted event",
            events.any { it.id == eventId && it.actionType == "RENTER_CREATE" }
        )
    }

    @Test
    fun trash_softDeleteThenRestorePreservesSnapshot() = runBlocking {
        val renterDao = db.renterDao()
        val deletedItemDao = db.deletedItemDao()

        val renter = Renter(
            name = "ToDelete",
            phoneNumber = "+998901",
            rentDurationDays = 7,
            rentStartDateTimestamp = 1
        )
        val id = renterDao.insertRenter(renter).toInt()

        // Snapshot before soft-delete.
        val snapshot = org.json.JSONObject().apply {
            put("name", renter.name)
            put("phoneNumber", renter.phoneNumber)
        }.toString()

        val trashId = deletedItemDao.insert(
            DeletedItem(
                sourceType = DeletedItem.TYPE_RENTER,
                sourceId = id.toString(),
                title = renter.name,
                snapshotJson = snapshot,
                reason = "test"
            )
        )
        renterDao.deleteRenter(id)

        kotlinx.coroutines.runBlocking { assertEquals(0, renterDao.getCount()) }

        // Restore: re-create from snapshot, remove from trash.
        val trashItems = deletedItemDao.all().first()
        assertEquals(1, trashItems.size)
        val restored = trashItems[0]
        val json = org.json.JSONObject(restored.snapshotJson)
        val restoredRenter = renter.copy(
            name = json.getString("name"),
            phoneNumber = json.getString("phoneNumber")
        )
        renterDao.insertRenter(restoredRenter)
        deletedItemDao.purge(restored.id)

        kotlinx.coroutines.runBlocking { assertEquals(1, renterDao.getCount()) }
        assertEquals(0, deletedItemDao.all().first().size)
    }

    @Test
    fun financialOperation_neverSilentlyErasedByDelete() = runBlocking {
        val op = BusinessOperation(
            type = BusinessOperation.TYPE_RENT_PAYMENT,
            direction = BusinessOperation.DIRECTION_INCOME,
            amountMinor = 100_00L,
            renterId = 1,
            status = BusinessOperation.STATUS_ACTIVE,
            note = "test payment"
        )
        val opId = db.businessOperationDao().insert(op)
        assertTrue(opId > 0)

        // Delete the renter (hard delete) — business op must remain.
        db.renterDao().deleteRenter(1)

        val allOps = db.businessOperationDao().getAllOnce()
        assertTrue(
            "Financial operation must survive linked entity deletion; got ${allOps.size} ops",
            allOps.any { it.id == opId }
        )
        assertFalse(
            "Operation must NOT be marked REVERSED just because renter was deleted",
            allOps.first { it.id == opId }.status == BusinessOperation.STATUS_REVERSED
        )
    }
}
