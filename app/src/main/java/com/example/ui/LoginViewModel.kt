package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.remote.ApiClient
import com.example.data.remote.LoginRequest
import com.example.data.remote.LoginResponse
import com.example.data.remote.SyncManager
import com.example.data.remote.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class UserRole { NONE, ADMIN, VIEWER }

sealed class LoginState {
    object Idle : LoginState()
    object Loading : LoginState()
    data class Error(val message: String) : LoginState()
    data class Success(val role: UserRole) : LoginState()
}

/**
 * Логин через backend API. Сохраняет JWT в EncryptedSharedPreferences.
 * После успешного логина делает pullAll() — Room заполняется данными с сервера.
 */
class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenStore = TokenStore(application)
    private val _state = MutableStateFlow<LoginState>(
        if (tokenStore.isLoggedIn()) {
            LoginState.Success(parseRole(tokenStore.role))
        } else LoginState.Idle
    )
    val state: StateFlow<LoginState> = _state.asStateFlow()

    private val _role = MutableStateFlow(parseRole(tokenStore.role))
    val role: StateFlow<UserRole> = _role.asStateFlow()

    fun login(serverUrl: String, email: String, password: String) {
        if (_state.value is LoginState.Loading) return

        _state.value = LoginState.Loading
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Сохраняем URL и сбрасываем кэш Retrofit.
                    tokenStore.serverUrl = serverUrl.trim().trimEnd('/')
                    tokenStore.userEmail = email
                    ApiClient.reset()

                    val api = ApiClient.getService(getApplication())
                    val response = api.login(LoginRequest(email.trim(), password))

                    tokenStore.jwt = response.token
                    tokenStore.role = response.user.role

                    // Полный pull с сервера в Room.
                    val sync = SyncManager(getApplication(), api)
                    val pulled = sync.pullAll()

                    if (!pulled) {
                        Log.w(TAG, "Pull failed after login — Room is empty")
                    }

                    response
                }
            }

            result.fold(
                onSuccess = { response: LoginResponse ->
                    _role.value = parseRole(response.user.role)
                    _state.value = LoginState.Success(parseRole(response.user.role))
                },
                onFailure = { err ->
                    Log.e(TAG, "Login failed", err)
                    val msg = err.message?.let {
                        when {
                            it.contains("401") || it.contains("Unauthorized") ->
                                "Login yoki parol noto'g'ri"
                            it.contains("Failed to connect") || it.contains("timeout") ->
                                "Serverga ulanib bo'lmadi. URL va internetni tekshiring."
                            else -> "Xatolik: $it"
                        }
                    } ?: "Noma'lum xatolik"
                    _state.value = LoginState.Error(msg)
                }
            )
        }
    }

    fun register(serverUrl: String, email: String, password: String) {
        if (_state.value is LoginState.Loading) return
        _state.value = LoginState.Loading
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    tokenStore.serverUrl = serverUrl.trim().trimEnd('/')
                    tokenStore.userEmail = email
                    ApiClient.reset()

                    val api = ApiClient.getService(getApplication())
                    api.register(
                        com.example.data.remote.RegisterRequest(
                            email.trim(), password
                        )
                    )
                }
            }
            result.fold(
                onSuccess = { response ->
                    tokenStore.jwt = response.token
                    tokenStore.role = response.user.role

                    val sync = SyncManager(
                        getApplication(),
                        ApiClient.getService(getApplication())
                    )
                    sync.pullAll()

                    _role.value = parseRole(response.user.role)
                    _state.value = LoginState.Success(parseRole(response.user.role))
                },
                onFailure = { err ->
                    Log.e(TAG, "Register failed", err)
                    _state.value = LoginState.Error(
                        err.message?.take(120) ?: "Register xatoligi"
                    )
                }
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Очищаем и Room, чтобы другой пользователь не видел данные.
                AppDatabase.getDatabase(getApplication()).runInTransaction {
                    AppDatabase.getDatabase(getApplication()).renterDao().deleteAll()
                    AppDatabase.getDatabase(getApplication()).scooterDao().deleteAll()
                    AppDatabase.getDatabase(getApplication())
                        .contractHistoryDao().deleteAll()
                    AppDatabase.getDatabase(getApplication())
                        .notificationHistoryDao().deleteAll()
                }
                tokenStore.clear()
                ApiClient.reset()
            }
            _role.value = UserRole.NONE
            _state.value = LoginState.Idle
        }
    }

    fun resetError() {
        if (_state.value is LoginState.Error) _state.value = LoginState.Idle
    }

    private fun parseRole(s: String?): UserRole = when (s) {
        "admin" -> UserRole.ADMIN
        "viewer" -> UserRole.VIEWER
        else -> UserRole.NONE
    }

    companion object {
        private const val TAG = "LoginViewModel"
    }
}
