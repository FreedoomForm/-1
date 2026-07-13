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
    version = 15,
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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "scooter_rent_db"
                )
                    .addMigrations(MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
                    // На случай если кто-то перескакивает через несколько версий —
                    // лучше потерять локальные данные, чем крашнуться при старте.
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
