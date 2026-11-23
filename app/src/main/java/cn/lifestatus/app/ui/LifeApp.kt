package cn.lifestatus.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.lifestatus.app.core.EventKind
import cn.lifestatus.app.core.LifeEvent
import cn.lifestatus.app.core.Metric
import cn.lifestatus.app.core.RuleConfig
import cn.lifestatus.app.core.Snapshot
import cn.lifestatus.app.core.valueFor
import cn.lifestatus.app.data.AiConfig
import cn.lifestatus.app.data.AnalysisRecord
import cn.lifestatus.app.data.AppSettings
import cn.lifestatus.app.data.DataScope
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private data class LifePalette(
    val page: Color,
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val brand: Color,
    val food: Color,
    val water: Color,
    val energy: Color,
)

private fun palette(dark: Boolean) = if (dark) LifePalette(
    Color(0xFF141B22), Color(0xFF202932), Color(0xFFEDF4F7), Color(0xFFA9BBC5),
    Color(0xFF3B4B57), Color(0xFFA4DCD2), Color(0xFFD8B26D), Color(0xFF78BADB), Color(0xFF85BDA1),
) else LifePalette(
    Color(0xFFF1F4F6), Color.White, Color(0xFF172C36), Color(0xFF536771),
    Color(0xFFDCE5E9), Color(0xFF235C58), Color(0xFF9C742F), Color(0xFF3D82A9), Color(0xFF53836C),
)

/** Stateless application surface. All persistence, permissions and network work belong to the host. */
@Composable
fun LifeApp(
    snapshot: Snapshot,
    events: List<LifeEvent>,
    settings: AppSettings,
    config: AiConfig,
    analysis: AnalysisRecord?,
    notice: String,
    busy: Boolean,
    stepAvailable: Boolean,
    onRecord: (String) -> Boolean,
    onVoice: () -> Unit,
    onCalibrate: (Metric, Double) -> Unit,
    onDeleteBatch: (String) -> Unit,
    onUndo: () -> Unit,
    onSettings: (AppSettings) -> Unit,
    onSaveAi: (AiConfig) -> Unit,
    onToggleSteps: (Boolean) -> Unit,
    onAnalyze: () -> Unit,
    onExport: () -> Unit,
    onPreviewUpload: () -> String,
    visionConfig: AiConfig = AiConfig(),
    analysisConfig: AiConfig = AiConfig(),
    imagePreview: Bitmap? = null,
    onPickImage: () -> Unit = {},
    onRemoveImage: () -> Unit = {},
    onSaveVision: (AiConfig) -> Unit = {},
    onSaveAnalysisAi: (AiConfig) -> Unit = {},
    onRetryBatch: (String) -> Unit = {},
) {
    val dark = isSystemInDarkTheme()
    val colors = palette(dark)
    val softBrand = if (dark) Color(0xFF2C443F) else Color(0xFFE4EFEC)
    val scheme = if (dark) darkColorScheme(
        primary = colors.brand, onPrimary = Color(0xFF112E2A), background = colors.page,
        surface = colors.surface, onSurface = colors.ink, onBackground = colors.ink,
        primaryContainer = softBrand, onPrimaryContainer = colors.ink,
        secondary = colors.brand, secondaryContainer = softBrand, onSecondaryContainer = colors.brand,
        surfaceVariant = colors.page, onSurfaceVariant = colors.muted, outline = colors.line,
    ) else lightColorScheme(
        primary = colors.brand, onPrimary = Color.White, background = colors.page,
        surface = colors.surface, onSurface = colors.ink, onBackground = colors.ink,
        primaryContainer = softBrand, onPrimaryContainer = colors.ink,
        secondary = colors.brand, secondaryContainer = softBrand, onSecondaryContainer = colors.brand,
        surfaceVariant = colors.page, onSurfaceVariant = colors.muted, outline = colors.line,
    )
    var page by rememberSaveable { mutableStateOf(0) }
    var calibration by remember { mutableStateOf<Metric?>(null) }
    var uploadPreview by remember { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    val statusScrollState = rememberLazyListState()
    val recordsScrollState = rememberLazyListState()
    val analysisScrollState = rememberLazyListState()
    val settingsScrollState = rememberLazyListState()
    val pageScrollState = when (page) {
        1 -> recordsScrollState
        2 -> analysisScrollState
        3 -> settingsScrollState
        else -> statusScrollState
    }
    val effectiveAnalysisConfig = analysisConfig.takeIf { it.isConfigured } ?: config
    val effectiveVisionConfig = visionConfig.takeIf { it.isConfigured } ?: config
    val batches = remember(events) {
        events.filterNot { it.deleted }.sortedByDescending { it.recordedAt }.groupBy { it.batchId }.values.toList()
    }

    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            containerColor = colors.page,
            bottomBar = {
                NavigationBar(containerColor = colors.surface) {
                    val tabs = listOf(
                        Triple("状态", Icons.Outlined.SpaceDashboard, 0),
                        Triple("记录", Icons.Outlined.History, 1),
                        Triple("分析", Icons.Outlined.AutoAwesome, 2),
                        Triple("设置", Icons.Outlined.Settings, 3),
                    )
                    tabs.forEach { (title, icon, index) ->
                        NavigationBarItem(
                            selected = page == index, onClick = { page = index },
                            icon = { Icon(icon, contentDescription = null) }, label = { Text(title) },
                        )
                    }
                }
            },
        ) { padding ->
            LazyColumn(
                state = pageScrollState,
                modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("LIFE / DAILY STATUS", style = MaterialTheme.typography.labelSmall, color = colors.muted, letterSpacing = 1.4.sp)
                            Text(listOf("个人状态", "生活记录", "AI 分析", "设置")[page], style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                            Text(formatDate(snapshot.at, "M 月 d 日 · 今天"), style = MaterialTheme.typography.bodySmall, color = colors.muted)
                        }
                        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Text(if (config.isConfigured || visionConfig.isConfigured || analysisConfig.isConfigured) "AI 已配置" else "本机记录", style = MaterialTheme.typography.labelSmall, color = colors.brand)
                    }
                }
                if (notice.isNotBlank()) item {
                    Text(notice, style = MaterialTheme.typography.bodySmall, color = colors.brand, modifier = Modifier.fillMaxWidth())
                }
                when (page) {
                    0 -> {
                        item { SectionHeading("身体状态", "0–100 · 基于记录估算", colors) }
                        item {
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                StateCard("饱腹", snapshot.satiety, Icons.Outlined.Restaurant, colors.food,
                                    "随进食和时间变化", colors, Modifier.weight(1f).fillMaxHeight()) { calibration = Metric.SATIETY }
                                StateCard("水分", snapshot.hydration, Icons.Outlined.WaterDrop, colors.water,
                                    "已记录 ${formatNumber(snapshot.todayWaterMl)} mL", colors, Modifier.weight(1f).fillMaxHeight()) { calibration = Metric.HYDRATION }
                            }
                        }
                        item {
                            EnergyCard(snapshot, colors) { calibration = Metric.ENERGY }
                        }
                        item { SectionHeading("实际记录", "保留原始单位", colors) }
                        item {
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ActualCard(
                                    "体重", snapshot.weightKg?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—", "kg",
                                    Icons.Outlined.MonitorWeight,
                                    snapshot.weightAt?.let { formatDate(it, "M/d HH:mm") } ?: "还没有测量记录",
                                    "记录体重", colors, Modifier.weight(1f).fillMaxHeight(),
                                ) { calibration = Metric.WEIGHT }
                                val hasSteps = settings.stepTracking || snapshot.sources[Metric.STEPS] != null
                                ActualCard(
                                    "今日运动量", if (hasSteps) formatNumber(snapshot.todaySteps.toDouble()) else "--", "步",
                                    Icons.Outlined.DirectionsWalk,
                                    (if (hasSteps) "" else "尚无步数记录\n") + "已记录 ${formatNumber(snapshot.todayExerciseMinutes)} 分钟",
                                    if (settings.stepTracking) "启用后计步 · 调整" else "补记步数", colors, Modifier.weight(1f).fillMaxHeight(),
                                ) { calibration = Metric.STEPS }
                            }
                        }
                        item {
                            Recorder(draft, { draft = it }, busy, config.isConfigured, effectiveVisionConfig.isConfigured,
                                imagePreview, colors, onVoice, onPickImage, onRemoveImage) {
                                val value = draft.trim()
                                if ((value.isNotEmpty() || imagePreview != null) && onRecord(value)) draft = ""
                            }
                        }
                        item {
                            SectionHeading("最近记录", "行为与变化原因", colors)
                        }
                        if (batches.isEmpty()) item { EmptyRecords(colors) }
                        else {
                            items(batches.take(3), key = { "recent-" + it.first().batchId }) { batch ->
                                EventBatch(batch, colors, onDeleteBatch, onRetryBatch)
                            }
                            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { page = 1 }) { Text("查看全部记录") }
                                TextButton(onClick = onUndo) { Icon(Icons.Outlined.Undo, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("撤销上次手动记录") }
                            } }
                        }
                        item {
                            Text("状态按记录与真实经过时间推算；可以校准为新的计算基准。", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                        }
                    }
                    1 -> {
                        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("共 ${batches.size} 条有效记录", color = colors.muted, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onUndo, enabled = batches.isNotEmpty()) { Icon(Icons.Outlined.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("撤销上次手动记录") }
                        } }
                        if (batches.isEmpty()) item { EmptyRecords(colors) }
                        else items(batches, key = { "history-" + it.first().batchId }) { EventBatch(it, colors, onDeleteBatch, onRetryBatch) }
                        item { OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(8.dp)); Text("导出本机记录") } }
                    }
                    2 -> {
                        item {
                            SurfaceCard(colors) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Outlined.CloudSync, null, tint = colors.brand)
                                    Text(if (!effectiveAnalysisConfig.isConfigured) "等待连接 AI" else if (settings.autoAnalyze) "自动分析已开启" else "自动分析已关闭", fontWeight = FontWeight.Medium)
                                }
                                Text(if (effectiveAnalysisConfig.isConfigured) "记录或状态发生变化后合并分析，最短间隔 ${settings.minAnalysisMinutes} 分钟。" else "先连接你的 AI 服务，再启用生活数据分析。", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                                Button(onClick = { if (effectiveAnalysisConfig.isConfigured) onAnalyze() else page = 3 }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (effectiveAnalysisConfig.isConfigured) "立即分析" else "配置 AI 服务")
                                }
                                OutlinedButton(onClick = { uploadPreview = onPreviewUpload() }, modifier = Modifier.fillMaxWidth()) { Text("查看本次发送的数据") }
                            }
                        }
                        item {
                            SurfaceCard(colors) {
                                SectionHeading("最近分析", analysis?.let { formatDate(it.at, "M/d HH:mm") } ?: "尚无结果", colors)
                                SelectionContainer {
                                    Text(analysis?.text ?: "记录一些生活信息，连接 AI 后，这里会显示对近期状态的分析。", style = MaterialTheme.typography.bodyMedium, color = if (analysis == null) colors.muted else colors.ink)
                                }
                            }
                        }
                        item { Text("分析使用你在设置里选择的数据范围。AI 的分析文字不会直接改写状态数值。", style = MaterialTheme.typography.bodySmall, color = colors.muted) }
                    }
                    3 -> {
                        item { AiSettings(config, busy, colors, onSaveAi, title = "主接口 · 文字解析") }
                        item { AiSettings(visionConfig, busy, colors, onSaveVision, title = "图片识别接口", optional = true,
                            helper = "用于食物照片和健康 App 截图，模型需要支持图片输入。留空时沿用主接口。", inheritedConfig = config) }
                        item { AiSettings(analysisConfig, busy, colors, onSaveAnalysisAi, title = "综合分析接口", optional = true,
                            helper = "用于结合所选数据分析近期状态。留空时沿用主接口。", inheritedConfig = config) }
                        item { AnalysisSettings(settings, colors, onSettings) }
                        item {
                            SurfaceCard(colors) {
                                SectionHeading("手机计步", if (stepAvailable) "传感器可用" else "当前设备未发现计步传感器", colors)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("后台系统计步", style = MaterialTheme.typography.bodyMedium)
                                        Text("统计启用后的步数，保持一条后台计步通知。", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                                    }
                                    Switch(checked = settings.stepTracking, onCheckedChange = onToggleSteps, enabled = stepAvailable || settings.stepTracking)
                                }
                                Text("这里读取系统计步传感器。手机健康 App 中已有的历史记录需要它开放的授权接口。", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                            }
                        }
                        item { RuleSettings(settings.rules, colors) { onSettings(settings.copy(rules = it)) } }
                        item {
                            SurfaceCard(colors) {
                                Text("本机数据", fontWeight = FontWeight.Medium)
                                Text("记录保存在手机内。导出文件包含生活记录和规则，不包含 API 密钥。", style = MaterialTheme.typography.bodySmall, color = colors.muted)
                                OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(8.dp)); Text("导出 JSON 记录") }
                            }
                        }
                    }
                }
            }
        }
        calibration?.let { metric ->
            CalibrationDialog(metric, snapshot.valueFor(metric), onDismiss = { calibration = null }) { value ->
                onCalibrate(metric, value)
                calibration = null
            }
        }
        uploadPreview?.let { text ->
            AlertDialog(
                onDismissRequest = { uploadPreview = null },
                title = { Text("本次分析发送的数据") },
                text = {
                    SelectionContainer {
                        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
                    }
                },
                confirmButton = { TextButton(onClick = { uploadPreview = null }) { Text("关闭") } },
            )
        }
    }
}

@Composable
private fun SectionHeading(title: String, context: String, colors: LifePalette) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (context.isNotBlank()) Text(context, style = MaterialTheme.typography.labelSmall, color = colors.muted, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun SurfaceCard(colors: LifePalette, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = colors.surface), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun StateCard(
    label: String, value: Double?, icon: ImageVector, accent: Color, subtitle: String,
    colors: LifePalette, modifier: Modifier = Modifier, onCalibrate: () -> Unit,
) {
    Card(onClick = onCalibrate, colors = CardDefaults.cardColors(containerColor = colors.surface), shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.background(colors.page, RoundedCornerShape(8.dp)).padding(5.dp)) { Icon(icon, null, Modifier.size(17.dp), tint = accent) }
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value?.roundToInt()?.toString() ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
                Text(" /100", style = MaterialTheme.typography.labelSmall, color = colors.muted, modifier = Modifier.padding(bottom = 4.dp))
            }
            LinearProgressIndicator(progress = { ((value ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(7.dp), color = accent, trackColor = colors.page, drawStopIndicator = {})
            Text(if (value == null) "先记录或校准" else subtitle, style = MaterialTheme.typography.labelSmall, color = colors.muted)
            Text("估算 · 校准", style = MaterialTheme.typography.labelMedium, color = colors.brand)
        }
    }
}

@Composable
private fun EnergyCard(snapshot: Snapshot, colors: LifePalette, onCalibrate: () -> Unit) {
    Card(onClick = onCalibrate, colors = CardDefaults.cardColors(containerColor = colors.surface), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(colors.page, RoundedCornerShape(8.dp)).padding(5.dp)) { Icon(Icons.Outlined.Bolt, null, Modifier.size(17.dp), tint = colors.energy) }
                Text("精力", Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(snapshot.energy?.roundToInt()?.toString() ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                Text(" /100", style = MaterialTheme.typography.labelSmall, color = colors.muted)
            }
            LinearProgressIndicator(progress = { ((snapshot.energy ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(7.dp), color = colors.energy, trackColor = colors.page, drawStopIndicator = {})
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(snapshot.lastSleepHours?.let { "最近睡眠 ${formatNumber(it)} 小时" } ?: "先记录睡眠或校准", style = MaterialTheme.typography.labelSmall, color = colors.muted, modifier = Modifier.weight(1f))
                Text("估算 · 校准", style = MaterialTheme.typography.labelMedium, color = colors.brand)
            }
        }
    }
}

@Composable
private fun ActualCard(
    label: String, value: String, unit: String, icon: ImageVector, subtitle: String, action: String,
    colors: LifePalette, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = colors.surface), shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { Icon(icon, null, Modifier.size(16.dp), tint = colors.muted); Text(label, style = MaterialTheme.typography.labelMedium, color = colors.muted) }
            Row(verticalAlignment = Alignment.Bottom) { Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium); Text(" $unit", style = MaterialTheme.typography.labelSmall, color = colors.muted, modifier = Modifier.padding(bottom = 3.dp)) }
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = colors.muted)
            Text(action, style = MaterialTheme.typography.labelMedium, color = colors.brand)
        }
    }
}

@Composable
private fun Recorder(
    draft: String, onDraft: (String) -> Unit, busy: Boolean, aiConnected: Boolean, visionConnected: Boolean,
    imagePreview: Bitmap?, colors: LifePalette, onVoice: () -> Unit, onPickImage: () -> Unit,
    onRemoveImage: () -> Unit, onRecord: () -> Unit,
) {
    SurfaceCard(colors) {
        Text("记下刚刚做了什么", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        OutlinedTextField(value = draft, onValueChange = onDraft, placeholder = { Text("例如：刚刚喝了 250 毫升水") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), enabled = !busy)
        if (imagePreview != null) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                ComposeImage(bitmap = imagePreview.asImageBitmap(), contentDescription = "待识别的图片", modifier = Modifier.fillMaxWidth().height(156.dp).background(colors.page, RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("图片已附加，点击记录后识别", style = MaterialTheme.typography.bodySmall, color = colors.muted, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRemoveImage, enabled = !busy) { Text("移除") }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onVoice, enabled = !busy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 7.dp)) { Icon(Icons.Outlined.Mic, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("语音") }
            OutlinedButton(onClick = onPickImage, enabled = !busy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 7.dp)) { Icon(Icons.Outlined.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("图片") }
            Button(onClick = onRecord, enabled = (draft.isNotBlank() || imagePreview != null) && !busy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 7.dp)) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("记录") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            AssistChip(onClick = { onDraft("刚刚喝了250毫升水") }, label = { Text("喝水", style = MaterialTheme.typography.labelMedium) }, enabled = !busy)
            AssistChip(onClick = { onDraft("刚刚吃了一份正餐") }, label = { Text("吃饭", style = MaterialTheme.typography.labelMedium) }, enabled = !busy)
            AssistChip(onClick = { onDraft("刚刚睡了8小时") }, label = { Text("睡眠", style = MaterialTheme.typography.labelMedium) }, enabled = !busy)
        }
        Text(when {
            imagePreview != null && visionConnected -> "图片识别后自动更新，可查看记录并校准"
            imagePreview != null -> "图片已附加，请先在设置里配置图片识别服务"
            aiConnected -> "AI 理解后自动更新，可查看记录并校准"
            else -> "基础识别已可用，连接 AI 后理解更多表达"
        }, style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
}

@Composable
private fun EmptyRecords(colors: LifePalette) {
    SurfaceCard(colors) {
        Text("从一条生活记录开始", fontWeight = FontWeight.Medium)
        Text("记录吃饭、喝水、睡眠或运动。状态会随记录与时间变化。", style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
}

@Composable
private fun EventBatch(batch: List<LifeEvent>, colors: LifePalette, onDeleteBatch: (String) -> Unit, onRetryBatch: (String) -> Unit) {
    val first = batch.first()
    val title = batch.map { it.text }.firstOrNull { it.isNotBlank() } ?: "生活记录"
    val summaries = batch.map(::eventSummary).distinct().joinToString(" · ")
    Card(colors = CardDefaults.cardColors(containerColor = colors.surface), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 13.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(summaries, style = MaterialTheme.typography.bodySmall, color = colors.muted)
                Text("${formatDate(first.occurredAt, "M/d HH:mm")} · ${first.source}", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                if (batch.any { it.source.contains("图片未识别") }) TextButton(onClick = { onRetryBatch(first.batchId) }) { Text("重试识别") }
            }
            IconButton(onClick = { onDeleteBatch(first.batchId) }) { Icon(Icons.Outlined.DeleteOutline, "删除这条记录", Modifier.size(19.dp), tint = colors.muted) }
        }
    }
}

private fun eventSummary(event: LifeEvent): String = when (event.kind) {
    EventKind.MEAL -> "进食 ${formatNumber(event.value)} 份 · 影响饱腹估算"
    EventKind.WATER -> "饮水 ${formatNumber(event.value)} mL · 影响水分估算"
    EventKind.SLEEP -> "睡眠 ${formatNumber(event.value)} 小时 · 影响精力估算"
    EventKind.WEIGHT -> "体重 ${formatNumber(event.value)} kg"
    EventKind.EXERCISE -> "运动 ${formatNumber(event.value)} 分钟"
    EventKind.STEPS -> "步数 +${formatNumber(event.value)}"
    EventKind.STEPS_TOTAL -> "今日总步数 ${formatNumber(event.value)}"
    EventKind.CALIBRATE -> "${metricLabel(event.metric)}校准为 ${formatNumber(event.value)} ${metricUnit(event.metric)}"
    EventKind.NOTE -> "保留原文，未调整状态"
}

private fun metricLabel(metric: Metric?): String = when (metric) {
    Metric.SATIETY -> "饱腹"
    Metric.HYDRATION -> "水分"
    Metric.ENERGY -> "精力"
    Metric.WEIGHT -> "体重"
    Metric.STEPS -> "今日步数"
    Metric.EXERCISE_MINUTES -> "今日运动时长"
    null -> "状态"
}

private fun metricUnit(metric: Metric?): String = when (metric) {
    Metric.WEIGHT -> "kg"
    Metric.STEPS -> "步"
    Metric.EXERCISE_MINUTES -> "分钟"
    else -> "/100"
}

@Composable
private fun CalibrationDialog(metric: Metric, current: Double?, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var input by remember(metric) { mutableStateOf(current?.let { formatNumber(it).replace(",", "") } ?: "") }
    val maximum = when (metric) { Metric.WEIGHT -> 500.0; Metric.STEPS -> 200000.0; Metric.EXERCISE_MINUTES -> 1440.0; else -> 100.0 }
    val minimum = if (metric == Metric.WEIGHT) 1.0 else 0.0
    val parsed = input.toDoubleOrNull()
    val valid = parsed != null && parsed.isFinite() && parsed in minimum..maximum && (metric != Metric.STEPS || parsed % 1.0 == 0.0)
    val estimated = metric in setOf(Metric.SATIETY, Metric.HYDRATION, Metric.ENERGY)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (estimated) "校准${metricLabel(metric)}" else "记录${metricLabel(metric)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (estimated) "校准后，以此刻的数值作为新的计算基准。原有生活记录会保留。" else "保存实际数值，同时记录时间和修改来源。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true, label = { Text(metricLabel(metric)) }, suffix = { Text(metricUnit(metric)) },
                    keyboardOptions = KeyboardOptions(keyboardType = if (metric == Metric.STEPS) KeyboardType.Number else KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("范围 ${formatNumber(minimum)}–${formatNumber(maximum)} ${metricUnit(metric)}") })
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.takeIf { valid }?.let(onSave) }, enabled = valid) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AiSettings(
    config: AiConfig,
    busy: Boolean,
    colors: LifePalette,
    onSave: (AiConfig) -> Unit,
    title: String = "AI 服务",
    optional: Boolean = false,
    helper: String = "填写支持 Chat Completions 接口的 HTTPS 服务。当前文字会用于解析，历史数据按下方范围参与分析。",
    inheritedConfig: AiConfig = AiConfig(),
) {
    var address by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var model by remember(config.model) { mutableStateOf(config.model) }
    var key by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    var expanded by rememberSaveable(title) { mutableStateOf(!optional) }
    SurfaceCard(colors) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(if (config.isConfigured) "独立配置已保存" else if (optional) "沿用主接口" else "尚未配置", style = MaterialTheme.typography.labelSmall, color = colors.muted)
            }
            if (optional) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "配置") }
        }
        Text(helper, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { address = "https://api.deepseek.com"; model = "deepseek-flash" }, enabled = !busy, label = { Text("DeepSeek 预设") })
                if (optional && inheritedConfig.isConfigured) TextButton(onClick = {
                    address = inheritedConfig.baseUrl; model = inheritedConfig.model; key = inheritedConfig.apiKey
                }, enabled = !busy) { Text("复制主配置") }
            }
            OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("服务地址") }, placeholder = { Text("https://你的服务地址/v1") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API 密钥") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Button(onClick = { onSave(config.copy(baseUrl = address.trim(), model = model.trim(), apiKey = key.trim())) }, enabled = address.isNotBlank() && model.isNotBlank() && key.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text("保存接口配置") }
            if (optional) TextButton(onClick = {
                address = ""; model = ""; key = ""; onSave(AiConfig()); expanded = false
            }, enabled = !busy) { Text("改为沿用主接口") }
            else if (config.isConfigured) TextButton(onClick = {
                address = ""; model = ""; key = ""; onSave(AiConfig())
            }, enabled = !busy) { Icon(Icons.Outlined.LinkOff, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("断开主接口") }
            Text("预设只填地址和模型，保存后才生效。密钥只在手机内加密保存。", style = MaterialTheme.typography.labelSmall, color = colors.muted)
        }
        if (!optional) Text("文字解析、图片提取与综合分析分别使用独立提示模板。", style = MaterialTheme.typography.labelSmall, color = colors.muted)
    }
}

@Composable
private fun AnalysisSettings(settings: AppSettings, colors: LifePalette, onSettings: (AppSettings) -> Unit) {
    SurfaceCard(colors) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自动分析", fontWeight = FontWeight.Medium)
                Text("记录或状态变化时触发", style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            Switch(checked = settings.autoAnalyze, onCheckedChange = { onSettings(settings.copy(autoAnalyze = it)) })
        }
        Text("最短分析间隔", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 60).forEach { minutes ->
                FilterChip(selected = settings.minAnalysisMinutes == minutes, onClick = { onSettings(settings.copy(minAnalysisMinutes = minutes)) }, label = { Text("$minutes 分钟") })
            }
        }
        HorizontalDivider(color = colors.line)
        Text("参与分析的数据", style = MaterialTheme.typography.bodyMedium)
        DataScope.entries.forEach { scope ->
            val checked = scope in settings.scopes
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox) { selected ->
                    onSettings(settings.copy(scopes = if (selected) settings.scopes + scope else settings.scopes - scope))
                }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(scope.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("范围只控制综合分析；解析新文字或图片时，会发送本次提交的内容。原始描述只随已选中的记录类型参与综合分析。关闭全部数据范围后，不会进行综合分析。", style = MaterialTheme.typography.bodySmall, color = colors.muted)
    }
}

@Composable
private fun RuleSettings(rules: RuleConfig, colors: LifePalette, onSave: (RuleConfig) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val original = listOf(rules.satietyDecayPerHour, rules.hydrationDecayPerHour, rules.awakeEnergyDecayPerHour, rules.sleepRecoveryPerHour, rules.mealGain, rules.waterGainPer250Ml, rules.exerciseEnergyCostPerMinute)
    var entries by remember(rules) { mutableStateOf(original.map { formatNumber(it).replace(",", "") }) }
    val labels = listOf("饱腹下降 / 小时", "水分下降 / 小时", "清醒时精力下降 / 小时", "睡眠恢复 / 小时", "一份正餐增加饱腹", "250 mL 水增加水分", "运动消耗精力 / 分钟")
    val values = entries.map { it.toDoubleOrNull() }
    val valid = values.withIndex().all { (index, value) -> value != null && value.isFinite() && value >= 0 && value <= (if (index == 6) 10 else 100) }
    SurfaceCard(colors) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("状态变化规则", fontWeight = FontWeight.Medium)
                Text("初始规则可调整，也可以随时校准状态", style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "调整") }
        }
        Text("0–100 是用于生活管理的游戏估算值；这些初始参数会随着使用体验调整。", style = MaterialTheme.typography.bodySmall, color = colors.muted)
        if (expanded) {
            labels.forEachIndexed { index, label ->
                OutlinedTextField(value = entries[index], onValueChange = { value -> entries = entries.toMutableList().also { it[index] = value } }, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = {
                if (valid) onSave(RuleConfig(values[0]!!, values[1]!!, values[2]!!, values[3]!!, values[4]!!, values[5]!!, values[6]!!))
            }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("保存变化规则") }
        }
    }
}

private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) String.format(Locale.US, "%,.0f", value) else String.format(Locale.US, "%,.1f", value)

private fun formatDate(timestamp: Long, pattern: String): String = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern, Locale.CHINA))
