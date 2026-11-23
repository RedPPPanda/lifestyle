package cn.lifestatus.app.data

import android.content.Context
import android.util.AtomicFile
import cn.lifestatus.app.core.EventKind
import cn.lifestatus.app.core.LifeEvent
import cn.lifestatus.app.core.Metric
import cn.lifestatus.app.core.RuleConfig
import cn.lifestatus.app.core.StateEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.floor
import kotlin.math.round

/** One process-wide lock keeps foreground UI, sensor service and workers from losing updates. */
class AppRepository(context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.filesDir, "life-state-v1.json"))
    private val secrets = SecretStore(app)
    private val visionSecrets = SecretStore(app, "vision")
    private val analysisSecrets = SecretStore(app, "analysis")

    fun loadEvents(): List<LifeEvent> = synchronized(lock) { decodeEvents(readState()) }

    fun appendEvents(events: List<LifeEvent>) = synchronized(lock) {
        if (events.isEmpty()) return@synchronized
        require(events.size <= 100) { "单次记录过多" }
        events.forEach(::validateEvent)
        val state = readState()
        val previous = decodeEvents(state)
        val existing = previous.mapTo(HashSet()) { it.id }
        val added = events.filter { existing.add(it.id) }
        if (added.isNotEmpty()) {
            state.put("events", encodeEvents(previous + added))
            writeState(state)
        }
    }

    fun undoLastBatch(): Boolean = synchronized(lock) {
        val state = readState()
        val events = decodeEvents(state)
        val last = events.filter { !it.deleted && it.source != SYSTEM_STEPS }
            .maxWithOrNull(compareBy<LifeEvent> { it.recordedAt }.thenBy { it.id })
            ?: return@synchronized false
        val updated = events.map { if (it.batchId == last.batchId) it.copy(deleted = true) else it }
        state.put("events", encodeEvents(updated))
        writeState(state)
        true
    }

    fun deleteBatch(batchId: String) = synchronized(lock) {
        val state = readState()
        val events = decodeEvents(state)
        if (events.none { it.batchId == batchId && !it.deleted }) return@synchronized
        state.put("events", encodeEvents(events.map { if (it.batchId == batchId) it.copy(deleted = true) else it }))
        writeState(state)
    }

    fun loadSettings(): AppSettings = synchronized(lock) { decodeSettings(readState().optJSONObject("settings")) }

    fun saveSettings(settings: AppSettings) = synchronized(lock) {
        val rules = settings.rules
        require(listOf(rules.satietyDecayPerHour, rules.hydrationDecayPerHour, rules.awakeEnergyDecayPerHour,
            rules.sleepRecoveryPerHour, rules.mealGain, rules.waterGainPer250Ml,
            rules.exerciseEnergyCostPerMinute).all { it.isFinite() && it >= 0 && it <= 1000 }) { "状态规则数值无效" }
        val state = readState()
        state.put("settings", encodeSettings(settings))
        writeState(state)
    }

    fun setStepTracking(enabled: Boolean) = synchronized(lock) {
        val state = readState()
        val settings = decodeSettings(state.optJSONObject("settings"))
        state.put("settings", encodeSettings(settings.copy(stepTracking = enabled)))
        writeState(state)
    }

    fun getAiConfig(): AiConfig = secrets.load()
    fun saveAiConfig(config: AiConfig) = secrets.save(config)
    fun getVisionConfig(): AiConfig = visionSecrets.load()
    fun saveVisionConfig(config: AiConfig) = visionSecrets.save(config)
    fun getAnalysisConfig(): AiConfig = analysisSecrets.load()
    fun saveAnalysisConfig(config: AiConfig) = analysisSecrets.save(config)

    /** A canceled pending input cannot be brought back by a late or duplicated network result. */
    fun replaceBatchIfActive(batchId: String, events: List<LifeEvent>): Boolean = synchronized(lock) {
        require(events.size <= 100) { "单次记录过多" }
        events.forEach(::validateEvent)
        val state = readState()
        val previous = decodeEvents(state)
        val pending = previous.filter { it.batchId == batchId && !it.deleted }
        if (pending.isEmpty() || pending.any { it.kind != EventKind.NOTE }) {
            return@synchronized false
        }
        if (events.isEmpty()) return@synchronized false
        val ids = previous.mapTo(HashSet()) { it.id }
        require(events.all { ids.add(it.id) }) { "记录标识重复，本次尚未更新" }
        val old = previous.map { if (it.batchId == batchId && !it.deleted) it.copy(deleted = true) else it }
        state.put("events", encodeEvents(old + events.map { it.copy(batchId = batchId) }))
        writeState(state)
        true
    }

    fun lastAnalysisAttemptAt(): Long = synchronized(lock) {
        readState().optLong("analysis_attempt_at", 0L).coerceAtLeast(0L)
    }

    fun markAnalysisAttempt(at: Long) = synchronized(lock) {
        require(at >= 0) { "分析时间无效" }
        val state = readState()
        state.put("analysis_attempt_at", at)
        writeState(state)
    }

    fun loadAnalysis(): AnalysisRecord? = synchronized(lock) {
        val value = readState().optJSONObject("analysis") ?: return@synchronized null
        AnalysisRecord(value.getString("text"), value.getLong("at"), value.getString("signature"))
    }

    fun saveAnalysis(record: AnalysisRecord) = synchronized(lock) {
        val state = readState()
        state.put("analysis", JSONObject().put("text", record.text.take(16000))
            .put("at", record.at).put("signature", record.signature))
        writeState(state)
    }

    /** No credentials or previous AI answer enter the payload. Excluded categories stay excluded. */
    fun buildAnalysisPayload(now: Long = System.currentTimeMillis()): JSONObject = synchronized(lock) {
        val state = readState()
        val settings = decodeSettings(state.optJSONObject("settings"))
        val events = decodeEvents(state).filter { !it.deleted && it.occurredAt <= now }
        val snapshot = StateEngine.snapshot(events, now, settings.rules)
        val result = JSONObject().put("generated_at", now)
            .put("local_day", Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString())
            .put("selected_scopes", JSONArray(settings.scopes.map { it.name }.sorted()))
            .put("state_model", "可校准的游戏估算；0–100 并非医学测量")
        if (DataScope.STATES in settings.scopes) {
            result.put("states", JSONObject()
                .putNullable("satiety", snapshot.satiety?.let(::oneDecimal))
                .putNullable("hydration", snapshot.hydration?.let(::oneDecimal))
                .putNullable("energy", snapshot.energy?.let(::oneDecimal)))
        }
        if (DataScope.WATER in settings.scopes) result.put("today_water_ml", oneDecimal(snapshot.todayWaterMl))
        if (DataScope.WEIGHT in settings.scopes) {
            result.put("latest_weight", JSONObject().putNullable("kg", snapshot.weightKg)
                .putNullable("measured_at", snapshot.weightAt))
        }
        if (DataScope.ACTIVITY in settings.scopes) {
            val stepSource = snapshot.sources[Metric.STEPS]?.takeIf { it.isNotBlank() }
            val stepTracking = if (settings.stepTracking) "手机计步当前已启用" else "手机计步当前未启用"
            val stepCoverage = if (stepSource == null) {
                if (settings.stepTracking) "$stepTracking，但今日尚无步数记录，不能视为 0 步"
                else "今日尚无步数记录"
            } else {
                "最近有效来源：$stepSource。步数按已导入记录计算，只覆盖记录涵盖的时段，不保证全天；$stepTracking"
            }
            result.put("today_activity", JSONObject().putNullable("steps", stepSource?.let { snapshot.todaySteps })
                .putNullable("step_source", stepSource).put("step_tracking_enabled", settings.stepTracking)
                .put("exercise_minutes", oneDecimal(snapshot.todayExerciseMinutes))
                .putNullable("exercise_source", snapshot.sources[Metric.EXERCISE_MINUTES])
                .put("exercise_coverage", "只含已记录的运动时长，不保证全天；未记录不代表没有运动")
                .put("step_coverage", stepCoverage))
        }
        if (DataScope.SLEEP in settings.scopes) {
            result.put("latest_sleep", JSONObject().putNullable("hours", snapshot.lastSleepHours)
                .putNullable("ended_at", snapshot.lastSleepAt))
        }
        val selected = events.filter { scopeFor(it) in settings.scopes }
        // A multi-event sentence may describe excluded data; omit its full text in that case.
        val textAllowedBatches = events.groupBy { it.batchId }.filterValues { batch ->
            batch.all { scopeFor(it) in settings.scopes }
        }.keys
        val cutoff = now - 7L * 24 * 60 * 60 * 1000
        val recent = selected.filter { it.occurredAt >= cutoff }.sortedBy { it.occurredAt }.takeLast(500)
        val records = JSONArray()
        recent.forEach { event ->
            val item = JSONObject().put("type", event.kind.name).put("value", event.value)
                .put("unit", unitFor(event)).put("occurred_at", event.occurredAt)
                .put("source", event.source.take(40))
            event.metric?.let { item.put("metric", it.name) }
            if (DataScope.RAW_TEXT in settings.scopes && event.batchId in textAllowedBatches) {
                item.put("text", event.text.take(1000))
            }
            records.put(item)
        }
        result.put("records_last_7_days", records)
        result
    }

    fun analysisSignature(payload: JSONObject): String {
        val stable = JSONObject(payload.toString())
        stable.remove("generated_at")
        stable.optJSONObject("states")?.let { states ->
            for (key in listOf("satiety", "hydration", "energy")) {
                if (!states.isNull(key)) states.put(key, floor(states.getDouble(key) / 10.0).toInt() * 10)
            }
        }
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical(stable).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** The counter checkpoint and its increment event are committed in the same atomic transaction. */
    fun recordSensorCounter(counter: Double, bootId: String, at: Long, sessionId: String): Int = synchronized(lock) {
        require(counter.isFinite() && counter >= 0) { "计步传感器数据无效" }
        val state = readState()
        if (state.optString("sensor_session") != sessionId || sessionId.isBlank()) return@synchronized 0
        val day = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val previous = state.optJSONObject("sensor")
        val sameSegment = previous != null && previous.optString("boot") == bootId &&
            previous.optString("day") == day && counter >= previous.optDouble("counter", Double.MAX_VALUE)
        val delta = if (sameSegment) (counter - previous!!.getDouble("counter")).toInt() else 0
        if (delta > 100000) throw IOException("计步传感器增量异常，尚未写入记录")
        val coverageStart = if (sameSegment) previous!!.optLong("coverage_start", at) else at
        if (delta > 0) {
            val event = LifeEvent(kind = EventKind.STEPS, value = delta.toDouble(), occurredAt = at,
                text = "手机计步传感器（启用后增量）", source = SYSTEM_STEPS)
            state.put("events", encodeEvents(decodeEvents(state) + event))
        }
        if (!sameSegment || delta > 0) {
            state.put("sensor", JSONObject().put("boot", bootId).put("day", day)
                .put("counter", counter).put("observed_at", at).put("coverage_start", coverageStart))
            writeState(state)
        }
        delta
    }

    fun resetSensorBaseline() = synchronized(lock) {
        val state = readState()
        state.remove("sensor")
        writeState(state)
    }

    fun prepareSensorSession(): String = synchronized(lock) {
        val state = readState()
        state.optString("sensor_session").takeIf { it.isNotBlank() }?.let { return@synchronized it }
        val session = UUID.randomUUID().toString()
        state.put("sensor_session", session)
        state.remove("sensor")
        writeState(state)
        session
    }

    fun finishSensorSession(sessionId: String? = null) = synchronized(lock) {
        val state = readState()
        if (sessionId != null && state.optString("sensor_session") != sessionId) return@synchronized
        state.remove("sensor_session")
        state.remove("sensor")
        writeState(state)
    }

    fun sensorCoverageStart(): Long? = synchronized(lock) {
        readState().optJSONObject("sensor")?.optLong("coverage_start")?.takeIf { it > 0 }
    }

    private fun readState(): JSONObject {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            return JSONObject().put("version", 1).put("events", JSONArray())
        }
        return try {
            if (file.baseFile.length() > 32 * 1024 * 1024) throw IOException()
            val bytes = file.openRead().use { input ->
                val data = input.readBytes()
                if (data.size > 32 * 1024 * 1024) throw IOException("本地记录体积较大")
                data
            }
            JSONObject(bytes.toString(Charsets.UTF_8)).also {
                if (it.optInt("version") != 1 || it.optJSONArray("events") == null) throw IOException()
            }
        } catch (_: Exception) {
            throw IOException("本地记录读取失败，原文件已保留。请勿清除应用数据。")
        }
    }

    private fun writeState(state: JSONObject) {
        val stream = file.startWrite()
        try {
            stream.write(state.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (_: Exception) {
            file.failWrite(stream)
            throw IOException("保存失败，原有记录已保留")
        }
    }

    private fun encodeEvents(events: List<LifeEvent>): JSONArray = JSONArray().also { array ->
        events.forEach { event ->
            array.put(JSONObject().put("id", event.id).put("batch", event.batchId).put("kind", event.kind.name)
                .put("value", event.value).put("occurred_at", event.occurredAt).put("recorded_at", event.recordedAt)
                .put("text", event.text).put("source", event.source).putNullable("metric", event.metric?.name)
                .put("deleted", event.deleted))
        }
    }

    private fun decodeEvents(state: JSONObject): List<LifeEvent> = try {
        val array = state.getJSONArray("events")
        (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            LifeEvent(id = value.getString("id"), batchId = value.getString("batch"),
                kind = EventKind.valueOf(value.getString("kind")), value = value.getDouble("value"),
                occurredAt = value.getLong("occurred_at"), recordedAt = value.getLong("recorded_at"),
                text = value.getString("text"), source = value.getString("source"),
                metric = if (value.isNull("metric")) null else Metric.valueOf(value.getString("metric")),
                deleted = value.optBoolean("deleted", false)).also(::validateEvent)
        }
    } catch (_: Exception) {
        throw IOException("记录格式无法读取，原文件已保留。请勿清除应用数据。")
    }

    private fun decodeSettings(value: JSONObject?): AppSettings {
        if (value == null) return AppSettings()
        val defaults = AppSettings()
        val rules = value.optJSONObject("rules")
        val base = defaults.rules
        fun rule(key: String, fallback: Double): Double = rules?.optDouble(key, fallback)
            ?.takeIf { it.isFinite() && it in 0.0..1000.0 } ?: fallback
        val scopesJson = value.optJSONArray("scopes")
        val scopes = if (scopesJson == null) defaults.scopes else (0 until scopesJson.length()).mapNotNull {
            runCatching { DataScope.valueOf(scopesJson.getString(it)) }.getOrNull()
        }.toSet()
        return AppSettings(autoAnalyze = value.optBoolean("auto_analyze", defaults.autoAnalyze),
            minAnalysisMinutes = value.optInt("min_analysis_minutes", defaults.minAnalysisMinutes).coerceIn(1, 1440),
            scopes = scopes, stepTracking = value.optBoolean("step_tracking", false),
            rules = RuleConfig(rule("satiety_decay", base.satietyDecayPerHour),
                rule("hydration_decay", base.hydrationDecayPerHour), rule("energy_decay", base.awakeEnergyDecayPerHour),
                rule("sleep_recovery", base.sleepRecoveryPerHour), rule("meal_gain", base.mealGain),
                rule("water_gain", base.waterGainPer250Ml), rule("exercise_cost", base.exerciseEnergyCostPerMinute)))
    }

    private fun encodeSettings(settings: AppSettings): JSONObject = JSONObject()
        .put("auto_analyze", settings.autoAnalyze)
        .put("min_analysis_minutes", settings.minAnalysisMinutes.coerceIn(1, 1440))
        .put("scopes", JSONArray(settings.scopes.map { it.name }.sorted()))
        .put("step_tracking", settings.stepTracking)
        .put("rules", JSONObject().put("satiety_decay", settings.rules.satietyDecayPerHour)
            .put("hydration_decay", settings.rules.hydrationDecayPerHour)
            .put("energy_decay", settings.rules.awakeEnergyDecayPerHour)
            .put("sleep_recovery", settings.rules.sleepRecoveryPerHour)
            .put("meal_gain", settings.rules.mealGain).put("water_gain", settings.rules.waterGainPer250Ml)
            .put("exercise_cost", settings.rules.exerciseEnergyCostPerMinute))

    private fun validateEvent(event: LifeEvent) {
        require(event.id.isNotBlank() && event.batchId.isNotBlank() && event.value.isFinite() &&
            event.occurredAt > 0 && event.recordedAt > 0 && event.text.length <= 10000 && event.source.length <= 100) {
            "记录数据无效"
        }
    }

    private fun scopeFor(event: LifeEvent): DataScope? = when (event.kind) {
        EventKind.MEAL -> DataScope.MEALS
        EventKind.WATER -> DataScope.WATER
        EventKind.SLEEP -> DataScope.SLEEP
        EventKind.WEIGHT -> DataScope.WEIGHT
        EventKind.STEPS, EventKind.STEPS_TOTAL, EventKind.EXERCISE -> DataScope.ACTIVITY
        EventKind.CALIBRATE -> when (event.metric) {
            Metric.WEIGHT -> DataScope.WEIGHT
            Metric.STEPS, Metric.EXERCISE_MINUTES -> DataScope.ACTIVITY
            Metric.SATIETY, Metric.HYDRATION, Metric.ENERGY -> DataScope.STATES
            null -> null
        }
        EventKind.NOTE -> null
    }

    private fun unitFor(event: LifeEvent): String = when (event.kind) {
        EventKind.MEAL -> "份"
        EventKind.WATER -> "mL"
        EventKind.SLEEP -> "小时"
        EventKind.WEIGHT -> "kg"
        EventKind.STEPS, EventKind.STEPS_TOTAL -> "步"
        EventKind.EXERCISE -> "分钟"
        EventKind.CALIBRATE -> when (event.metric) {
            Metric.WEIGHT -> "kg"
            Metric.STEPS -> "步"
            Metric.EXERCISE_MINUTES -> "分钟"
            else -> "/100"
        }
        EventKind.NOTE -> ""
    }

    private fun oneDecimal(value: Double): Double = round(value * 10.0) / 10.0
    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    companion object {
        private val lock = Any()
        const val SYSTEM_STEPS = "系统计步"
    }
}
