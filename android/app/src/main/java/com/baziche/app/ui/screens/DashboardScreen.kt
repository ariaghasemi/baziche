package com.baziche.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.baziche.app.ui.components.BazicheLogo
import com.baziche.app.ui.components.BazichePrimaryButton
import com.baziche.app.ui.components.BazicheSecondaryButton
import com.baziche.app.ui.components.ErrorCard
import com.baziche.app.ui.components.ShimmerBox
import com.baziche.app.ui.theme.BazicheAmber
import com.baziche.app.ui.theme.BazicheBgCard
import com.baziche.app.ui.theme.BazicheBgCardElevated
import com.baziche.app.ui.theme.BazicheBgRoot
import com.baziche.app.ui.theme.BazicheBgSurface
import com.baziche.app.ui.theme.BazicheBlue
import com.baziche.app.ui.theme.BazicheBorder
import com.baziche.app.ui.theme.BazicheBorderLight
import com.baziche.app.ui.theme.BazicheCyan
import com.baziche.app.ui.theme.BazicheEmerald
import com.baziche.app.ui.theme.BazicheEmeraldBright
import com.baziche.app.ui.theme.BazicheLime
import com.baziche.app.ui.theme.BazichePurple
import com.baziche.app.ui.theme.BazicheRed
import com.baziche.app.ui.theme.BazicheTextMuted
import com.baziche.app.ui.theme.BazicheTextPrimary
import com.baziche.app.ui.theme.BazicheTextSecondary
import com.baziche.core.common.ApiResult
import com.baziche.core.data.repo.AuthRepository
import com.baziche.core.data.repo.MeInfo
import com.baziche.core.data.repo.ProjectRepository
import com.baziche.core.network.ProjectDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

data class DashUi(
    val loading: Boolean = true,
    val me: MeInfo? = null,
    val recentProjects: List<ProjectDto> = emptyList(),
    val errorCode: String? = null,
    val errorMsg: String = "",
    val loggedOut: Boolean = false,
)

class DashboardViewModel(
    private val authRepo: AuthRepository,
    private val projectRepo: ProjectRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(DashUi())
    val ui: StateFlow<DashUi> = _ui

    init {
        refresh()
    }

    fun refresh() {
        _ui.value = _ui.value.copy(loading = true, errorCode = null, errorMsg = "")
        viewModelScope.launch {
            val meRes = authRepo.me()
            val projRes = projectRepo.listProjects()

            when (meRes) {
                is ApiResult.Success -> {
                    val projects = (projRes as? ApiResult.Success)?.data ?: emptyList()
                    _ui.value = DashUi(
                        loading = false,
                        me = meRes.data,
                        recentProjects = projects.take(5),
                    )
                }
                is ApiResult.Error -> {
                    _ui.value = DashUi(
                        loading = false,
                        errorCode = meRes.code,
                        errorMsg = meRes.message,
                    )
                }
            }
        }
    }

    fun quickCreate(name: String, gameType: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            when (val r = projectRepo.createProject(name, gameType)) {
                is ApiResult.Success -> {
                    refresh()
                    onDone(r.data.id)
                }
                is ApiResult.Error -> {
                    _ui.value = _ui.value.copy(errorCode = r.code, errorMsg = r.message)
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepo.logout()
            _ui.value = _ui.value.copy(loggedOut = true, loading = false)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    vm: DashboardViewModel,
    onNewProject: () -> Unit,
    onProjects: () -> Unit,
    onOpenEditor: (String) -> Unit,
    onOpenPreview: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onSettings: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val ui by vm.ui.collectAsState()

    LaunchedEffect(ui.loggedOut) {
        if (ui.loggedOut) onLoggedOut()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BazicheLogo(size = 38.dp, animated = false)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.dashboard),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                actions = {
                    Text(
                        text = "تنظیمات",
                        color = BazicheEmeraldBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable(onClick = onSettings)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BazicheBgSurface,
                    titleContentColor = BazicheTextPrimary,
                ),
            )
        },
        containerColor = BazicheBgRoot,
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            // 1. Error Banner
            if (ui.errorCode != null) {
                item {
                    ErrorCard(code = ui.errorCode!!, fallback = ui.errorMsg, onRetry = { vm.refresh() })
                }
            }

            // 2. Loading Skeleton
            if (ui.loading && ui.me == null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ShimmerBox(modifier = Modifier.fillMaxWidth().height(100.dp))
                        ShimmerBox(modifier = Modifier.fillMaxWidth().height(60.dp))
                        ShimmerBox(modifier = Modifier.fillMaxWidth().height(160.dp))
                    }
                }
            }

            // 3. Header & Smart Persian Greeting
            if (ui.me != null) {
                val me = ui.me!!
                item {
                    UserGreetingCard(me = me)
                }

                // 4. Metric Cards (Free Build, Plan, Projects Count)
                item {
                    MetricsRow(me = me)
                }

                // 5. Primary CTA: New Game Button
                item {
                    BazichePrimaryButton(
                        text = "+ " + stringResource(R.string.new_game_cta),
                        onClick = onNewProject,
                        modifier = Modifier.height(56.dp),
                    )
                }

                // 6. Quick Action Creators
                item {
                    QuickActionSection(
                        onQuickCreate = { name, type ->
                            vm.quickCreate(name, type) { id -> onOpenEditor(id) }
                        },
                    )
                }

                // 7. Recent Projects Header
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "پروژه‌های اخیر",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = BazicheTextPrimary,
                        )
                        Text(
                            text = "مشاهده همه (${me.projects}) ‹",
                            style = MaterialTheme.typography.labelMedium,
                            color = BazicheLime,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(onClick = onProjects),
                        )
                    }
                }

                // 8. Recent Projects List / Empty State
                if (ui.recentProjects.isEmpty()) {
                    item {
                        EmptyProjectsCard(onNewProject = onNewProject)
                    }
                } else {
                    items(ui.recentProjects) { p ->
                        RecentProjectCard(
                            project = p,
                            onOpenEditor = { onOpenEditor(p.id) },
                            onOpenPreview = { onOpenPreview(p.id) },
                            onOpenDetail = { onOpenDetail(p.id) },
                        )
                    }
                }

                // 9. Bottom Actions
                item {
                    Spacer(Modifier.height(8.dp))
                    BazicheSecondaryButton(
                        text = stringResource(R.string.logout),
                        onClick = { vm.logout() },
                    )
                }
            }
        }
    }
}

@Composable
private fun UserGreetingCard(me: MeInfo) {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) {
        in 5..11 -> stringResource(R.string.greeting_morning)
        in 12..16 -> "ظهر بخیر، استودیو بازی‌سازی شما آماده است"
        in 17..20 -> stringResource(R.string.greeting_afternoon)
        else -> stringResource(R.string.greeting_evening)
    }

    BazicheCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Gamer Avatar with emerald glowing ring
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(BazicheBgCardElevated)
                    .border(2.dp, BazicheEmeraldBright, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = me.username.take(2).uppercase(),
                    color = BazicheLime,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "سلام ${me.username} عزیز 👋",
                    style = MaterialTheme.typography.titleLarge,
                    color = BazicheTextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = greeting,
                    style = MaterialTheme.typography.bodySmall,
                    color = BazicheTextSecondary,
                )
                if (me.email != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = me.email,
                        style = MaterialTheme.typography.labelSmall,
                        color = BazicheTextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricsRow(me: MeInfo) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Free Build State Card
        val isFreeAvailable = me.entitlement.freeBuild == "AVAILABLE"
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(BazicheBgCard)
                .border(1.dp, if (isFreeAvailable) BazicheEmeraldBright.copy(alpha = 0.4f) else BazicheBorder, RoundedCornerShape(14.dp))
                .padding(12.dp),
        ) {
            Column {
                Text(
                    text = "خروجی APK",
                    style = MaterialTheme.typography.labelSmall,
                    color = BazicheTextMuted,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (isFreeAvailable) "۱ بیلد رایگان" else "مصرف شده",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isFreeAvailable) BazicheLime else BazicheTextSecondary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Plan Card
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(BazicheBgCard)
                .border(1.dp, BazicheBorder, RoundedCornerShape(14.dp))
                .padding(12.dp),
        ) {
            Column {
                Text(
                    text = "پلن حساب",
                    style = MaterialTheme.typography.labelSmall,
                    color = BazicheTextMuted,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (me.entitlement.subscribed) "اشتراک فعال" else "پایه (رایگان)",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (me.entitlement.subscribed) BazicheCyan else BazicheTextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Total Projects Card
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(BazicheBgCard)
                .border(1.dp, BazicheBorder, RoundedCornerShape(14.dp))
                .padding(12.dp),
        ) {
            Column {
                Text(
                    text = "پروژه‌ها",
                    style = MaterialTheme.typography.labelSmall,
                    color = BazicheTextMuted,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${me.projects} بازی",
                    style = MaterialTheme.typography.titleSmall,
                    color = BazicheTextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun QuickActionSection(
    onQuickCreate: (name: String, type: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "ساخت سریع بر اساس قالب",
            style = MaterialTheme.typography.labelLarge,
            color = BazicheTextSecondary,
            fontWeight = FontWeight.SemiBold,
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
        ) {
            val quickItems = listOf(
                Triple("کوئیز و دانستنی", "quiz", BazicheEmeraldBright),
                Triple("پازل تصویری", "puzzle", BazicheCyan),
                Triple("کلمات و جدول", "word", BazicheAmber),
                Triple("دونده بی‌پایان", "runner", BazicheLime),
                Triple("آرکید رکوردی", "arcade", BazichePurple),
            )

            items(quickItems) { (nameFa, typeId, accentColor) ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(BazicheBgCard)
                        .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .clickable { onQuickCreate("بازی $nameFa", typeId) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+ $nameFa",
                        color = accentColor,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentProjectCard(
    project: ProjectDto,
    onOpenEditor: () -> Unit,
    onOpenPreview: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    BazicheCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenDetail,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = BazicheTextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "سبک: ${project.gameType} · ویرایش ${project.rev}",
                        style = MaterialTheme.typography.bodySmall,
                        color = BazicheTextSecondary,
                    )
                }

                BazicheBadge(text = project.gameType.uppercase(), color = BazicheLime)
            }

            // Quick action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BazicheBgCardElevated)
                        .clickable(onClick = onOpenEditor)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "استودیو ویرایش",
                        color = BazicheEmeraldBright,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BazicheBgCardElevated)
                        .clickable(onClick = onOpenPreview)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "پیش‌نمایش زنده",
                        color = BazicheLime,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BazicheBgCardElevated)
                        .clickable(onClick = onOpenDetail)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "خروجی APK",
                        color = BazicheCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyProjectsCard(onNewProject: () -> Unit) {
    BazicheCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "🎮",
                fontSize = 40.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "هنوز هیچ بازی‌ای نساخته‌اید!",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = BazicheTextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "بدون نیاز به کدنویسی اولین بازی اندروید خود را خلق کنید.",
                style = MaterialTheme.typography.bodySmall,
                color = BazicheTextSecondary,
            )
            Spacer(Modifier.height(16.dp))
            BazichePrimaryButton(
                text = "شروع ساخت اولین بازی",
                onClick = onNewProject,
                modifier = Modifier.width(220.dp).height(46.dp),
            )
        }
    }
}
