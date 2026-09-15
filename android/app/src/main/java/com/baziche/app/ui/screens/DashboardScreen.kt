package com.baziche.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baziche.app.R
import com.baziche.app.ui.components.ErrorCard
import com.baziche.core.common.ApiResult
import com.baziche.core.data.repo.AuthRepository
import com.baziche.core.data.repo.MeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DashUi(val loading: Boolean = true, val me: MeInfo? = null, val errorCode: String? = null, val errorMsg: String = "", val loggedOut: Boolean = false)

class DashboardViewModel(private val repo: AuthRepository) : ViewModel() {
    private val _ui = MutableStateFlow(DashUi())
    val ui: StateFlow<DashUi> = _ui

    init {
        refresh()
    }

    fun refresh() {
        _ui.value = _ui.value.copy(loading = true, errorCode = null)
        viewModelScope.launch {
            when (val r = repo.me()) {
                is ApiResult.Success -> _ui.value = DashUi(me = r.data)
                is ApiResult.Error -> _ui.value = DashUi(loading = false, errorCode = r.code, errorMsg = r.message)
            }
        }
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
fun DashboardScreen(vm: DashboardViewModel, onProjects: () -> Unit, onSettings: () -> Unit, onLoggedOut: () -> Unit) {
    val ui by vm.ui.collectAsState()
    LaunchedEffect(ui.loggedOut) { if (ui.loggedOut) onLoggedOut() }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.dashboard)) }) }) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                ui.loading -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                }
                ui.errorCode != null -> {
                    ErrorCard(ui.errorCode!!, ui.errorMsg) { vm.refresh() }
                }
                ui.me != null -> {
                    val me = ui.me!!
                    Text(stringResource(R.string.hello, me.username), style = MaterialTheme.typography.headlineSmall)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${stringResource(R.string.plan)}: ${me.entitlement.planId}")
                            Text(if (me.entitlement.subscribed) stringResource(R.string.subscribed) else stringResource(R.string.not_subscribed))
                            Text(if (me.entitlement.freeBuild == "AVAILABLE") stringResource(R.string.free_build_available) else stringResource(R.string.free_build_used))
                            Text("${stringResource(R.string.projects)}: ${me.projects}")
                        }
                    }
                    Button(onClick = onProjects, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.my_projects)) }
                    OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings)) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.logout() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.logout)) }
                }
            }
        }
    }
}
