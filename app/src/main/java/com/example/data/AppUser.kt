package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Local operator account. Password/authentication is deliberately kept outside
 * this entity so backups never contain a secret. */
@Entity(tableName = "app_users")
data class AppUser(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val role: String = ROLE_OWNER,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val ROLE_OWNER = "OWNER"
        const val ROLE_MANAGER = "MANAGER"
        const val ROLE_CASHIER = "CASHIER"
        const val ROLE_VIEWER = "VIEWER"
    }
}
