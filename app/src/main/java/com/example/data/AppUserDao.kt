package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppUserDao {
    @Query("SELECT * FROM app_users WHERE isActive = 1 ORDER BY role ASC, displayName ASC")
    fun activeUsers(): Flow<List<AppUser>>

    @Query("SELECT * FROM app_users WHERE id = :id AND isActive = 1 LIMIT 1")
    suspend fun activeById(id: Long): AppUser?

    @Query("SELECT * FROM app_users ORDER BY id ASC LIMIT 1")
    suspend fun first(): AppUser?

    @Query("SELECT * FROM app_users ORDER BY id ASC")
    suspend fun getAllOnce(): List<AppUser>

    @Query("DELETE FROM app_users")
    suspend fun clear()

    @Insert
    suspend fun insert(user: AppUser): Long

    @Update
    suspend fun update(user: AppUser)
}

/** Central permission matrix. UI is never the authority for access checks. */
object AccessPolicy {
    const val PAYMENT_ACCEPT = "PAYMENT_ACCEPT"
    const val FINANCE_REVERSE = "FINANCE_REVERSE"
    const val CONTRACT_TERMINATE = "CONTRACT_TERMINATE"
    const val REPORT_VIEW = "REPORT_VIEW"
    const val USER_MANAGE = "USER_MANAGE"

    fun can(role: String, permission: String): Boolean = when (role) {
        AppUser.ROLE_OWNER -> true
        AppUser.ROLE_MANAGER -> permission in setOf(PAYMENT_ACCEPT, CONTRACT_TERMINATE, REPORT_VIEW)
        AppUser.ROLE_CASHIER -> permission in setOf(PAYMENT_ACCEPT, REPORT_VIEW)
        AppUser.ROLE_VIEWER -> permission == REPORT_VIEW
        else -> false
    }
}
