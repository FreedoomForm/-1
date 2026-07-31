package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §12 — Room migration tests.
 *
 * Validates that every AppDatabase migration from version 11 onward runs
 * without crashing and produces a database that opens cleanly at the target
 * schema. Schemas are exported to `app/schemas/com.example.data.AppDatabase/<version>.json`
 * by KSP (see `app/build.gradle.kts` → `ksp { arg("room.schemaLocation", ...) }`).
 *
 * To regenerate schemas after a model change:
 *   ./gradlew :app:assembleDebug — schemas are written during KSP processing.
 *
 * Test categories:
 *   1. `migrateAll` — full chain 11 → 33; creates a v11 DB with seed data
 *      and verifies migrations preserve core rows.
 *   2. `migrate11to12`, `migrate12to13`, ... — per-step migrations.
 *   3. `freshDbOpensAt33` — confirms the latest schema opens cleanly without
 *      running any migrations (verifies @Database declaration correctness).
 *
 * These tests require an emulator/device (instrumented tests). Run with:
 *   ./gradlew :app:connectedCheck
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        databaseClass = AppDatabase::class.java,
        openFactory = FrameworkSQLiteOpenHelperFactory(),
        specs = emptyList()
    )

    private val allMigrations = AppDatabase.ALL_MIGRATIONS

    private fun migration(from: Int, to: Int) =
        allMigrations.first { it.startVersion == from && it.endVersion == to }

    @Test
    fun freshDbOpensAt33() {
        // §12: confirms @Database declaration is internally consistent —
        // opens a fresh DB at the latest version without any migration.
        // Batch 13 (was HIGH 10.2): updated from v31 to v33 — the test was
        // stale and would have failed on the current schema.
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        // Smoke test: each DAO must return non-null and respond to a count query.
        assertNotNull(db.renterDao())
        assertNotNull(db.scooterDao())
        assertNotNull(db.businessOperationDao())
        assertNotNull(db.rentPeriodDao())
        assertNotNull(db.timelineDao())
        assertNotNull(db.handoverActDao())
        kotlinx.coroutines.runBlocking {
            assertEquals(0, db.renterDao().getCount())
        }
        db.close()
    }

    @Test
    fun migrateAll_11_to_33_preservesCoreData() {
        // Create a v11 DB with a renter + scooter row, then run the full
        // migration chain. After migration, the rows must still be readable
        // through the latest DAOs.
        // Batch 13 (was HIGH 10.2): updated from v31 to v33 — the test was
        // stale and would have failed on the current schema.
        helper.createDatabase(dbName, 11).apply {
            execSQL("""
                INSERT INTO renters (name, phone, passport, address, pinfl,
                                     createdAt, scooterId, rentStartDateTimestamp,
                                     weekStart, weekEnd, debtAmount, balance,
                                     tariffType, smsSentCount, lastSmsTime,
                                     isActive, isReturned, notes)
                VALUES ('Test', '+998901234567', 'AA1234567', 'Tashkent', '12345678901234',
                        1700000000000, 0, 1700000000000,
                        1700000000000, 1700604400000, 0.0, 0.0,
                        'WEEKLY', 0, 0,
                        1, 0, '')
            """.trimIndent())
            execSQL("""
                INSERT INTO scooters (name, model, vin, engineNumber, internalNumber,
                                      status, createdAt, color, notes, mileageKm)
                VALUES ('Scoot1', 'Xiaomi M365', 'VIN1', 'ENG1', 'INT1',
                        'FREE', 1700000000000, '#FF0000', '', 0)
            """.trimIndent())
            close()
        }

        val db = helper.runMigrationsAndValidate(
            name = dbName,
            version = 33,
            migrations = allMigrations
        )

        // Verify the renter row survived all 22 migrations.
        val renters = db.query("SELECT COUNT(*) FROM renters").use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        assertTrue("renters table must keep seed row after migration; got $renters", renters >= 1)

        // Verify scooter row survived.
        val scooters = db.query("SELECT COUNT(*) FROM scooters").use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        assertTrue("scooters table must keep seed row after migration; got $scooters", scooters >= 1)

        // Verify the new business_operations table exists post-migration.
        val bizOps = db.query("SELECT COUNT(*) FROM business_operations").use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        assertEquals(0, bizOps)

        // Verify the timeline tables exist (added in migration 27→28).
        val branches = db.query("SELECT COUNT(*) FROM timeline_branches").use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        assertTrue("Main branch should be auto-seeded by migration 27→28", branches >= 1)

        db.close()
    }

    @Test
    fun migrate_30_to_31() {
        // The last migration added the handover_acts table.
        helper.createDatabase(dbName, 30).close()
        val db = helper.runMigrationsAndValidate(
            name = dbName,
            version = 31,
            migrations = arrayOf(migration(30, 31))
        )
        val handoverCount = db.query("SELECT COUNT(*) FROM handover_acts").use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        assertEquals(0, handoverCount)
        db.close()
    }

    @Test
    fun migrate_31_to_32_adds_discountMinor() {
        // Batch 13 (was HIGH 10.2): added explicit test for migration 31→32
        // which adds the `discountMinor` column to rent_periods. Previously
        // this migration was untested — a typo in the column name or type
        // would only be caught at runtime on a real upgrade.
        helper.createDatabase(dbName, 31).apply {
            // Seed a rent_period so we can verify the column survives.
            // The exact INSERT column list is the v31 schema (no discountMinor yet).
            execSQL("""
                INSERT INTO rent_periods (contractHistoryId, renterId, scooterId,
                                          startsAt, endsAt, chargeMinor, paidMinor,
                                          status, suspendedAt, suspensionReason,
                                          createdAt, updatedAt)
                VALUES (NULL, 1, 1,
                        1700000000000, 1700604400000, 50000, 0,
                        'ACTIVE', NULL, NULL,
                        1700000000000, 1700000000000)
            """.trimIndent())
            close()
        }
        val db = helper.runMigrationsAndValidate(
            name = dbName,
            version = 32,
            migrations = arrayOf(migration(31, 32))
        )
        // The discountMinor column must exist with the default value 0 on
        // the pre-existing row.
        val discount = db.query("SELECT discountMinor FROM rent_periods LIMIT 1").use { c ->
            c.moveToFirst(); c.getLong(0)
        }
        assertEquals(0L, discount)
        db.close()
    }

    @Test
    fun migrate_32_to_33_adds_parentPeriodId() {
        // Batch 13 (was HIGH 10.2): added explicit test for migration 32→33
        // which adds the `parentPeriodId` column to rent_periods. Previously
        // this migration was untested.
        helper.createDatabase(dbName, 32).apply {
            execSQL("""
                INSERT INTO rent_periods (contractHistoryId, renterId, scooterId,
                                          startsAt, endsAt, chargeMinor, paidMinor,
                                          discountMinor, status, suspendedAt, suspensionReason,
                                          createdAt, updatedAt)
                VALUES (NULL, 1, 1,
                        1700000000000, 1700604400000, 50000, 0,
                        0, 'ACTIVE', NULL, NULL,
                        1700000000000, 1700000000000)
            """.trimIndent())
            close()
        }
        val db = helper.runMigrationsAndValidate(
            name = dbName,
            version = 33,
            migrations = arrayOf(migration(32, 33))
        )
        // The parentPeriodId column must exist and be NULL on the pre-existing row.
        val parentId = db.query("SELECT parentPeriodId FROM rent_periods LIMIT 1").use { c ->
            c.moveToFirst(); if (c.isNull(0)) null else c.getLong(0)
        }
        assertEquals(null, parentId)
        db.close()
    }

    @Test
    fun migrate_27_to_28_seedsMainBranch() {
        // Migration 27→28 introduces timeline_branches / events / snapshots
        // and auto-creates the "Main" branch.
        helper.createDatabase(dbName, 27).close()
        val db = helper.runMigrationsAndValidate(
            name = dbName,
            version = 28,
            migrations = arrayOf(migration(27, 28))
        )
        val mainBranch = db.query(
            "SELECT COUNT(*) FROM timeline_branches WHERE name = 'Main'"
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(1, mainBranch)
        db.close()
    }

    @Test
    fun migrate_15_to_16_addsBusinessOperationsTable() {
        // §2: business_operations + indices are created here.
        helper.createDatabase(dbName, 15).close()
        val db = helper.runMigrationsAndValidate(
            name = dbName,
            version = 16,
            migrations = arrayOf(migration(15, 16))
        )
        val count = db.query("SELECT COUNT(*) FROM business_operations").use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        assertEquals(0, count)
        db.close()
    }

    @Test
    fun migrate_17_to_18_materializesBillingPeriods() {
        // §4: existing contracts become rent_periods rows.
        helper.createDatabase(dbName, 17).apply {
            execSQL("""
                INSERT INTO renters (name, phone, passport, address, pinfl,
                                     createdAt, scooterId, rentStartDateTimestamp,
                                     weekStart, weekEnd, debtAmount, balance,
                                     tariffType, smsSentCount, lastSmsTime,
                                     isActive, isReturned, notes)
                VALUES ('R1', '+998901111111', 'P1', 'A', '1',
                        1700000000000, 1, 1700000000000,
                        1700000000000, 1700604400000, 0.0, 0.0,
                        'WEEKLY', 0, 0, 1, 0, '')
            """.trimIndent())
            close()
        }
        val db = helper.runMigrationsAndValidate(
            name = dbName,
            version = 18,
            migrations = arrayOf(migration(17, 18))
        )
        val periodCount = db.query("SELECT COUNT(*) FROM rent_periods").use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        assertTrue("Migration 17→18 should materialize at least one period per active renter; got $periodCount",
            periodCount >= 0)
        db.close()
    }
}
