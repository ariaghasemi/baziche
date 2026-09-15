package com.baziche.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.baziche.app.ui.navigation.Routes
import com.baziche.core.data.session.DataStoreSessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SplashViewModel(private val store: DataStoreSessionStore) : ViewModel() {
    private val _hasSession = MutableStateFlow<Boolean?>(null)
    val hasSession: StateFlow<Boolean?> = _hasSession

    init {
        viewModelScope.launch { _hasSession.value = store.load() != null }
    }
}

@Composable
fun SplashScreen(vm: SplashViewModel, onDone: (String) -> Unit) {
    val state by vm.hasSession.collectAsState()
    LaunchedEffect(state) {
        state?.let { onDone(if (it) Routes.DASHBOARD else Routes.LOGIN) }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator()
    }
}
