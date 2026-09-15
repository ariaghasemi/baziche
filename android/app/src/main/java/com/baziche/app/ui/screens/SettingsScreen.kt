package com.baziche.app.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baziche.app.BuildConfig
import com.baziche.app.R
import com.baziche.app.ui.util.LanguageManager
import com.baziche.core.data.repo.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SettingsUi(val locale: String = LanguageManager.FA, val loggedOut: Boolean = false, val recreateTick: Int = 0)

class SettingsViewModel(private val repo: AuthRepository, private val lang: LanguageManager, val apiUrl: String) : ViewModel() {
    private val _ui = MutableStateFlow(SettingsUi(locale = lang.current()))
    val ui: StateFlow<SettingsUi> = _ui

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
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = { IconButton(onClick = onBack) { Text("‹") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.language), style = MaterialTheme.typography.labelLarge)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = ui.locale == LanguageManager.FA, onClick = { vm.setLocale(LanguageManager.FA) })
                        Text("فارسی")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = ui.locale == LanguageManager.EN, onClick = { vm.setLocale(LanguageManager.EN) })
                        Text("English")
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${stringResource(R.string.api_url)}: ${vm.apiUrl}")
                    Text("${stringResource(R.string.version)}: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                }
            }
            OutlinedButton(onClick = { vm.logout() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.logout)) }
        }
    }
}
