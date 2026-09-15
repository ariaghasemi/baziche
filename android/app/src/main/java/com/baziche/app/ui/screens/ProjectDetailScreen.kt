package com.baziche.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.baziche.core.data.repo.ProjectDetail
import com.baziche.core.data.repo.ProjectRepository
import com.baziche.core.network.RevisionDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DetailUi(
    val loading: Boolean = true,
    val detail: ProjectDetail? = null,
    val errorCode: String? = null,
    val errorMsg: String = "",
    val deleted: Boolean = false,
    val revisions: List<RevisionDto> = emptyList(),
    val revisionsLoading: Boolean = false,
    val restoring: Boolean = false,
    val restoreError: String? = null,
)

class ProjectDetailViewModel(private val repo: ProjectRepository, private val id: String) : ViewModel() {
    private val _ui = MutableStateFlow(DetailUi())
    val ui: StateFlow<DetailUi> = _ui

    init {
        refresh()
    }

    fun refresh() {
        _ui.value = _ui.value.copy(loading = true, errorCode = null, restoreError = null)
        viewModelScope.launch {
            when (val r = repo.getProject(id)) {
                is ApiResult.Success -> {
                    _ui.value = _ui.value.copy(loading = false, detail = r.data)
                    loadRevisions()
                }
                is ApiResult.Error -> _ui.value = _ui.value.copy(loading = false, errorCode = r.code, errorMsg = r.message)
            }
        }
    }

    fun loadRevisions() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(revisionsLoading = true)
            try {
                _ui.value = _ui.value.copy(revisions = repo.revisions(id), revisionsLoading = false)
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(revisionsLoading = false)
            }
        }
    }

    fun restore(rev: Int) {
        val baseRev = _ui.value.detail?.project?.rev ?: return
        _ui.value = _ui.value.copy(restoring = true, restoreError = null)
        viewModelScope.launch {
            try {
                repo.restoreRevision(id, rev, baseRev)
                _ui.value = _ui.value.copy(restoring = false)
                refresh()
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(restoring = false, restoreError = t.message ?: "restore failed")
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            when (val r = repo.deleteProject(id)) {
                is ApiResult.Success -> _ui.value = _ui.value.copy(deleted = true)
                is ApiResult.Error -> _ui.value = _ui.value.copy(errorCode = r.code, errorMsg = r.message)
            }
        }
    }
}

private fun formatTs(epochSec: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochSec * 1000))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(vm: ProjectDetailViewModel, onBack: () -> Unit, onOpenEditor: () -> Unit) {
    val ui by vm.ui.collectAsState()
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var restoreTarget by rememberSaveable { mutableStateOf<Int?>(null) }
    if (ui.deleted) {
        onBack()
        return
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(ui.detail?.project?.name ?: stringResource(R.string.loading)) },
            navigationIcon = { IconButton(onClick = onBack) { Text("‹") } },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                ui.loading -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                ui.errorCode != null -> ErrorCard(ui.errorCode!!, ui.errorMsg) { vm.refresh() }
                ui.detail != null -> {
                    val d = ui.detail!!
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${stringResource(R.string.game_type)}: ${d.project.gameType}")
                            Text("${stringResource(R.string.revision)}: ${d.project.rev}")
                        }
                    }
                    Button(onClick = onOpenEditor, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.open_in_editor)) }
                    OutlinedButton(onClick = { showDelete = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.delete)) }
                    Text(stringResource(R.string.history), style = MaterialTheme.typography.labelLarge)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (ui.revisionsLoading || ui.restoring) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                            } else if (ui.revisions.isEmpty()) {
                                Text(stringResource(R.string.no_revisions), style = MaterialTheme.typography.bodySmall)
                            } else {
                                ui.revisions.forEach { rev ->
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("r${rev.rev} · ${formatTs(rev.createdAt)}", style = MaterialTheme.typography.bodySmall)
                                        if (rev.rev == d.project.rev) {
                                            Text(stringResource(R.string.current), style = MaterialTheme.typography.labelSmall)
                                        } else {
                                            TextButton(onClick = { restoreTarget = rev.rev }) { Text(stringResource(R.string.restore)) }
                                        }
                                    }
                                }
                            }
                            ui.restoreError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    Text(stringResource(R.string.project_json), style = MaterialTheme.typography.labelLarge)
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            (d.json?.toString() ?: "{}").take(4000),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
    restoreTarget?.let { rev ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text(stringResource(R.string.restore)) },
            text = { Text(stringResource(R.string.restore_confirm, rev)) },
            confirmButton = { TextButton(onClick = { restoreTarget = null; vm.restore(rev) }) { Text(stringResource(R.string.restore)) } },
            dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_project_q)) },
            confirmButton = { TextButton(onClick = { showDelete = false; vm.delete() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
