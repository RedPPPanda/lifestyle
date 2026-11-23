package cn.lifestatus.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.*
import cn.lifestatus.app.ai.AiClient
import cn.lifestatus.app.core.*
import cn.lifestatus.app.data.*
import cn.lifestatus.app.platform.SpeechInput
import cn.lifestatus.app.platform.StepTrackingService
import cn.lifestatus.app.ui.LifeApp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private lateinit var repository: AppRepository
    private lateinit var speech: SpeechInput
    private var events by mutableStateOf<List<LifeEvent>>(emptyList())
    private var settings by mutableStateOf(AppSettings())
    private var config by mutableStateOf(AiConfig())
    private var visionConfig by mutableStateOf(AiConfig())
    private var analysisConfig by mutableStateOf(AiConfig())
    private var analysis by mutableStateOf<AnalysisRecord?>(null)
    private var now by mutableLongStateOf(System.currentTimeMillis())
    private var notice by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var imagePreview by mutableStateOf<Bitmap?>(null)
    private var pendingImageBytes: ByteArray? = null
    private var lastRecordMessageAt = 0L
    private var previousStateBand = ""

    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) { prepareImage(uri) }
                imagePreview = prepared.first; pendingImageBytes = prepared.second
                notice = "图片已附加，点击记录后识别"
            } catch (error: Exception) { notice = "图片未能读取：${safeError(error)}" }
        }
    }

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else notice = "未授予麦克风权限，可以继续使用文字记录"
    }
    private val movementPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            activateTracking()
        } else notice = "未授予运动权限，计步尚未开启"
    }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val document = buildExport()
                    contentResolver.openOutputStream(uri)?.use { it.write(document.toByteArray(Charsets.UTF_8)) }
                        ?: error("无法打开目标文件")
                }
                notice = "记录已导出，文件不包含 AI 密钥"
            } catch (error: Exception) { notice = "导出失败：${safeError(error)}" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = AppRepository(applicationContext)
        speech = SpeechInput(this)
        reload()
        RecordCoordinator.recover(applicationContext)
        configureAnalysis()
        setContent {
            val snapshot = remember(events, now, settings.rules) { StateEngine.snapshot(events, now, settings.rules) }
            LifeApp(
                snapshot = snapshot, events = events, settings = settings, config = config,
                analysis = analysis, notice = notice, busy = busy,
                stepAvailable = StepTrackingService.isAvailable(this),
                onRecord = { record(it, "文字") }, onVoice = { voice() },
                onCalibrate = { metric, value -> calibrate(metric, value) },
                onDeleteBatch = { batch -> if (change { repository.deleteBatch(batch) }) { RecordCoordinator.remove(applicationContext, batch); notice = "已移除这组记录，状态已重算" } },
                onUndo = { change {
                    if (!repository.undoLastBatch()) notice = "没有可撤销的手动记录"
                    else { RecordCoordinator.cleanupDeleted(applicationContext); notice = "已撤销最近一次手动记录，状态已重算" }
                } },
                onSettings = { saveSettings(it) }, onSaveAi = { saveAi(it) },
                onToggleSteps = { toggleTracking(it) }, onAnalyze = { analyzeNow() },
                onExport = { export() }, onPreviewUpload = { repository.buildAnalysisPayload(System.currentTimeMillis()).toString(2) },
                visionConfig = visionConfig, analysisConfig = analysisConfig, imagePreview = imagePreview,
                onPickImage = { photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemoveImage = { imagePreview = null; pendingImageBytes = null; notice = "图片已移除" },
                onSaveVision = { saveAi(it, "vision") }, onSaveAnalysisAi = { saveAi(it, "analysis") },
                onRetryBatch = { batch ->
                    try { RecordCoordinator.retry(applicationContext, batch); reload(); notice = "正在重新识别图片" }
                    catch (error: Exception) { notice = "暂不能重试：${safeError(error)}" }
                }
            )
        }
        lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                reload(readConfig = false)
                val snapshot = StateEngine.snapshot(events, now, settings.rules)
                val band = listOf(snapshot.satiety, snapshot.hydration, snapshot.energy).joinToString("/") { it?.div(10)?.toInt()?.toString() ?: "-" }
                if (band != previousStateBand) {
                    if (previousStateBand.isNotEmpty()) AnalysisSchedule.changed(applicationContext, canAutoAnalyze())
                    previousStateBand = band
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::repository.isInitialized) {
            reload()
            if (settings.stepTracking && (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)) {
                try { StepTrackingService.start(this) } catch (_: Exception) { notice = "后台计步尚未恢复，请重新打开计步开关" }
            }
        }
    }
    override fun onDestroy() { if (::speech.isInitialized) speech.destroy(); super.onDestroy() }
    override fun onStop() { if (::speech.isInitialized && speech.isListening) speech.stop(); super.onStop() }

    private fun reload(readConfig: Boolean = true) {
        try {
            events = repository.loadEvents()
            settings = repository.loadSettings()
            analysis = repository.loadAnalysis()
            if (readConfig) {
                config = repository.getAiConfig()
                visionConfig = repository.getVisionConfig()
                analysisConfig = repository.getAnalysisConfig()
            }
            val status = getSharedPreferences("life-operation-status", MODE_PRIVATE)
            val messageAt = status.getLong("record_message_at", 0L)
            if (messageAt > lastRecordMessageAt) {
                notice = status.getString("record_message", "") ?: ""
                lastRecordMessageAt = messageAt
            }
            now = System.currentTimeMillis()
        } catch (error: Exception) { notice = "读取本机数据失败：${safeError(error)}" }
    }

    private fun canAutoAnalyze() = settings.autoAnalyze && (analysisConfig.isConfigured || config.isConfigured) && settings.scopes.isNotEmpty()
    private fun configureAnalysis() { AnalysisSchedule.configure(applicationContext, canAutoAnalyze()) }

    private fun change(action: () -> Unit): Boolean {
        return try { action(); reload(); AnalysisSchedule.changed(applicationContext, canAutoAnalyze()); true }
        catch (error: Exception) { notice = "保存失败：${safeError(error)}"; false }
    }

    private fun record(input: String, source: String): Boolean {
        val text = input.trim()
        if (text.isEmpty() && pendingImageBytes == null) { notice = "先写下做了什么，或附加一张图片"; return false }
        if (text.length > 4000) { notice = "这条记录太长，请控制在 4000 字内"; return false }
        if (pendingImageBytes != null && !visionConfig.isConfigured && !config.isConfigured) {
            notice = "请先在设置中连接支持图片的 AI，图片仍已附加"; return false
        }
        return try {
            RecordCoordinator.submit(applicationContext, text, if (pendingImageBytes == null) source else "图片", pendingImageBytes)
            pendingImageBytes = null; imagePreview = null
            reload(); notice = "原始记录已保存，正在解析"
            true
        } catch (error: Exception) { notice = "未能保存记录：${safeError(error)}"; false }
    }

    private fun calibrate(metric: Metric, value: Double) {
        val valid = value.isFinite() && when (metric) {
            Metric.SATIETY, Metric.HYDRATION, Metric.ENERGY -> value in 0.0..100.0
            Metric.WEIGHT -> value in 1.0..500.0
            Metric.STEPS -> value in 0.0..200000.0 && value == value.toLong().toDouble()
            Metric.EXERCISE_MINUTES -> value in 0.0..1440.0
        }
        if (!valid) { notice = "请输入有效范围内的数值"; return }
        val at = System.currentTimeMillis()
        val event = LifeEvent(kind = EventKind.CALIBRATE, metric = metric, value = value, occurredAt = at,
            text = "手动校准 ${metricName(metric)}：$value", source = "手动校准")
        if (change { repository.appendEvents(listOf(event)) }) notice = "已校准${metricName(metric)}，以此刻为新的基准"
    }

    private fun saveSettings(next: AppSettings) {
        if (change { repository.saveSettings(next) }) { configureAnalysis(); notice = "设置已保存" }
    }

    private fun saveAi(next: AiConfig, channel: String = "main") {
        try {
            if (next.baseUrl.isNotBlank() || next.model.isNotBlank() || next.apiKey.isNotBlank()) {
                require(next.isConfigured) { "请填写服务地址、模型和 API 密钥" }
                AiClient.validateConfig(next)
                val uri = java.net.URI(next.baseUrl.trim())
                require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
                    "请使用 HTTPS 服务地址，地址中不要包含密钥或查询参数"
                }
            }
            val normalized = next.copy(baseUrl = next.baseUrl.trim(), model = next.model.trim(), apiKey = next.apiKey.trim())
            when (channel) {
                "vision" -> repository.saveVisionConfig(normalized)
                "analysis" -> repository.saveAnalysisConfig(normalized)
                else -> repository.saveAiConfig(normalized)
            }
            reload(); configureAnalysis()
            AnalysisSchedule.changed(applicationContext, canAutoAnalyze())
            val label = when (channel) { "vision" -> "图片识别"; "analysis" -> "综合分析"; else -> "主" }
            notice = if (next.isConfigured) "${label}接口配置已加密保存在本机"
                else if (channel == "main") "主接口已清空" else "${label}接口将沿用主接口"
        } catch (error: Exception) { notice = "配置未保存：${safeError(error)}" }
    }

    private fun voice() {
        if (busy) { notice = "上一条正在处理，请稍等"; return }
        if (speech.isListening) { speech.stop(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        else startVoice()
    }

    private fun startVoice() {
        speech.start(onText = { text -> record(text, "语音") }, onStatus = { notice = it })
    }

    private fun toggleTracking(enabled: Boolean) {
        if (!enabled) {
            try { StepTrackingService.stop(this); saveSettings(settings.copy(stepTracking = false)); notice = "已关闭后台计步" }
            catch (error: Exception) { notice = "关闭计步失败：${safeError(error)}" }
            return
        }
        if (!StepTrackingService.isAvailable(this)) { notice = "当前设备未提供计步传感器，可以手动记录运动"; return }
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED)
            required += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            required += Manifest.permission.POST_NOTIFICATIONS
        if (required.isEmpty()) activateTracking() else movementPermissions.launch(required.toTypedArray())
    }

    private fun activateTracking() {
        try {
            repository.saveSettings(settings.copy(stepTracking = true))
            StepTrackingService.start(this)
            reload(); notice = "已开启系统计步，从启用时开始累计"
        } catch (error: Exception) {
            repository.saveSettings(settings.copy(stepTracking = false)); reload()
            notice = "计步未能启动：${safeError(error)}"
        }
    }

    private fun analyzeNow() {
        if (busy) return
        if (!analysisConfig.isConfigured && !config.isConfigured) { notice = "先在设置里连接 AI"; return }
        if (settings.scopes.isEmpty()) { notice = "先选择允许参与分析的数据范围"; return }
        busy = true
        lifecycleScope.launch {
            try {
                val answer = withContext(Dispatchers.IO) { AnalysisRunner.run(applicationContext, force = true) }
                reload(); notice = if (answer == null) "当前没有可分析的数据" else "AI 分析已更新"
            } catch (error: Exception) { notice = "分析未完成：${safeError(error)}" }
            finally { busy = false }
        }
    }

    private fun export() {
        try {
            exportDocument.launch("个人状态-${LocalDate.now()}.json")
        } catch (error: Exception) { notice = "无法准备导出：${safeError(error)}" }
    }

    private fun buildExport(): String {
            val rows = JSONArray()
            repository.loadEvents().forEach { event -> rows.put(JSONObject().apply {
                put("id", event.id); put("batchId", event.batchId); put("kind", event.kind.name)
                put("value", event.value); put("occurredAt", event.occurredAt); put("recordedAt", event.recordedAt)
                put("text", event.text); put("source", event.source); put("metric", event.metric?.name ?: JSONObject.NULL); put("deleted", event.deleted)
            }) }
            val rules = repository.loadSettings().rules
            return JSONObject().apply {
                put("schema", "life-status-export-v1"); put("exportedAt", System.currentTimeMillis()); put("events", rows)
                put("rules", JSONObject().apply {
                    put("satietyDecayPerHour", rules.satietyDecayPerHour); put("hydrationDecayPerHour", rules.hydrationDecayPerHour)
                    put("awakeEnergyDecayPerHour", rules.awakeEnergyDecayPerHour); put("sleepRecoveryPerHour", rules.sleepRecoveryPerHour)
                    put("mealGain", rules.mealGain); put("waterGainPer250Ml", rules.waterGainPer250Ml)
                    put("exerciseEnergyCostPerMinute", rules.exerciseEnergyCostPerMinute)
                })
            }.toString(2)
    }

    private fun prepareImage(uri: Uri): Pair<Bitmap, ByteArray> {
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                val scale = minOf(1.0, 1600.0 / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片格式无法识别" }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: error("图片无法读取")
        }
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val bytes = stream.toByteArray()
        require(bytes.size <= 4 * 1024 * 1024) { "图片较大，请裁剪后重试" }
        return bitmap to bytes
    }

    private fun safeError(error: Exception): String {
        val message = error.message?.take(240) ?: "操作未完成，请重试"
        return listOf(config, visionConfig, analysisConfig).fold(message) { text, item ->
            if (item.apiKey.isNotBlank()) text.replace(item.apiKey, "***") else text
        }
    }

    private fun metricName(metric: Metric) = when (metric) {
        Metric.SATIETY -> "饱腹"; Metric.HYDRATION -> "水分"; Metric.ENERGY -> "精力"
        Metric.WEIGHT -> "体重"; Metric.STEPS -> "步数"; Metric.EXERCISE_MINUTES -> "运动时长"
    }
}
