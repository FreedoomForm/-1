package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Renter::class,
        Scooter::class,
        NotificationHistoryEntity::class,
        ContractHistoryEntry::class,
        Transaction::class,
        VirtualCard::class,
        CardTransaction::class,
        BusinessOperation::class,
        RentPeriod::class,
        PaymentAllocationEntity::class,
        AuditEvent::class,
        AppUser::class
    ],
    version = 21,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun renterDao(): RenterDao
    abstract fun scooterDao(): ScooterDao
    abstract fun notificationHistoryDao(): NotificationHistoryDao
    abstract fun contractHistoryDao(): ContractHistoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun virtualCardDao(): VirtualCardDao
    abstract fun cardTransactionDao(): CardTransactionDao
    abstract fun businessOperationDao(): BusinessOperationDao
    abstract fun rentPeriodDao(): RentPeriodDao
    abstract fun paymentAllocationDao(): PaymentAllocationDao
    abstract fun auditEventDao(): AuditEventDao
    abstract fun appUserDao(): AppUserDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration 11 → 12: добавляем таблицы virtual_cards и card_transactions
         * без потери существующих данных (арендаторы, скутеры, история, транзакции).
         * Сразу сидируем две системные карты.
         *
         * ВАЖНО: одинарные кавычки внутри SQL-строк экранируем удвоением
         * (to'lovlari -> to''lovlari), иначе SQLite роняет парсер.
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `virtual_cards` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `name` TEXT NOT NULL,
                        `balance` REAL NOT NULL,
                        `colorHex` TEXT NOT NULL,
                        `info` TEXT,
                        `isDefault` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `card_transactions` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `timestamp` INTEGER NOT NULL,
                        `fromCardId` INTEGER NOT NULL,
                        `toCardId` INTEGER NOT NULL,
                        `amount` REAL NOT NULL,
                        `note` TEXT,
                        `type` TEXT NOT NULL
                    )
                """.trimIndent())

                // INSERT OR IGNORE — если карты уже есть (id=1,2), ничего не ломаем.
                db.execSQL("""
                    INSERT OR IGNORE INTO `virtual_cards` (id, name, balance, colorHex, info, isDefault, createdAt)
                    VALUES
                        (1, 'Glavnaya', 0.0, '#FF1565C0', 'Asosiy kassa — contract to''lovlari shu yerga tushadi', 1, strftime('%s','now') * 1000),
                        (2, 'Vtorostepennaya', 0.0, '#FF2E7D32', 'Qo`shimcha karta', 1, strftime('%s','now') * 1000)
                """.trimIndent())
            }
        }

        /**
         * Migration 12 → 13: defensive re-seed системных карт.
         * На случай, если у пользователя уже стоит v12, в которой упал onCreate
         * из-за бага с неэкранированной кавычкой — таблица есть, а карт нет.
         * INSERT OR IGNORE безопасно добавит недостающие карты без потери данных.
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // На случай если таблиц вдруг нет (битое состояние v12) — создаём.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `virtual_cards` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `name` TEXT NOT NULL,
                        `balance` REAL NOT NULL,
                        `colorHex` TEXT NOT NULL,
                        `info` TEXT,
                        `isDefault` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `card_transactions` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `timestamp` INTEGER NOT NULL,
                        `fromCardId` INTEGER NOT NULL,
                        `toCardId` INTEGER NOT NULL,
                        `amount` REAL NOT NULL,
                        `note` TEXT,
                        `type` TEXT NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT OR IGNORE INTO `virtual_cards` (id, name, balance, colorHex, info, isDefault, createdAt)
                    VALUES
                        (1, 'Glavnaya', 0.0, '#FF1565C0', 'Asosiy kassa — contract to''lovlari shu yerga tushadi', 1, strftime('%s','now') * 1000),
                        (2, 'Vtorostepennaya', 0.0, '#FF2E7D32', 'Qo`shimcha karta', 1, strftime('%s','now') * 1000)
                """.trimIndent())
            }
        }

        /**
         * Migration 13 → 14: добавляем колонку `kind` в virtual_cards и сидируем
         * две внешние карты с бесконечным балансом:
         *   • id=3 «Tashqidan» (EXTERNAL_IN) — приём денег «из вне» (банк, нал).
         *   • id=4 «Tashqiga»  (EXTERNAL_OUT) — вывод денег «вне» (снятие, налоги).
         *
         * У обеих карт isDefault=1 (нельзя удалить), balance=0 и не меняется
         * при переводах — логика в VirtualCardRepository.transfer пропускает
         * adjustBalance для внешних карт.
         *
         * При переводе с участием внешней карты пользователь обязан указать
         * описание (note) — валидация в FinansiViewModel.transfer.
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1) Добавляем колонку kind. У всех существующих карт значение 'REGULAR'.
                db.execSQL(
                    "ALTER TABLE `virtual_cards` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'REGULAR'"
                )
                // 2) Сидируем две внешние карты. INSERT OR IGNORE безопасен, если
                //    вдруг id=3/4 уже заняты пользовательскими картами — тогда внешние
                //    карты не встанут (редкий случай; на практике id=3,4 свободны).
                db.execSQL("""
                    INSERT OR IGNORE INTO `virtual_cards`
                        (id, name, balance, colorHex, info, isDefault, createdAt, kind)
                    VALUES
                        (3, 'Tashqidan', 0.0, '#FF00838F', 'Tashqidan kirgan pul (bank, naqd va h.k.)', 1, strftime('%s','now') * 1000, 'EXTERNAL_IN'),
                        (4, 'Tashqiga',  0.0, '#FFC62828', 'Tashqiga chiqarilgan pul (yechib olish, to''lovlar)', 1, strftime('%s','now') * 1000, 'EXTERNAL_OUT')
                """.trimIndent())
            }
        }

        /**
         * Migration 14 → 15: добавляем колонку `contractId` (Int?) в card_transactions.
         *
         * Это «мостик» между CardTransaction и ContractHistoryEntry. Колонка
         * заполняется только для записей type=CONTRACT_INCOME (когда деньги от
         * оплаты контракта падают на главную карту). Для старых CONTRACT_INCOME
         * записей contractId остаётся null — мы не можем ретроактивно связать
         * их с контрактами, потому что раньше поле вообще отсутствовало.
         *
         * Для новых CONTRACT_INCOME записей (создаваемых после этой миграции)
         * VirtualCardRepository.depositContractIncome(contractId, ...) проставит
         * поле явно. Это позволяет:
         *   1. Каскадно удалять CardTransaction при удалении контракта.
         *   2. Реверсить баланс главной карты при отмене оплаты контракта.
         *   3. Показывать связь в UI (если понадобится).
         *
         * ALTER TABLE ... ADD COLUMN с NULL-значением по умолчанию — стандартный
         * способ добавить nullable-колонку в SQLite, не пересоздавая таблицу.
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // contractId — nullable, без DEFAULT. У всех существующих строк
                // значение станет NULL, что корректно (старые записи не привязаны).
                db.execSQL(
                    "ALTER TABLE `card_transactions` ADD COLUMN `contractId` INTEGER"
                )
            }
        }

        /**
         * Migration 15 → 16: immutable universal business ledger. Existing
         * card movements are imported once. Contract income becomes revenue;
         * transfers remain neutral and therefore cannot inflate profit.
         */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `business_operations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `occurredAt` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `amountMinor` INTEGER NOT NULL,
                        `renterId` INTEGER,
                        `scooterId` INTEGER,
                        `contractId` INTEGER,
                        `fromCardId` INTEGER,
                        `toCardId` INTEGER,
                        `cardTransactionId` INTEGER,
                        `legacyTransactionId` INTEGER,
                        `note` TEXT,
                        `status` TEXT NOT NULL,
                        `reversesOperationId` INTEGER,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_business_operations_occurredAt` ON `business_operations` (`occurredAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_business_operations_renterId` ON `business_operations` (`renterId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_business_operations_contractId` ON `business_operations` (`contractId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_business_operations_cardTransactionId` ON `business_operations` (`cardTransactionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_business_operations_status` ON `business_operations` (`status`)")
                // Legacy card transactions are the most reliable historical
                // cash source. ROUND converts REAL sums to exact tийин units.
                db.execSQL("""
                    INSERT INTO business_operations
                    (occurredAt,type,direction,amountMinor,contractId,fromCardId,toCardId,cardTransactionId,note,status,createdAt)
                    SELECT timestamp,
                           CASE WHEN type = 'CONTRACT_INCOME' THEN 'RENT_PAYMENT' ELSE 'TRANSFER' END,
                           CASE WHEN type = 'CONTRACT_INCOME' THEN 'INCOME' ELSE 'TRANSFER' END,
                           CAST(ROUND(ABS(amount) * 100.0) AS INTEGER),
                           contractId, fromCardId, toCardId, id, note, 'ACTIVE', timestamp
                    FROM card_transactions
                    WHERE amount <> 0
                """.trimIndent())
            }
        }

        /** Migration 16 → 17: retain closed cards for audit instead of deleting them. */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `virtual_cards` ADD COLUMN `isArchived` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Migration 17 → 18: materialise billable rental periods and payment
         * allocations. Existing contract history is copied without deleting it.
         */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `rent_periods` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `contractHistoryId` INTEGER,
                        `renterId` INTEGER NOT NULL,
                        `scooterId` INTEGER,
                        `startsAt` INTEGER NOT NULL,
                        `endsAt` INTEGER NOT NULL,
                        `chargeMinor` INTEGER NOT NULL,
                        `paidMinor` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_rent_periods_contractHistoryId` ON `rent_periods` (`contractHistoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_rent_periods_renterId_status` ON `rent_periods` (`renterId`, `status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_rent_periods_scooterId_startsAt_endsAt` ON `rent_periods` (`scooterId`, `startsAt`, `endsAt`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `payment_allocations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `operationId` INTEGER NOT NULL,
                        `rentPeriodId` INTEGER NOT NULL,
                        `amountMinor` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_allocations_operationId` ON `payment_allocations` (`operationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_allocations_rentPeriodId` ON `payment_allocations` (`rentPeriodId`)")
                // Snapshot the legacy contracts. When a period is marked paid,
                // it is fully settled; old partial payments did not have a
                // durable allocation model and therefore remain auditable only
                // in the legacy history until manually reconciled.
                db.execSQL("""
                    INSERT OR IGNORE INTO rent_periods
                    (contractHistoryId,renterId,scooterId,startsAt,endsAt,chargeMinor,paidMinor,status,createdAt,updatedAt)
                    SELECT h.id, h.renterId, r.scooterId,
                           COALESCE(h.weekStart, h.timestamp),
                           COALESCE(h.weekEnd, h.timestamp + 604800000),
                           CAST(ROUND(ABS(h.amount) * 100.0) AS INTEGER),
                           CASE WHEN h.isPaid = 1 THEN CAST(ROUND(ABS(h.amount) * 100.0) AS INTEGER) ELSE 0 END,
                           CASE
                             WHEN h.isPaid = 1 THEN 'PAID'
                             WHEN COALESCE(h.weekStart, h.timestamp) > strftime('%s','now') * 1000 THEN 'SCHEDULED'
                             WHEN COALESCE(h.weekEnd, h.timestamp + 604800000) <= strftime('%s','now') * 1000 THEN 'OVERDUE'
                             ELSE 'ACTIVE'
                           END,
                           h.timestamp, h.timestamp
                    FROM contract_history h
                    LEFT JOIN renters r ON r.id = h.renterId
                    WHERE h.type IN ('CREATED','AUTO_RENEW')
                """.trimIndent())
            }
        }

        /** Migration 18 → 19: immutable local audit trail. */
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `audit_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `occurredAt` INTEGER NOT NULL,
                        `actor` TEXT NOT NULL,
                        `action` TEXT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `entityId` TEXT NOT NULL,
                        `reason` TEXT,
                        `beforeSnapshot` TEXT,
                        `afterSnapshot` TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_events_occurredAt` ON `audit_events` (`occurredAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_events_entityType_entityId` ON `audit_events` (`entityType`, `entityId`)")
            }
        }

        /** Migration 19 → 20: local roles; every existing installation gets an owner. */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `app_users` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `isActive` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("INSERT INTO app_users (id,displayName,role,isActive,createdAt) VALUES (1,'Owner','OWNER',1,strftime('%s','now')*1000)")
            }
        }

        /** Migration 20 → 21: explicit operational lifecycle and service dates for scooters. */
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `scooters` ADD COLUMN `lifecycleStatus` TEXT NOT NULL DEFAULT 'AVAILABLE'")
                db.execSQL("ALTER TABLE `scooters` ADD COLUMN `lastServiceAt` INTEGER")
                db.execSQL("ALTER TABLE `scooters` ADD COLUMN `nextServiceAt` INTEGER")
                // Existing active rentals must not suddenly appear available.
                db.execSQL("""
                    UPDATE scooters SET lifecycleStatus = 'RENTED'
                    WHERE id IN (SELECT scooterId FROM renters WHERE isReturned = 0 AND scooterId IS NOT NULL)
                """.trimIndent())
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "scooter_rent_db"
                )
                    .addMigrations(MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21)
                    // Production data must never be silently erased on an unknown migration.
                    // Room will fail visibly and the user can restore a backup instead.
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // При первой установке (fresh install) создаём все 4 системные карты:
                            //   1 = Glavnaya, 2 = Vtorostepennaya (обычные, isDefault)
                            //   3 = Tashqidan (EXTERNAL_IN), 4 = Tashqiga (EXTERNAL_OUT)
                            // Одинарные кавычки внутри SQL-строк экранируем удвоением.
                            // INSERT OR IGNORE — на случай если коллбэк вызывается повторно.
                            db.execSQL("""
                                INSERT OR IGNORE INTO `virtual_cards`
                                    (id, name, balance, colorHex, info, isDefault, createdAt, kind)
                                VALUES
                                    (1, 'Glavnaya', 0.0, '#FF1565C0', 'Asosiy kassa — contract to''lovlari shu yerga tushadi', 1, strftime('%s','now') * 1000, 'REGULAR'),
                                    (2, 'Vtorostepennaya', 0.0, '#FF2E7D32', 'Qo`shimcha karta', 1, strftime('%s','now') * 1000, 'REGULAR'),
                                    (3, 'Tashqidan', 0.0, '#FF00838F', 'Tashqidan kirgan pul (bank, naqd va h.k.)', 1, strftime('%s','now') * 1000, 'EXTERNAL_IN'),
                                    (4, 'Tashqiga',  0.0, '#FFC62828', 'Tashqiga chiqarilgan pul (yechib olish, to''lovlar)', 1, strftime('%s','now') * 1000, 'EXTERNAL_OUT')
                            """.trimIndent())
                            db.execSQL("INSERT OR IGNORE INTO app_users (id, displayName, role, isActive, createdAt) VALUES (1, 'Owner', 'OWNER', 1, strftime('%s','now') * 1000)")
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
