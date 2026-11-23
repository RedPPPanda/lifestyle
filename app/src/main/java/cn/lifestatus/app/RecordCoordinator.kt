package cn.lifestatus.app

import android.content.Context
import androidx.work.*
import cn.lifestatus.app.ai.AiClient
import cn.lifestatus.app.core.*
import cn.lifestatus.app.data.AppRepository
import java.io.File
import java.util.UUID

/** Persist the user's input before any network call. Work survives activity recreation. */
object RecordCoordinator {
    const val PENDING = "待解析·"

    fun imageFile(context: Context, batch: String): File {
        require(batch.matches(Regex("[a-fA-F0-9-]{36}")))
        return File(File(context.filesDir, "record-images"), "$batch.jpg")
    }

    fun submit(context: Context, text: String, source: String, image: ByteArray? = null): String {
        val id = UUID.randomUUID().toString()
        val at = System.currentTimeMillis()
        val repository = AppRepository(context)
        if (image != null) {
            val file = imageFile(context, id)
            file.parentFile?.mkdirs()
            file.outputStream().use { output -> output.write(image); output.fd.sync() }
        }
        val record = LifeEvent(id = id, batchId = id, kind = EventKind.NOTE, occurredAt = at,
            text = text.ifBlank { "记录这张食物照片或健康数据截图" }, source = PENDING + source)
        try { repository.appendEvents(listOf(record)) }
        catch (error: Exception) { imageFile(context, id).delete(); throw error }
        enqueue(context, id)
        return id
    }

    fun enqueue(context: Context, batch: String) {
        val work = OneTimeWorkRequestBuilder<RecordWorker>().setInputData(workDataOf("batch" to batch)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("life-record-$batch", ExistingWorkPolicy.KEEP, work)
    }

    fun recover(context: Context) {
        AppRepository(context).loadEvents().filter { !it.deleted && it.source.startsWith(PENDING) }
            .map { it.batchId }.distinct().forEach { enqueue(context, it) }
    }

    fun retry(context: Context, batch: String) {
        val repository = AppRepository(context)
        val old = repository.loadEvents().firstOrNull { it.batchId == batch && !it.deleted && it.source == "图片未识别" }
            ?: error("这组记录无需重试")
        require(imageFile(context, batch).exists()) { "本机原图已不存在，请重新选择图片" }
        val pending = old.copy(id = UUID.randomUUID().toString(), source = PENDING + "图片")
        check(repository.replaceBatchIfActive(batch, listOf(pending))) { "这组记录已变更，请刷新后重试" }
        enqueue(context, batch)
    }

    fun remove(context: Context, batch: String) {
        WorkManager.getInstance(context).cancelUniqueWork("life-record-$batch")
        imageFile(context, batch).delete()
    }

    fun cleanupDeleted(context: Context) {
        val groups = AppRepository(context).loadEvents().groupBy { it.batchId }
        groups.filterValues { batch -> batch.isNotEmpty() && batch.all { it.deleted } }.keys
            .filter { it.matches(Regex("[a-fA-F0-9-]{36}")) }.forEach { remove(context, it) }
    }
}

class RecordWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val batch = inputData.getString("batch") ?: return Result.failure()
        val repository = AppRepository(applicationContext)
        val pending = repository.loadEvents().firstOrNull {
            it.batchId == batch && !it.deleted && it.source.startsWith(RecordCoordinator.PENDING)
        } ?: return Result.success()
        val source = pending.source.removePrefix(RecordCoordinator.PENDING)
        val picture = RecordCoordinator.imageFile(applicationContext, batch)
        var message: String
        var parsed: List<LifeEvent>
        try {
            val main = repository.getAiConfig()
            val local = LocalParser.parse(pending.text, pending.occurredAt, source)
            if (picture.exists()) {
                val vision = repository.getVisionConfig().takeIf { it.isConfigured } ?: main
                if (!vision.isConfigured) error("请先配置支持图片的 AI 接口")
                parsed = AiClient(vision).parseImage(picture.readBytes(), "image/jpeg", pending.text, pending.occurredAt, "图片")
                message = "图片识别结果已保存，可查看记录与校准"
            } else if (main.isConfigured) {
                try {
                    parsed = AiClient(main).parse(pending.text, pending.occurredAt, source)
                    if (parsed.isEmpty()) parsed = local.events
                    message = "AI 解析结果已保存，可以查看记录与校准"
                } catch (_: Exception) {
                    parsed = local.events
                    message = "AI 未完成解析，原文已保留并采用基础识别"
                }
            } else { parsed = local.events; message = local.message }
            if (parsed.isEmpty()) parsed = listOf(pending.copy(id = UUID.randomUUID().toString(), source = source))
        } catch (_: Exception) {
            parsed = listOf(pending.copy(id = UUID.randomUUID().toString(), source = if (picture.exists()) "图片未识别" else source))
            message = if (picture.exists()) "图片识别未完成，原图与描述已保存在本机；请检查网络和图片接口配置后重试" else "原文已保存在本机，状态未调整"
        }
        val automaticSteps = repository.loadSettings().stepTracking
        val normalized = parsed.map { event ->
            val candidate = if (automaticSteps && event.kind == EventKind.STEPS)
                event.copy(kind = EventKind.NOTE, value = 0.0, text = event.text + "（已启用系统计步，未重复增加步数；总步数可校准）")
            else event
            candidate.copy(batchId = batch, recordedAt = pending.recordedAt)
        }
        if (repository.replaceBatchIfActive(batch, normalized)) {
            applicationContext.getSharedPreferences("life-operation-status", Context.MODE_PRIVATE).edit()
                .putString("record_message", message).putLong("record_message_at", System.currentTimeMillis()).apply()
            val settings = repository.loadSettings()
            val analysisConfig = repository.getAnalysisConfig().takeIf { it.isConfigured } ?: repository.getAiConfig()
            AnalysisSchedule.changed(applicationContext, settings.autoAnalyze && analysisConfig.isConfigured && settings.scopes.isNotEmpty())
        }
        return Result.success()
    }
}
