package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UserRole { NONE, ADMIN, VIEWER }

class LoginViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsRepository(application)
    private val _role = MutableStateFlow(
        settings.currentRole?.let { runCatching { UserRole.valueOf(it) }.getOrNull() }
            ?: UserRole.NONE
    )
    val role: StateFlow<UserRole> = _role.asStateFlow()

    fun login(password: String): UserRole {
        val role = when (password) {
            settings.adminPassword -> UserRole.ADMIN
            settings.viewerPassword -> UserRole.VIEWER
            else -> UserRole.NONE
        }
        if (role != UserRole.NONE) {
            settings.currentRole = role.name
            _role.value = role
        }
        return role
    }

    fun logout() {
        settings.currentRole = null
        _role.value = UserRole.NONE
    }

    fun isAdmin(): Boolean = _role.value == UserRole.ADMIN
    fun isViewer(): Boolean = _role.value == UserRole.VIEWER
}
