package com.baziche.editor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baziche.editor.EditorViewModel
import com.baziche.editor.EditorUi
import com.baziche.editor.R
import com.baziche.editor.core.ObjItem

private val KINDS = listOf("rect", "circle", "text", "button", "panel", "sprite")

private fun kindColor(kind: String): Color = when (kind) {
    "rect" -> Color(0xFF10B981)
    "circle" -> Color(0xFF3B82F6)
    "text" -> Color(0xFFE0E0E0)
    "button" -> Color(0xFFB8E62E)
    "panel" -> Color(0xFF24362E)
    "sprite" -> Color(0xFFF59E0B)
    else -> Color(0xFF9E9E9E)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneEditorScreen(
    vm: EditorViewModel,
    onBack: () -> Unit,
    onPreview: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("محتوای بازی", "بوم صحنه", "درآمدزایی", "تنظیمات و خروجی")

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(Color(0xFF0F1714))) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = ui.projectName.ifBlank { "استودیو بازیچه" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF1E382B))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = ui.gameType.uppercase(),
                                    color = Color(0xFFB8E62E),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Text("‹", fontSize = 28.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                        }
                    },
                    actions = {
                        // Live Preview button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF162D22))
                                .border(1.dp, Color(0xFFB8E62E).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .clickable(onClick = onPreview)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "▶ پیش‌نمایش",
                                color = Color(0xFFB8E62E),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        // Save button with feedback
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF10B981))
                                .clickable(enabled = !ui.saving, onClick = { vm.save() })
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (ui.saving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color(0xFF080C0A), strokeWidth = 2.dp)
                            } else {
                                Text(
                                    text = if (ui.dirty) "ذخیره *" else "ذخیره شد",
                                    color = Color(0xFF080C0A),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF0F1714),
                    ),
                )

                // 4 Main Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF0F1714),
                    contentColor = Color(0xFF10B981),
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = Color(0xFF10B981),
                        )
                    },
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    text = title,
                                    fontSize = 12.sp,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedTab == index) Color(0xFF10B981) else Color(0xFF9CA3AF),
                                )
                            },
                        )
                    }
                }
            }
        },
        containerColor = Color(0xFF080C0A),
    ) { pad ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            when (selectedTab) {
                0 -> ContextAwareEditorTab(gameType = ui.gameType)
                1 -> VisualCanvasTab(vm = vm, ui = ui)
                2 -> MonetizationTab()
                3 -> BuildAndExportTab(projectName = ui.projectName, gameType = ui.gameType)
            }
        }
    }
}

// ============================================================================
// TAB 1: Context-Aware Game Type Editor (Quiz, Word, Runner, Puzzle, etc.)
// ============================================================================

@Composable
private fun ContextAwareEditorTab(gameType: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Section 19 Image Permission Banner
        ImagePermissionBanner()

        when (gameType) {
            "quiz" -> QuizContextEditor()
            "word" -> WordContextEditor()
            "runner" -> RunnerContextEditor()
            "puzzle" -> PuzzleContextEditor()
            else -> GenericContextEditor(gameType = gameType)
        }
    }
}

@Composable
private fun ImagePermissionBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF16231D))
            .border(1.dp, Color(0xFF10B981).copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "🖼️ تصاویر اختصاصی",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.image_permission_banner),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9CA3AF),
                )
            }

            Spacer(Modifier.width(10.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF10B981))
                    .clickable { /* Photo Picker Intent simulation */ }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.add_image),
                    color = Color(0xFF080C0A),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

data class QuizQuestion(
    var question: String,
    var options: List<String>,
    var correctIndex: Int,
    var timeSec: Int = 20,
    var points: Int = 10,
    var explanation: String = "",
)

@Composable
private fun QuizContextEditor() {
    val questions = remember {
        mutableStateListOf(
            QuizQuestion(
                question = "پایتخت تاریخی دوره ساسانیان کدام شهر بود؟",
                options = listOf("تیسفون", "اصفهان", "ری", "شیراز"),
                correctIndex = 0,
                timeSec = 20,
                points = 10,
                explanation = "تیسفون پایتخت شکوهمند پادشاهی ساسانیان در بین‌النهرین بود.",
            ),
            QuizQuestion(
                question = "بزرگترین سیاره در منظومه شمسی کدام است؟",
                options = listOf("مریخ", "مشتری (ژوپیتر)", "زحل", "اورانوس"),
                correctIndex = 1,
                timeSec = 15,
                points = 15,
                explanation = "مشتری بزرگترین غول گازی منظومه شمسی است.",
            ),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "بانک سؤالات کوئیز (${questions.size} سؤال)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E382B))
                    .clickable {
                        questions.add(
                            QuizQuestion(
                                question = "سؤال جدید",
                                options = listOf("گزینه ۱", "گزینه ۲", "گزینه ۳", "گزینه ۴"),
                                correctIndex = 0,
                            ),
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("+ افزودن سؤال", color = Color(0xFFB8E62E), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        questions.forEachIndexed { index, q ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF121B17))
                    .border(1.dp, Color(0xFF1E2F26), RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "سؤال شماره ${index + 1}",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFB8E62E),
                            fontSize = 13.sp,
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF1B2A22))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text("زمان: ${q.timeSec}s", color = Color(0xFF00FFB2), fontSize = 10.sp)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF1B2A22))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text("امتیاز: ${q.points}", color = Color(0xFFF59E0B), fontSize = 10.sp)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = q.question,
                        onValueChange = { q.question = it },
                        label = { Text("متن سؤال") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF16221D),
                            unfocusedContainerColor = Color(0xFF16221D),
                            focusedBorderColor = Color(0xFF10B981),
                            unfocusedBorderColor = Color(0xFF22352B),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                        ),
                    )

                    Text(
                        text = "گزینه‌های پاسخ (گزینه صحیح را تیک بزنید):",
                        fontSize = 12.sp,
                        color = Color(0xFF9CA3AF),
                    )

                    q.options.forEachIndexed { optIndex, optText ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (q.correctIndex == optIndex) Color(0xFF1A3326) else Color(0xFF16221D))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            RadioButton(
                                selected = q.correctIndex == optIndex,
                                onClick = { q.correctIndex = optIndex },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFFB8E62E)),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = optText,
                                color = if (q.correctIndex == optIndex) Color(0xFFB8E62E) else Color.White,
                                fontSize = 13.sp,
                                fontWeight = if (q.correctIndex == optIndex) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }

                    if (q.explanation.isNotBlank()) {
                        Text(
                            text = "توضیح پاسخ: ${q.explanation}",
                            fontSize = 11.sp,
                            color = Color(0xFF6B7280),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WordContextEditor() {
    var words by remember { mutableStateOf("ایران، ستاره، خورشید، پرواز، امید") }
    var scrambleEnabled by remember { mutableStateOf(true) }
    var timeLimit by remember { mutableStateOf("۶۰ ثانیه") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("تنظیمات بازی کلمات و جدول", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)

        OutlinedTextField(
            value = words,
            onValueChange = { words = it },
            label = { Text("کلمات هدف (با کاما جدا کنید)") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF16221D),
                unfocusedContainerColor = Color(0xFF16221D),
                focusedBorderColor = Color(0xFF10B981),
                unfocusedBorderColor = Color(0xFF22352B),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("به‌هم‌ریختگی تصادفی حروف (Scramble)", color = Color(0xFF9CA3AF), fontSize = 13.sp)
            FilterChip(
                selected = scrambleEnabled,
                onClick = { scrambleEnabled = !scrambleEnabled },
                label = { Text(if (scrambleEnabled) "فعال" else "غیرفعال") },
            )
        }
    }
}

@Composable
private fun RunnerContextEditor() {
    var speed by remember { mutableStateOf("متوسط (500 px/s)") }
    var obstacleDensity by remember { mutableStateOf("۳ مانع در هر ثانیه") }
    var coinReward by remember { mutableStateOf("۱۰ سکه") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("تنظیمات فیزیک و المان‌های دونده", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF16221D))
                .padding(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("سرعت حرکت بازیکن: $speed", color = Color(0xFFB8E62E), fontSize = 13.sp)
                Text("تراکم موانع: $obstacleDensity", color = Color.White, fontSize = 13.sp)
                Text("ارزش سکه‌ها: $coinReward", color = Color(0xFFF59E0B), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun PuzzleContextEditor() {
    var gridSize by remember { mutableStateOf("۳ در ۳") }
    var moveLimit by remember { mutableStateOf("۵۰ حرکت") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("تنظیمات پازل و چیدمان جورچین", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF16221D))
                .padding(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ابعاد شبکه پازل: $gridSize", color = Color(0xFFB8E62E), fontSize = 13.sp)
                Text("محدودیت حرکات: $moveLimit", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun GenericContextEditor(gameType: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF16221D))
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("پیکربندی سبک $gameType", fontWeight = FontWeight.Bold, color = Color(0xFFB8E62E))
            Text(
                "قوانین، متغیرها و رفتارهای پیش‌فرض برای این بازی تنظیم شده‌اند و از طریق بوم صحنه و منوی درآمدزایی قابل شخصی‌سازی هستند.",
                color = Color(0xFF9CA3AF),
                fontSize = 13.sp,
            )
        }
    }
}

// ============================================================================
// TAB 2: Visual Canvas & Entities
// ============================================================================

@Composable
private fun VisualCanvasTab(vm: EditorViewModel, ui: EditorUi) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Scene Selector Tabs
        SceneSelectorBar(vm = vm, ui = ui)

        // Interactive Canvas
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            CanvasInteractiveArea(vm = vm, ui = ui)
        }

        // Entity Creator Toolbar
        ObjectKindToolbar(onAdd = { kind -> vm.addObject(kind, 100f, 100f) })

        // Inspector Panel for Selected Object
        val currentSelected = ui.selected
        if (currentSelected != null) {
            SelectedObjectInspector(vm = vm, sel = currentSelected)
        }
    }
}

@Composable
private fun SceneSelectorBar(vm: EditorViewModel, ui: EditorUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F1714))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(ui.scenes) { sc ->
                val active = sc.id == ui.activeSceneId
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) Color(0xFF1E382B) else Color(0xFF16221D))
                        .border(1.dp, if (active) Color(0xFF10B981) else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { vm.selectScene(sc.id) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = if (sc.entry) "${sc.name} ★" else sc.name,
                        color = if (active) Color(0xFFB8E62E) else Color(0xFF9CA3AF),
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1E382B))
                .clickable { vm.addScene("صحنه ${ui.scenes.size + 1}") }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text("+ صحنه", color = Color(0xFFB8E62E), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CanvasInteractiveArea(vm: EditorViewModel, ui: EditorUi) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B100E))
            .pointerInput(ui.activeSceneId) {
                detectTapGestures { offset ->
                    val clicked = ui.objects.reversed().firstOrNull { obj ->
                        val t = obj.transform
                        offset.x >= t.x && offset.x <= t.x + t.w && offset.y >= t.y && offset.y <= t.y + t.h
                    }
                    vm.select(clicked?.id)
                }
            }
            .pointerInput(ui.selectedId) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    if (ui.selectedId != null) {
                        vm.moveSelected(dragAmount.x, dragAmount.y)
                    }
                }
            },
    ) {
        val selId = ui.selectedId

        Canvas(modifier = Modifier.fillMaxSize()) {
            // Draw subtle coordinate grid
            val gridStep = 40f
            var x = 0f
            while (x < size.width) {
                drawLine(Color(0xFF14211B), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += gridStep
            }
            var y = 0f
            while (y < size.height) {
                drawLine(Color(0xFF14211B), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += gridStep
            }

            // Draw scene objects
            for (obj in ui.objects) {
                if (!obj.visible) continue
                val t = obj.transform
                val color = kindColor(obj.kind)
                val isSelected = obj.id == selId

                when (obj.kind) {
                    "circle" -> {
                        drawCircle(color = color, radius = t.w / 2f, center = Offset(t.x + t.w / 2f, t.y + t.h / 2f))
                    }
                    "button" -> {
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(t.x, t.y),
                            size = Size(t.w, t.h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
                        )
                    }
                    else -> {
                        drawRect(
                            color = color,
                            topLeft = Offset(t.x, t.y),
                            size = Size(t.w, t.h),
                        )
                    }
                }

                // Selection bounding box
                if (isSelected) {
                    drawRect(
                        color = Color(0xFFB8E62E),
                        topLeft = Offset(t.x - 4f, t.y - 4f),
                        size = Size(t.w + 8f, t.h + 8f),
                        style = Stroke(width = 3f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ObjectKindToolbar(onAdd: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F1714))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("افزودن:", fontSize = 11.sp, color = Color(0xFF9CA3AF), fontWeight = FontWeight.Bold)

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val items = listOf(
                "rect" to "مستطیل",
                "circle" to "دایره",
                "text" to "متن",
                "button" to "دکمه",
                "panel" to "پنل",
                "sprite" to "تصویر",
            )
            items(items) { (kind, labelFa) ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF16221D))
                        .border(1.dp, kindColor(kind).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .clickable { onAdd(kind) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(labelFa, color = kindColor(kind), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SelectedObjectInspector(vm: EditorViewModel, sel: ObjItem) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF121B17))
            .border(1.dp, Color(0xFF1E382B))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "آیتم انتخابی: ${sel.kind} (لایه ${sel.layer})",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFFB8E62E),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "موقعیت: X=${sel.transform.x.toInt()} Y=${sel.transform.y.toInt()} | اندازه: ${sel.transform.w.toInt()}x${sel.transform.h.toInt()}",
                    fontSize = 11.sp,
                    color = Color(0xFF9CA3AF),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF1E382B))
                        .clickable { vm.changeLayer(1) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("لایه +", color = Color.White, fontSize = 11.sp)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF381A1A))
                        .clickable { vm.deleteSelected() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("حذف", color = Color(0xFFEF4444), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ============================================================================
// TAB 3: Monetization Builder (Section 26 & 27)
// ============================================================================

data class EditorProduct(
    val sku: String,
    val title: String,
    val type: String,
    val priceToman: Long,
    val actionText: String,
)

@Composable
private fun MonetizationTab() {
    val products = remember {
        mutableStateListOf(
            EditorProduct("coins_100", "بسته ۱۰۰ سکه طلایی", "مصرفی (Consumable)", 15_000, "افزودن ۱۰۰ سکه به حساب بازیکن"),
            EditorProduct("remove_ads", "حذف دائمی تبلیغات", "غیرمصرفی (Non-consumable)", 35_000, "غیرفعال‌سازی نمایش تمام بنرها و ویدیوها"),
            EditorProduct("vip_monthly", "عضویت ویژه VIP", "اشتراکی (Subscription)", 50_000, "باز کردن مرحله مخفی و دو برابر شدن امتیازات"),
        )
    }

    var showAddDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "درآمدزایی و محصولات درون‌برنامه‌ای",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    text = "تعریف محصولات بازار و مایکت بدون نیاز به نوشتن کد Kotlin",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9CA3AF),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF10B981))
                .clickable { showAddDialog = true }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("+ افزودن محصول جدید به بازی", color = Color(0xFF080C0A), fontWeight = FontWeight.Bold)
        }

        products.forEach { prod ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF16221D))
                    .border(1.dp, Color(0xFF1E382B), RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(prod.title, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        Text("${prod.priceToman} تومان", color = Color(0xFFB8E62E), fontWeight = FontWeight.Bold)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("شناسه: ${prod.sku}", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                        Text("·", fontSize = 11.sp, color = Color(0xFF6B7280))
                        Text(prod.type, fontSize = 11.sp, color = Color(0xFF00FFB2))
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF101915))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "منطق پس از پرداخت: ${prod.actionText}",
                            fontSize = 11.sp,
                            color = Color(0xFFF59E0B),
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var sku by remember { mutableStateOf("") }
        var title by remember { mutableStateOf("") }
        var price by remember { mutableStateOf("20000") }
        var actionType by remember { mutableStateOf("افزودن سکه") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = Color(0xFF16221D),
            title = { Text("افزودن محصول جدید", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("نام محصول (مثال: ۵۰ سکه)") },
                    )
                    OutlinedTextField(
                        value = sku,
                        onValueChange = { sku = it },
                        label = { Text("شناسه محصول (SKU انگلیسی)") },
                    )
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it },
                        label = { Text("قیمت به تومان") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (sku.isNotBlank() && title.isNotBlank()) {
                            products.add(
                                EditorProduct(
                                    sku = sku.trim(),
                                    title = title.trim(),
                                    type = "مصرفی (Consumable)",
                                    priceToman = price.toLongOrNull() ?: 20_000L,
                                    actionText = "افزایش موجودی درون‌برنامه‌ای",
                                ),
                            )
                            showAddDialog = false
                        }
                    },
                ) {
                    Text("افزودن", color = Color(0xFFB8E62E), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("انصراف", color = Color(0xFF9CA3AF))
                }
            },
        )
    }
}

// ============================================================================
// TAB 4: Build & Export (Section 20 & 21)
// ============================================================================

@Composable
private fun BuildAndExportTab(projectName: String, gameType: String) {
    var appId by remember { mutableStateOf("com.baziche.game." + projectName.lowercase().replace(" ", "")) }
    var appNameFa by remember { mutableStateOf(projectName) }
    var versionCode by remember { mutableStateOf("1") }
    var versionName by remember { mutableStateOf("1.0.0") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("تنظیمات ساخت خروجی و شناسه بازی", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)

        OutlinedTextField(
            value = appNameFa,
            onValueChange = { appNameFa = it },
            label = { Text("نام نمایشی بازی") },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = appId,
            onValueChange = { appId = it },
            label = { Text("شناسه پکیج (Package ID)") },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = versionCode,
                    onValueChange = { versionCode = it },
                    label = { Text("کد نسخه") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = versionName,
                    onValueChange = { versionName = it },
                    label = { Text("نام نسخه") },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF16221D))
                .border(1.dp, Color(0xFF1E382B), RoundedCornerShape(14.dp))
                .padding(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("معماری سرور بیلد (Build Pipeline)", fontWeight = FontWeight.Bold, color = Color(0xFF00FFB2))
                Text(
                    "پروژه، دارایی‌ها و تنظیمات فوق با Game Shell ادغام شده و خروجی APK استاندارد و قابل نصب تولید خواهد شد.",
                    fontSize = 12.sp,
                    color = Color(0xFF9CA3AF),
                )
            }
        }
    }
}
