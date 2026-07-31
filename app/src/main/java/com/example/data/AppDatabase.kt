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
        CardTransaction::class
    ],
    version = 34,
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
         * Migration 15 → 34: no-op.
         *
         * Схема БД между v15 и v34 не изменилась — эти две версии идентичны
         * по структуре таблиц. Этот migration существует только для того,
         * чтобы Room не упал с `fallbackToDestructiveMigration` для пользователей,
         * которые были на v15 (коммит 2a862c9) и обновляются до v1.2.135+.
         *
         * ВАЖНО: v34 — это не «новая схема», а просто номер версии, выбранный
         * выше v33 (последнего релиза с batches), чтобы Room воспринял переход
         * с v33 как upgrade, а не downgrade.
         */
        private val MIGRATION_15_34 = object : Migration(15, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No-op: схема v15 уже соответствует v34 (entity definitions
                // не менялись между 2a862c9 и этим коммитом).
            }
        }

        /**
         * Migration 33 → 34: конвертирует БД из v33 (последний релиз с batches,
         * 19 таблиц + extra колонки) в v34 (7 таблиц, как в 2a862c9).
         *
         * Что делает эта миграция:
         *   1. Удаляет 13 таблиц, которых больше нет в коде:
         *      app_users, audit_events, business_operations, deleted_items,
         *      handover_acts, legacy_money_amounts, payment_allocations,
         *      rent_periods, repair_orders, sms_deliveries,
         *      timeline_branches, timeline_events, timeline_snapshots.
         *
         *   2. Удаляет extra-колонки, добавленные в v16-v33 migrations:
         *      • virtual_cards.isArchived (added в v17)
         *      • scooters.lifecycleStatus, lastServiceAt, nextServiceAt (added в v21)
         *      • scooters.mileageKm (added в v25)
         *
         *   SQLite < 3.35 (Android API < 31) не поддерживает ALTER TABLE DROP COLUMN,
         *   поэтому используем стандартный паттерн CREATE-INSERT-DROP-RENAME.
         *
         * После миграции все 7 таблиц (renters, scooters, transactions,
         * contract_history, virtual_cards, card_transactions, notification_history)
         * имеют ровно те колонки, которые описаны в entity definitions v15/v34 —
         * Room проходит schema validation без ошибок.
         *
         * Данные в 7 основных таблицах (арендаторы, скутеры, контракты,
         * транзакции, карты, карточные транзакции, уведомления) сохраняются
         * полностью — мы только отбрасываем «лишние» колонки и таблицы,
         * которые v34-код всё равно не использует.
         */
        private val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ── 1. Удаляем 13 extra-таблиц ──
                val extraTables = listOf(
                    "timeline_snapshots",
                    "timeline_events",
                    "timeline_branches",
                    "deleted_items",
                    "legacy_money_amounts",
                    "repair_orders",
                    "handover_acts",
                    "sms_deliveries",
                    "app_users",
                    "payment_allocations",
                    "rent_periods",
                    "audit_events",
                    "business_operations"
                )
                extraTables.forEach { t ->
                    db.execSQL("DROP TABLE IF EXISTS `$t`")
                }

                // ── 2. Удаляем extra-колонки из virtual_cards ──
                // В v17 добавилась колонка `isArchived` (INTEGER NOT NULL DEFAULT 0).
                // В v34 entity её не имеет — отбрасываем через CREATE-INSERT-DROP-RENAME.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `virtual_cards_new` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `name` TEXT NOT NULL,
                        `balance` REAL NOT NULL,
                        `colorHex` TEXT NOT NULL,
                        `info` TEXT,
                        `isDefault` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL DEFAULT 'REGULAR'
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO `virtual_cards_new` (id, name, balance, colorHex, info, isDefault, createdAt, kind)
                    SELECT id, name, balance, colorHex, info, isDefault, createdAt, kind FROM `virtual_cards`
                """.trimIndent())
                db.execSQL("DROP TABLE `virtual_cards`")
                db.execSQL("ALTER TABLE `virtual_cards_new` RENAME TO `virtual_cards`")

                // ── 3. Удаляем extra-колонки из scooters ──
                // В v21 добавлены: lifecycleStatus (TEXT NOT NULL DEFAULT 'AVAILABLE'),
                //                  lastServiceAt (INTEGER),
                //                  nextServiceAt (INTEGER).
                // В v25 добавлен: mileageKm (INTEGER NOT NULL DEFAULT 0).
                // В v34 entity этих полей нет — отбрасываем.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `scooters_new` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `name` TEXT NOT NULL,
                        `documentedNumber` TEXT,
                        `vinNumber` TEXT NOT NULL,
                        `engineNumber` TEXT NOT NULL,
                        `scooterSerialNumber` TEXT NOT NULL,
                        `batteryId1` TEXT NOT NULL,
                        `batteryId2` TEXT NOT NULL,
                        `additionalInfo` TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO `scooters_new` (id, name, documentedNumber, vinNumber, engineNumber, scooterSerialNumber, batteryId1, batteryId2, additionalInfo)
                    SELECT id, name, documentedNumber, vinNumber, engineNumber, scooterSerialNumber, batteryId1, batteryId2, additionalInfo FROM `scooters`
                """.trimIndent())
                db.execSQL("DROP TABLE `scooters`")
                db.execSQL("ALTER TABLE `scooters_new` RENAME TO `scooters`")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "scooter_rent_db"
                )
                    .addMigrations(
                        MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
                        MIGRATION_15_34, MIGRATION_33_34
                    )
                    // На случай если кто-то перескакивает через несколько версий
                    // (например, был на v16-v32, для которых нет явной миграции
                    // в эту сборку) — лучше потерять локальные данные, чем
                    // крашнуться при старте. Пользователь сможет восстановить
                    // данные из .xlsx-бэкапа через BackupManager.importFromExcel().
                    .fallbackToDestructiveMigration(true)
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
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
