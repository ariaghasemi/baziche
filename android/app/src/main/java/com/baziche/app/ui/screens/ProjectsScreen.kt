package com.baziche.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.baziche.app.R
import com.baziche.app.ui.components.ErrorCard
import com.baziche.core.common.ApiResult
import com.baziche.core.data.repo.ProjectRepository
import com.baziche.core.network.GameTypeDto
import com.baziche.core.network.ProjectDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ProjectsUi(
    val loading: Boolean = true,
    val items: List<ProjectDto> = emptyList(),
    val types: List<GameTypeDto> = emptyList(),
    val errorCode: String? = null,
    val errorMsg: String = "",
)

class ProjectsViewModel(private val repo: ProjectRepository) : ViewModel() {
    private val _ui = MutableStateFlow(ProjectsUi())
    val ui: StateFlow<ProjectsUi> = _ui

    init {
        refresh()
    }

    fun refresh() {
        _ui.value = _ui.value.copy(loading = true, errorCode = null)
        viewModelScope.launch {
            val types = repo.getGameTypes()
            when (val r = repo.listProjects()) {
                is ApiResult.Success -> _ui.value = ProjectsUi(items = r.data, types = types)
                is ApiResult.Error -> _ui.value = _ui.value.copy(loading = false, errorCode = r.code, errorMsg = r.message, types = types)
            }
        }
    }

    fun create(name: String, gameType: String, onDone: () -> Unit) {
        viewModelScope.launch {
            when (val r = repo.createProject(name, gameType)) {
                is ApiResult.Success -> {
                    onDone()
                    refresh()
                }
                is ApiResult.Error -> _ui.value = _ui.value.copy(errorCode = r.code, errorMsg = r.message)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(vm: ProjectsViewModel, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    var showCreate by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.my_projects)) },
                navigationIcon = { IconButton(onClick = onBack) { Text("‹") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) { Text("+") }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp)) {
            when {
                ui.loading -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                ui.errorCode != null && ui.items.isEmpty() -> ErrorCard(ui.errorCode!!, ui.errorMsg) { vm.refresh() }
                ui.items.isEmpty() -> Text(stringResource(R.string.no_projects))
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.items) { p ->
                        Card(Modifier.fillMaxWidth().clickable { onOpen(p.id) }) {
                            Column(Modifier.padding(12.dp)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Text("${p.gameType} · ${stringResource(R.string.revision)} ${p.rev}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    if (showCreate) CreateDialog(ui.types, onDismiss = { showCreate = false }, onCreate = { n, t ->
        vm.create(n, t) { showCreate = false }
    })
}

@Composable
private fun CreateDialog(types: List<GameTypeDto>, onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(types.firstOrNull()?.id ?: "quiz") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_project)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.project_name)) }, singleLine = true)
                Text(stringResource(R.string.game_type), style = MaterialTheme.typography.labelLarge)
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(types.ifEmpty { ProjectRepository.FALLBACK_TIER1 }) { t ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { picked = t.id }) {
                            RadioButton(selected = picked == t.id, onClick = { picked = t.id })
                            Text("${t.name} · T${t.tier}")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name.trim(), picked) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
