package com.baziche.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baziche.app.R
import com.baziche.app.ui.components.BazicheBadge
import com.baziche.app.ui.components.BazicheCard
import com.baziche.app.ui.components.BazicheLogo
import com.baziche.app.ui.components.BazichePrimaryButton
import com.baziche.app.ui.components.BazicheTextField
import com.baziche.app.ui.components.ErrorCard
import com.baziche.app.ui.theme.BazicheBgRoot
import com.baziche.app.ui.theme.BazicheEmeraldBright
import com.baziche.app.ui.theme.BazicheLime
import com.baziche.app.ui.theme.BazicheTextMuted
import com.baziche.app.ui.theme.BazicheTextPrimary
import com.baziche.app.ui.theme.BazicheTextSecondary
import com.baziche.core.common.ApiResult
import com.baziche.core.data.repo.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AuthUi(
    val loading: Boolean = false,
    val errorCode: String? = null,
    val errorMsg: String = "",
    val done: Boolean = false,
    val codeSent: Boolean = false,
    val cooldownSec: Int = 0,
    val emailVerified: Boolean = false,
    val verificationToken: String? = null,
)

class SharedAuthViewModel(private val repo: AuthRepository) : ViewModel() {
    private val _ui = MutableStateFlow(AuthUi())
    val ui: StateFlow<AuthUi> = _ui

    private var countdownJob: Job? = null

    fun login(identifier: String, password: String) {
        _ui.value = _ui.value.copy(loading = true, errorCode = null, errorMsg = "")
        viewModelScope.launch {
            when (val r = repo.login(identifier, password)) {
                is ApiResult.Success -> _ui.value = _ui.value.copy(loading = false, done = true)
                is ApiResult.Error -> _ui.value = _ui.value.copy(loading = false, errorCode = r.code, errorMsg = r.message)
            }
        }
    }

    fun sendEmailCode(email: String, phone: String?) {
        val trimmed = email.trim().lowercase()
        if (!trimmed.endsWith("@gmail.com")) {
            _ui.value = _ui.value.copy(errorCode = "INVALID_EMAIL", errorMsg = "فقط ایمیل‌های gmail.com@ مجاز هستند")
            return
        }

        _ui.value = _ui.value.copy(loading = true, errorCode = null, errorMsg = "")
        viewModelScope.launch {
            when (val r = repo.sendEmailCode(trimmed, phone)) {
                is ApiResult.Success -> {
                    val cooldown = r.data.cooldownSec ?: 60
                    _ui.value = _ui.value.copy(loading = false, codeSent = true, cooldownSec = cooldown)
                    startCooldown(cooldown)
                }
                is ApiResult.Error -> {
                    _ui.value = _ui.value.copy(loading = false, errorCode = r.code, errorMsg = r.message)
                }
            }
        }
    }

    private fun startCooldown(seconds: Int) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var s = seconds
            while (isActive && s > 0) {
                delay(1000)
                s -= 1
                _ui.value = _ui.value.copy(cooldownSec = s)
            }
        }
    }

    fun verifyEmailCode(email: String, code: String) {
        _ui.value = _ui.value.copy(loading = true, errorCode = null, errorMsg = "")
        viewModelScope.launch {
            when (val r = repo.verifyEmailCode(email, code)) {
                is ApiResult.Success -> {
                    _ui.value = _ui.value.copy(
                        loading = false,
                        emailVerified = true,
                        verificationToken = r.data.verificationToken,
                    )
                }
                is ApiResult.Error -> {
                    _ui.value = _ui.value.copy(loading = false, errorCode = r.code, errorMsg = r.message)
                }
            }
        }
    }

    fun register(
        phone: String,
        username: String,
        password: String,
        email: String? = null,
        verificationCode: String? = null,
    ) {
        _ui.value = _ui.value.copy(loading = true, errorCode = null, errorMsg = "")
        viewModelScope.launch {
            val token = _ui.value.verificationToken
            when (val r = repo.register(phone, username, password, email, verificationCode, token)) {
                is ApiResult.Success -> _ui.value = _ui.value.copy(loading = false, done = true)
                is ApiResult.Error -> _ui.value = _ui.value.copy(loading = false, errorCode = r.code, errorMsg = r.message)
            }
        }
    }

    fun clearError() {
        _ui.value = _ui.value.copy(errorCode = null, errorMsg = "")
    }

    override fun onCleared() {
        countdownJob?.cancel()
    }
}

@Composable
fun LoginScreen(
    vm: SharedAuthViewModel,
    onLoggedIn: () -> Unit,
    onGoRegister: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(ui.done) { if (ui.done) onLoggedIn() }

    var identifier by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BazicheBgRoot),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BazicheLogo(size = 80.dp)

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = BazicheEmeraldBright,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = BazicheTextMuted,
            )

            Spacer(Modifier.height(32.dp))

            BazicheCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = stringResource(R.string.login),
                        style = MaterialTheme.typography.titleLarge,
                        color = BazicheTextPrimary,
                    )

                    BazicheTextField(
                        value = identifier,
                        onValueChange = { identifier = it; vm.clearError() },
                        label = "شماره موبایل یا جیمیل",
                        placeholder = "۰۹۱۲... یا yourname@gmail.com",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )

                    BazicheTextField(
                        value = password,
                        onValueChange = { password = it; vm.clearError() },
                        label = stringResource(R.string.password),
                        placeholder = stringResource(R.string.password_hint),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )

                    ui.errorCode?.let {
                        ErrorCard(code = it, fallback = ui.errorMsg, onRetry = { vm.login(identifier, password) })
                    }

                    Spacer(Modifier.height(6.dp))

                    BazichePrimaryButton(
                        text = stringResource(R.string.login),
                        loading = ui.loading,
                        enabled = identifier.isNotBlank() && password.length >= 8,
                        onClick = { vm.login(identifier, password) },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.no_account),
                    color = BazicheTextSecondary,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "ثبت‌نام کنید",
                    color = BazicheLime,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable(onClick = onGoRegister),
                )
            }
        }
    }
}

@Composable
fun RegisterScreen(
    vm: SharedAuthViewModel,
    onRegistered: () -> Unit,
    onGoLogin: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(ui.done) { if (ui.done) onRegistered() }

    var phone by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }

    val isGmailValid = email.trim().lowercase().endsWith("@gmail.com") && email.trim().length >= 12
    val mismatch = confirm.isNotEmpty() && confirm != password

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BazicheBgRoot),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BazicheLogo(size = 72.dp)

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.register),
                style = MaterialTheme.typography.headlineLarge,
                color = BazicheEmeraldBright,
                fontWeight = FontWeight.Bold,
            )

            Text(
                text = "ثبت‌نام با شماره تماس و تأیید جیمیل",
                style = MaterialTheme.typography.bodyMedium,
                color = BazicheTextMuted,
            )

            Spacer(Modifier.height(24.dp))

            BazicheCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

                    // 1. Phone Input
                    BazicheTextField(
                        value = phone,
                        onValueChange = { phone = it; vm.clearError() },
                        label = stringResource(R.string.phone),
                        placeholder = stringResource(R.string.phone_hint),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )

                    // 2. Gmail Input with verification step
                    BazicheTextField(
                        value = email,
                        onValueChange = { email = it; vm.clearError() },
                        label = stringResource(R.string.email),
                        placeholder = stringResource(R.string.email_hint),
                        supportingText = stringResource(R.string.gmail_only_hint),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )

                    // Send Code Button / Countdown
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (ui.emailVerified) {
                            BazicheBadge(text = "✓ جیمیل تأیید شد", color = BazicheLime)
                        } else {
                            val canSend = isGmailValid && ui.cooldownSec == 0 && !ui.loading
                            TextButton(
                                onClick = { vm.sendEmailCode(email, phone) },
                                enabled = canSend,
                            ) {
                                Text(
                                    text = if (ui.cooldownSec > 0) "ارسال مجدد (${ui.cooldownSec}s)" else if (ui.codeSent) stringResource(R.string.resend_code) else stringResource(R.string.send_code),
                                    color = if (canSend) BazicheEmeraldBright else BazicheTextMuted,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }

                    // 6-digit Code Input (appears after code sent or when not verified)
                    AnimatedVisibility(visible = ui.codeSent && !ui.emailVerified) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                BazicheTextField(
                                    value = code,
                                    onValueChange = { if (it.length <= 6) code = it; vm.clearError() },
                                    label = stringResource(R.string.email_code),
                                    placeholder = "۱۲۳۴۵۶",
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )
                            }

                            BazichePrimaryButton(
                                text = stringResource(R.string.verify_code),
                                onClick = { vm.verifyEmailCode(email, code) },
                                modifier = Modifier.width(110.dp),
                                enabled = code.trim().length == 6 && !ui.loading,
                                loading = ui.loading,
                            )
                        }
                    }

                    // 3. Username Input
                    BazicheTextField(
                        value = username,
                        onValueChange = { username = it; vm.clearError() },
                        label = stringResource(R.string.username),
                        placeholder = "حداقل ۳ کاراکتر",
                    )

                    // 4. Password Input
                    BazicheTextField(
                        value = password,
                        onValueChange = { password = it; vm.clearError() },
                        label = stringResource(R.string.password),
                        placeholder = stringResource(R.string.password_hint),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )

                    // 5. Confirm Password
                    BazicheTextField(
                        value = confirm,
                        onValueChange = { confirm = it; vm.clearError() },
                        label = stringResource(R.string.confirm_password),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = mismatch,
                        supportingText = if (mismatch) stringResource(R.string.passwords_no_match) else null,
                    )

                    ui.errorCode?.let {
                        ErrorCard(code = it, fallback = ui.errorMsg)
                    }

                    Spacer(Modifier.height(8.dp))

                    val canRegister = phone.isNotBlank() &&
                        isGmailValid &&
                        username.trim().length >= 3 &&
                        password.length >= 8 &&
                        !mismatch &&
                        !ui.loading

                    BazichePrimaryButton(
                        text = stringResource(R.string.register),
                        loading = ui.loading,
                        enabled = canRegister,
                        onClick = {
                            vm.register(
                                phone = phone,
                                username = username,
                                password = password,
                                email = email,
                                verificationCode = code.ifBlank { null },
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.have_account),
                    color = BazicheTextSecondary,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "وارد شوید",
                    color = BazicheLime,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable(onClick = onGoLogin),
                )
            }
        }
    }
}
