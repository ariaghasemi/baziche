package com.baziche.app.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baziche.app.BuildConfig
import com.baziche.app.R
import com.baziche.app.ui.components.BazicheBadge
import com.baziche.app.ui.components.BazicheCard
import com.baziche.app.ui.components.BazicheSecondaryButton
import com.baziche.app.ui.theme.BazicheBgCardElevated
import com.baziche.app.ui.theme.BazicheBgRoot
import com.baziche.app.ui.theme.BazicheBgSurface
import com.baziche.app.ui.theme.BazicheCyan
import com.baziche.app.ui.theme.BazicheEmeraldBright
import com.baziche.app.ui.theme.BazicheLime
import com.baziche.app.ui.theme.BazicheRed
import com.baziche.app.ui.theme.BazicheTextMuted
import com.baziche.app.ui.theme.BazicheTextPrimary
import com.baziche.app.ui.theme.BazicheTextSecondary
import com.baziche.app.ui.util.LanguageManager
import com.baziche.core.billing.BazicheSubscriptionPlan
import com.baziche.core.common.ApiResult
import com.baziche.core.data.repo.AuthRepository
import com.baziche.core.data.repo.MeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SettingsUi(
    val locale: String = LanguageManager.FA,
    val me: MeInfo? = null,
    val loggedOut: Boolean = false,
    val recreateTick: Int = 0,
)

class SettingsViewModel(
    private val repo: AuthRepository,
    private val lang: LanguageManager,
    val apiUrl: String,
) : ViewModel() {
    private val _ui = MutableStateFlow(SettingsUi(locale = lang.current()))
    val ui: StateFlow<SettingsUi> = _ui

    init {
        loadProfile()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            when (val r = repo.me()) {
                is ApiResult.Success -> _ui.value = _ui.value.copy(me = r.data)
                is ApiResult.Error -> {}
            }
        }
    }

    fun setLocale(tag: String) {
        if (tag == _ui.value.locale) return
        lang.set(tag)
        _ui.value = _ui.value.copy(locale = tag, recreateTick = _ui.value.recreateTick + 1)
    }

    fun logout() {
        viewModelScope.launch {
            repo.logout()
            _ui.value = _ui.value.copy(loggedOut = true)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onLoggedOut: () -> Unit, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(ui.loggedOut) { if (ui.loggedOut) onLoggedOut() }
    LaunchedEffect(ui.recreateTick) {
        if (ui.recreateTick > 0) (context as? Activity)?.recreate()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("‹", fontSize = 28.sp, color = BazicheEmeraldBright, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BazicheBgSurface,
                    titleContentColor = BazicheTextPrimary,
                ),
            )
        },
        containerColor = BazicheBgRoot,
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // User Profile Card
            ui.me?.let { me ->
                BazicheCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(BazicheBgCardElevated)
                                .border(2.dp, BazicheEmeraldBright, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(me.username.take(2).uppercase(), color = BazicheLime, fontWeight = FontWeight.Bold)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(me.username, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = BazicheTextPrimary)
                            Text(me.phone, style = MaterialTheme.typography.bodySmall, color = BazicheTextSecondary)
                            me.email?.let { email ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(email, style = MaterialTheme.typography.labelSmall, color = BazicheTextMuted)
                                    Spacer(Modifier.width(6.dp))
                                    if (me.emailVerified) {
                                        BazicheBadge(text = "تأیید شده", color = BazicheLime)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Subscription Plans (Section 23 of Master Spec)
            Text("پلن‌های اشتراک بازیچه", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = BazicheTextPrimary)
            BazicheCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val plans = listOf(
                        Triple("ماهانه (۳۰ روز)", "۳۰۰,۰۰۰ تومان", BazicheSubscriptionPlan.MONTHLY),
                        Triple("سه‌ماهه (۹۰ روز)", "۵۰۰,۰۰۰ تومان", BazicheSubscriptionPlan.QUARTERLY),
                        Triple("سالانه (۳۶۵ روز)", "۱,۰۰۰,۰۰۰ تومان", BazicheSubscriptionPlan.YEARLY),
                    )

                    plans.forEach { (name, price, plan) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(BazicheBgCardElevated)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(name, fontWeight = FontWeight.Bold, color = BazicheTextPrimary, fontSize = 14.sp)
                                Text("پشتیبانی از بازار و مایکت", fontSize = 11.sp, color = BazicheTextMuted)
                            }
                            BazicheBadge(text = price, color = BazicheCyan)
                        }
                    }
                }
            }

            // Language Selection Card
            Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = BazicheTextPrimary)
            BazicheCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.setLocale(LanguageManager.FA) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = ui.locale == LanguageManager.FA,
                            onClick = { vm.setLocale(LanguageManager.FA) },
                            colors = RadioButtonDefaults.colors(selectedColor = BazicheEmeraldBright),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("فارسی (راست‌چین)", color = BazicheTextPrimary, fontWeight = FontWeight.Medium)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.setLocale(LanguageManager.EN) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = ui.locale == LanguageManager.EN,
                            onClick = { vm.setLocale(LanguageManager.EN) },
                            colors = RadioButtonDefaults.colors(selectedColor = BazicheEmeraldBright),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("English", color = BazicheTextPrimary, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // App & API Details
            BazicheCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${stringResource(R.string.api_url)}: ${vm.apiUrl}", style = MaterialTheme.typography.bodySmall, color = BazicheTextSecondary)
                    Text("${stringResource(R.string.version)}: ${BuildConfig.VERSION_NAME} (بیلد ${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall, color = BazicheTextSecondary)
                }
            }

            Spacer(Modifier.height(8.dp))
            BazicheSecondaryButton(
                text = stringResource(R.string.logout),
                onClick = { vm.logout() },
            )
        }
    }
}
