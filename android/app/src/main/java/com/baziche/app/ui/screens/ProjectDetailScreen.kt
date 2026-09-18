package com.baziche.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.baziche.app.R
import com.baziche.app.ui.components.BazicheBadge
import com.baziche.app.ui.components.BazicheCard
import com.baziche.app.ui.components.BazichePrimaryButton
import com.baziche.app.ui.components.BazicheSecondaryButton
import com.baziche.app.ui.components.ErrorCard
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
import com.baziche.core.common.ApiResult
import com.baziche.core.data.repo.ProjectDetail
import com.baziche.core.data.repo.ProjectRepository
import com.baziche.core.network.BuildDto
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
    val latestBuild: BuildDto? = null,
    val building: Boolean = false,
    val buildError: String? = null,
)

class ProjectDetailViewModel(private val repo: ProjectRepository, private val id: String) : ViewModel() {
    private val _ui = MutableStateFlow(DetailUi())
    val ui: StateFlow<DetailUi> = _ui

    init {
        refresh()
    }

    fun refresh() {
        _ui.value = _ui.value.copy(loading = true, errorCode = null, restoreError = null, buildError = null)
        viewModelScope.launch {
            when (val r = repo.getProject(id)) {
                is ApiResult.Success -> {
                    _ui.value = _ui.value.copy(loading = false, detail = r.data)
                    loadRevisions()
                    loadBuilds()
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
            } catch (_: Throwable) {
                _ui.value = _ui.value.copy(revisionsLoading = false)
            }
        }
    }

    fun loadBuilds() {
        viewModelScope.launch {
            when (val r = repo.listBuilds()) {
                is ApiResult.Success -> {
                    val matching = r.data.firstOrNull { it.projectId == id }
                    _ui.value = _ui.value.copy(latestBuild = matching)
                }
                is ApiResult.Error -> {}
            }
        }
    }

    fun requestBuild() {
        _ui.value = _ui.value.copy(building = true, buildError = null)
        viewModelScope.launch {
            when (val r = repo.createBuild(id, "apk")) {
                is ApiResult.Success -> {
                    _ui.value = _ui.value.copy(building = false, latestBuild = r.data)
                }
                is ApiResult.Error -> {
                    _ui.value = _ui.value.copy(building = false, buildError = r.message)
                }
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
fun ProjectDetailScreen(
    vm: ProjectDetailViewModel,
    onBack: () -> Unit,
    onOpenEditor: () -> Unit,
    onPreview: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var restoreTarget by rememberSaveable { mutableStateOf<Int?>(null) }

    if (ui.deleted) {
        onBack()
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.detail?.project?.name ?: stringResource(R.string.loading)) },
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
            when {
                ui.loading -> {
                    Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = BazicheLime)
                    }
                }

                ui.errorCode != null -> {
                    ErrorCard(code = ui.errorCode!!, fallback = ui.errorMsg, onRetry = { vm.refresh() })
                }

                ui.detail != null -> {
                    val d = ui.detail!!

                    // Project Overview Card
                    BazicheCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = d.project.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = BazicheTextPrimary,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "سبک: ${d.project.gameType} · نسخه ذخیره ${d.project.rev}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BazicheTextSecondary,
                                )
                            }
                            BazicheBadge(text = d.project.gameType.uppercase(), color = BazicheLime)
                        }
                    }

                    // Main Action Buttons
                    BazichePrimaryButton(
                        text = stringResource(R.string.open_in_editor),
                        onClick = onOpenEditor,
                    )

                    BazicheSecondaryButton(
                        text = stringResource(R.string.preview),
                        onClick = onPreview,
                    )

                    // Build & Export APK Card
                    BazicheCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "خروجی APK و انتشار",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = BazicheTextPrimary,
                                )

                                val status = ui.latestBuild?.status ?: "NONE"
                                val badgeColor = when (status) {
                                    "COMPLETED" -> BazicheEmeraldBright
                                    "BUILDING", "QUEUED" -> BazicheLime
                                    "FAILED" -> BazicheRed
                                    else -> BazicheTextMuted
                                }
                                val statusFa = when (status) {
                                    "COMPLETED" -> "آماده نصب"
                                    "BUILDING" -> "در حال ساخت…"
                                    "QUEUED" -> "در صف ساخت"
                                    "FAILED" -> "بیلد ناموفق"
                                    else -> "بدون بیلد"
                                }
                                BazicheBadge(text = statusFa, color = badgeColor)
                            }

                            Text(
                                text = "با کلیک بر روی دکمه زیر، پروژه و تمام Assetها برای Build Server ارسال شده و فایل APK قابل نصب آماده می‌شود.",
                                style = MaterialTheme.typography.bodySmall,
                                color = BazicheTextSecondary,
                            )

                            ui.buildError?.let {
                                Text(text = it, color = BazicheRed, fontSize = 12.sp)
                            }

                            if (ui.latestBuild?.status == "COMPLETED" && !ui.latestBuild?.apkUrl.isNullOrBlank()) {
                                BazichePrimaryButton(
                                    text = "📥 دانلود و نصب APK",
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ui.latestBuild!!.apkUrl))
                                        context.startActivity(intent)
                                    },
                                )
                            } else {
                                BazichePrimaryButton(
                                    text = "⚡ ساخت خروجی APK جدید",
                                    loading = ui.building,
                                    onClick = { vm.requestBuild() },
                                )
                            }
                        }
                    }

                    // Revisions History Card
                    Text(
                        text = stringResource(R.string.history),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = BazicheTextPrimary,
                    )

                    BazicheCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (ui.revisionsLoading || ui.restoring) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                    CircularProgressIndicator(color = BazicheLime)
                                }
                            } else if (ui.revisions.isEmpty()) {
                                Text(stringResource(R.string.no_revisions), style = MaterialTheme.typography.bodySmall, color = BazicheTextMuted)
                            } else {
                                ui.revisions.forEach { rev ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "r${rev.rev} · ${formatTs(rev.createdAt)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = BazicheTextPrimary,
                                        )
                                        if (rev.rev == d.project.rev) {
                                            BazicheBadge(text = stringResource(R.string.current), color = BazicheEmeraldBright)
                                        } else {
                                            Text(
                                                text = stringResource(R.string.restore),
                                                color = BazicheLime,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.clickable { restoreTarget = rev.rev },
                                            )
                                        }
                                    }
                                }
                            }
                            ui.restoreError?.let { Text(it, color = BazicheRed, fontSize = 12.sp) }
                        }
                    }

                    // Delete Project Action
                    Spacer(Modifier.height(8.dp))
                    BazicheSecondaryButton(
                        text = stringResource(R.string.delete),
                        onClick = { showDelete = true },
                    )
                }
            }
        }
    }

    restoreTarget?.let { rev ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            containerColor = BazicheBgCardElevated,
            title = { Text(stringResource(R.string.restore), color = BazicheTextPrimary) },
            text = { Text(stringResource(R.string.restore_confirm, rev), color = BazicheTextSecondary) },
            confirmButton = {
                TextButton(onClick = { restoreTarget = null; vm.restore(rev) }) {
                    Text(stringResource(R.string.restore), color = BazicheLime)
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreTarget = null }) {
                    Text(stringResource(R.string.cancel), color = BazicheTextMuted)
                }
            },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            containerColor = BazicheBgCardElevated,
            title = { Text(stringResource(R.string.delete), color = BazicheTextPrimary) },
            text = { Text(stringResource(R.string.delete_project_q), color = BazicheTextSecondary) },
            confirmButton = {
                TextButton(onClick = { showDelete = false; vm.delete() }) {
                    Text(stringResource(R.string.delete), color = BazicheRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(R.string.cancel), color = BazicheTextMuted)
                }
            },
        )
    }
}
