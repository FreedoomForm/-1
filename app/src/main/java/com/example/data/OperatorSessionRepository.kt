package com.example.data

import android.content.Context

/** Stores only the selected local operator, never authentication secrets. */
class OperatorSessionRepository(context: Context) {
    private val preferences = context.getSharedPreferences("operator_session", Context.MODE_PRIVATE)

    var activeUserId: Long
        get() = preferences.getLong("active_user_id", 1L)
        set(value) = preferences.edit().putLong("active_user_id", value).apply()

    suspend fun currentUser(db: AppDatabase): AppUser =
        db.appUserDao().activeById(activeUserId)
            ?: db.appUserDao().first()
            ?: AppUser(id = 1, displayName = "Owner", role = AppUser.ROLE_OWNER)

    suspend fun requirePermission(db: AppDatabase, permission: String): AppUser {
        val user = currentUser(db)
        check(AccessPolicy.can(user.role, permission)) {
            "${user.role} is not permitted to perform $permission"
        }
        return user
    }
}
