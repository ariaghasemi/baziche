package com.baziche.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.graphics.Color
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
import com.baziche.app.ui.components.BazicheTextField
import com.baziche.app.ui.components.ErrorCard
import com.baziche.app.ui.theme.BazicheBgCard
import com.baziche.app.ui.theme.BazicheBgCardElevated
import com.baziche.app.ui.theme.BazicheBgRoot
import com.baziche.app.ui.theme.BazicheBgSurface
import com.baziche.app.ui.theme.BazicheBorder
import com.baziche.app.ui.theme.BazicheBorderLight
import com.baziche.app.ui.theme.BazicheCyan
import com.baziche.app.ui.theme.BazicheEmeraldBright
import com.baziche.app.ui.theme.BazicheLime
import com.baziche.app.ui.theme.BazicheTextMuted
import com.baziche.app.ui.theme.BazicheTextPrimary
import com.baziche.app.ui.theme.BazicheTextSecondary
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

class ProjectsViewModel(
    private val repo: ProjectRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(ProjectsUi())
    val ui: StateFlow<ProjectsUi> = _ui

    init {
        refresh()
    }

    fun refresh() {
        _ui.value = _ui.value.copy(loading = true, errorCode = null, errorMsg = "")

        viewModelScope.launch {
            val types = repo.getGameTypes()

            when (val r = repo.listProjects()) {
                is ApiResult.Success -> {
                    _ui.value = ProjectsUi(
                        loading = false,
                        items = r.data,
                        types = types,
                    )
                }

                is ApiResult.Error -> {
                    _ui.value = ProjectsUi(
                        loading = false,
                        items = emptyList(),
                        types = types,
                        errorCode = r.code,
                        errorMsg = r.message,
                    )
                }
            }
        }
    }

    fun create(
        name: String,
        gameType: String,
        onDone: (String) -> Unit,
    ) {
        viewModelScope.launch {
            when (val r = repo.createProject(name, gameType)) {
                is ApiResult.Success -> {
                    refresh()
                    onDone(r.data.id)
                }

                is ApiResult.Error -> {
                    _ui.value = _ui.value.copy(
                        loading = false,
                        errorCode = r.code,
                        errorMsg = r.message,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    vm: ProjectsViewModel,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    val ui by vm.ui.collectAsState()

    var showCreate by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf<String?>(null) }

    val filteredItems = ui.items.filter { p ->
        val matchesSearch = searchQuery.isBlank() || p.name.contains(searchQuery, ignoreCase = true)
        val matchesFilter = selectedFilter == null || p.gameType == selectedFilter
        matchesSearch && matchesFilter
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.my_projects),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
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
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreate = true },
                containerColor = BazicheEmeraldBright,
                contentColor = BazicheBgRoot,
                shape = CircleShape,
            ) {
                Text("+", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = BazicheBgRoot,
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            // Search Bar
            BazicheTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "جستجوی پروژه بر اساس نام…",
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))

            // Filter Chips by Game Type
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                item {
                    val active = selectedFilter == null
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) BazicheEmeraldBright else BazicheBgCard)
                            .clickable { selectedFilter = null }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "همه",
                            color = if (active) BazicheBgRoot else BazicheTextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                val allTypes = ui.types.ifEmpty { ProjectRepository.FALLBACK_ALL_20 }
                items(allTypes) { t ->
                    val active = selectedFilter == t.id
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) BazicheEmeraldBright else BazicheBgCard)
                            .clickable { selectedFilter = if (active) null else t.id }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = if (t.nameFa.isNotBlank()) t.nameFa else t.name,
                            color = if (active) BazicheBgRoot else BazicheTextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            when {
                ui.loading && ui.items.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = BazicheLime)
                    }
                }

                ui.errorCode != null && ui.items.isEmpty() -> {
                    ErrorCard(code = ui.errorCode!!, fallback = ui.errorMsg, onRetry = { vm.refresh() })
                }

                filteredItems.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (searchQuery.isNotBlank()) "هیچ پروژه‌ای با این مشخصات یافت نشد." else stringResource(R.string.no_projects),
                            style = MaterialTheme.typography.bodyMedium,
                            color = BazicheTextSecondary,
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 80.dp),
                    ) {
                        items(filteredItems) { p ->
                            BazicheCard(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onOpen(p.id) },
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            p.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = BazicheTextPrimary,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "${p.gameType} · ${stringResource(R.string.revision)} ${p.rev}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = BazicheTextSecondary,
                                        )
                                    }

                                    BazicheBadge(
                                        text = p.gameType.uppercase(),
                                        color = BazicheLime,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateDialog(
            types = ui.types.ifEmpty { ProjectRepository.FALLBACK_ALL_20 },
            onDismiss = { showCreate = false },
            onCreate = { n, t ->
                vm.create(n, t) { id ->
                    showCreate = false
                    onOpen(id)
                }
            },
        )
    }
}

@Composable
private fun CreateDialog(
    types: List<GameTypeDto>,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(types.firstOrNull()?.id ?: "quiz") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BazicheBgCard,
        title = {
            Text(
                stringResource(R.string.new_project),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = BazicheTextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BazicheTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.project_name),
                    placeholder = "مثال: فرار از سیاره زامبی",
                    singleLine = true,
                )

                Text(
                    text = "سبک بازی را انتخاب کنید (۲۰ سبک پشتیبانی‌شده):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = BazicheTextSecondary,
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BazicheBgRoot)
                        .border(1.dp, BazicheBorder, RoundedCornerShape(12.dp))
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(types) { t ->
                        val isSelected = picked == t.id
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) BazicheEmeraldBright.copy(alpha = 0.2f) else Color.Transparent)
                                .border(1.dp, if (isSelected) BazicheEmeraldBright else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { picked = t.id }
                                .padding(10.dp),
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = if (t.nameFa.isNotBlank()) "${t.nameFa} (${t.name})" else t.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) BazicheLime else BazicheTextPrimary,
                                    )
                                    Text(
                                        text = t.orientation,
                                        fontSize = 11.sp,
                                        color = BazicheTextMuted,
                                    )
                                }
                                if (t.descriptionFa.isNotBlank() || t.description.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = if (t.descriptionFa.isNotBlank()) t.descriptionFa else t.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = BazicheTextSecondary,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            BazichePrimaryButton(
                text = stringResource(R.string.create),
                onClick = { onCreate(name.trim(), picked) },
                enabled = name.isNotBlank(),
                modifier = Modifier.width(110.dp).height(44.dp),
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = BazicheTextSecondary)
            }
        },
    )
}
