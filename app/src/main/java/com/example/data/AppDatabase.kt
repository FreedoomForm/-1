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
        VirtualCard::class,
        CardTransaction::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun renterDao(): RenterDao
    abstract fun scooterDao(): ScooterDao
    abstract fun notificationHistoryDao(): NotificationHistoryDao
    abstract fun contractHistoryDao(): ContractHistoryDao
    abstract fun virtualCardDao(): VirtualCardDao
    abstract fun cardTransactionDao(): CardTransactionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration 5 → 6: добавляем таблицы virtual_cards и card_transactions
         * без потери существующих данных (арендаторы, скутеры, история).
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
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

                // Сидируем две системные карты при первом создании таблицы
                db.execSQL("""
                    INSERT INTO `virtual_cards` (id, name, balance, colorHex, info, isDefault, createdAt)
                    VALUES
                        (1, 'Bosh karta', 0.0, '#FF1565C0', 'Asosiy kassa', 1, strftime('%s','now') * 1000),
                        (2, 'Ikkinchi karta', 0.0, '#FF2E7D32', 'Qo shimcha karta', 1, strftime('%s','now') * 1000)
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
                    .addMigrations(MIGRATION_5_6)
                    // На случай если кто-то перескакивает через несколько версий —
                    // лучше потерять локальные данные, чем крашнуться при старте.
                    .fallbackToDestructiveMigration(true)
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // При первой установке (fresh install) тоже создаём две системные карты
                            db.execSQL("""
                                INSERT INTO `virtual_cards` (id, name, balance, colorHex, info, isDefault, createdAt)
                                VALUES
                                    (1, 'Bosh karta', 0.0, '#FF1565C0', 'Asosiy kassa', 1, strftime('%s','now') * 1000),
                                    (2, 'Ikkinchi karta', 0.0, '#FF2E7D32', 'Qo`shimcha karta', 1, strftime('%s','now') * 1000)
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
