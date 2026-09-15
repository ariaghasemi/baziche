package com.baziche.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baziche.app.R
import com.baziche.app.ui.components.ErrorCard
import com.baziche.app.ui.components.LoadingButton
import com.baziche.core.common.ApiResult
import com.baziche.core.data.repo.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AuthUi(val loading: Boolean = false, val errorCode: String? = null, val errorMsg: String = "", val done: Boolean = false)

class SharedAuthViewModel(private val repo: AuthRepository) : ViewModel() {
    private val _ui = MutableStateFlow(AuthUi())
    val ui: StateFlow<AuthUi> = _ui

    fun login(phone: String, password: String) {
        _ui.value = AuthUi(loading = true)
        viewModelScope.launch {
            when (val r = repo.login(phone, password)) {
                is ApiResult.Success -> _ui.value = AuthUi(done = true)
                is ApiResult.Error -> _ui.value = AuthUi(errorCode = r.code, errorMsg = r.message)
            }
        }
    }

    fun register(phone: String, username: String, password: String) {
        _ui.value = AuthUi(loading = true)
        viewModelScope.launch {
            when (val r = repo.register(phone, username, password)) {
                is ApiResult.Success -> _ui.value = AuthUi(done = true)
                is ApiResult.Error -> _ui.value = AuthUi(errorCode = r.code, errorMsg = r.message)
            }
        }
    }
}

@Composable
fun LoginScreen(vm: SharedAuthViewModel, onLoggedIn: () -> Unit, onGoRegister: () -> Unit) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(ui.done) { if (ui.done) onLoggedIn() }
    var phone by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(phone, { phone = it }, label = { Text(stringResource(R.string.phone)) }, placeholder = { Text(stringResource(R.string.phone_hint)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password)) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
        Spacer(Modifier.height(8.dp))
        ui.errorCode?.let { ErrorCard(it, ui.errorMsg) }
        LoadingButton(stringResource(R.string.login), ui.loading, phone.isNotBlank() && password.length >= 8) {
            vm.login(phone, password)
        }
        TextButton(onClick = onGoRegister) { Text(stringResource(R.string.no_account)) }
    }
}

@Composable
fun RegisterScreen(vm: SharedAuthViewModel, onRegistered: () -> Unit, onGoLogin: () -> Unit) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(ui.done) { if (ui.done) onRegistered() }
    var phone by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val mismatch = confirm.isNotEmpty() && confirm != password
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.register), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(phone, { phone = it }, label = { Text(stringResource(R.string.phone)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(username, { username = it }, label = { Text(stringResource(R.string.username)) }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password)) }, placeholder = { Text(stringResource(R.string.password_hint)) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(confirm, { confirm = it }, label = { Text(stringResource(R.string.confirm_password)) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, isError = mismatch, supportingText = { if (mismatch) Text(stringResource(R.string.passwords_no_match)) })
        Spacer(Modifier.height(8.dp))
        ui.errorCode?.let { ErrorCard(it, ui.errorMsg) }
        LoadingButton(stringResource(R.string.register), ui.loading, phone.isNotBlank() && username.length >= 3 && password.length >= 8 && !mismatch) {
            vm.register(phone, username, password)
        }
        TextButton(onClick = onGoLogin) { Text(stringResource(R.string.have_account)) }
    }
}
