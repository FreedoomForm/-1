package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.AppUser
import com.example.data.OperatorSessionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Local operator management and session switching. */
class AppUserViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val session = OperatorSessionRepository(application)

    val users: StateFlow<List<AppUser>> = db.appUserDao().activeUsers().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )
    private val _activeUserId = MutableStateFlow(session.activeUserId)
    val activeUserId: StateFlow<Long> = _activeUserId

    fun switchUser(id: Long) {
        viewModelScope.launch {
            if (db.appUserDao().activeById(id) != null) {
                session.activeUserId = id
                _activeUserId.value = id
            }
        }
    }

    fun addUser(name: String, role: String) {
        viewModelScope.launch {
            val operator = session.requirePermission(db, com.example.data.AccessPolicy.USER_MANAGE)
            if (operator.role == AppUser.ROLE_OWNER && name.trim().isNotEmpty() && role in setOf(
                    AppUser.ROLE_OWNER, AppUser.ROLE_MANAGER, AppUser.ROLE_CASHIER, AppUser.ROLE_VIEWER
                )) {
                db.appUserDao().insert(AppUser(displayName = name.trim(), role = role))
            }
        }
    }
}
