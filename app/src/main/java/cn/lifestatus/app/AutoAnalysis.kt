package cn.lifestatus.app

import android.content.Context
import androidx.work.*
import cn.lifestatus.app.ai.AiClient
import cn.lifestatus.app.data.AnalysisRecord
import cn.lifestatus.app.data.AppRepository
import java.util.concurrent.TimeUnit

/** One coordinator shared by UI and workers, so a background wake-up cannot duplicate an upload. */
object AnalysisRunner {
    private val lock = Any()

    fun run(context: Context, force: Boolean = false): String? = synchronized(lock) {
        val repository = AppRepository(context.applicationContext)
        val settings = repository.loadSettings()
        val config = repository.getAnalysisConfig().takeIf { it.isConfigured } ?: repository.getAiConfig()
        if (!config.isConfigured || settings.scopes.isEmpty() || (!settings.autoAnalyze && !force)) return null
        if (repository.loadEvents().none { !it.deleted }) return null
        val now = System.currentTimeMillis()
        val previous = repository.loadAnalysis()
        val attempted = repository.lastAnalysisAttemptAt()
        if (!force && attempted in 1..now && now - attempted < settings.minAnalysisMinutes.coerceIn(15, 1440) * 60_000L) return null
        val payload = repository.buildAnalysisPayload(now)
        val signature = repository.analysisSignature(payload)
        if (!force && signature == previous?.signature) return null
        repository.markAnalysisAttempt(now)
        val answer = AiClient(config).analyze(payload)
        repository.saveAnalysis(AnalysisRecord(answer, now, signature))
        answer
    }
}

class AutoAnalysisWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        AnalysisRunner.run(applicationContext)
        Result.success()
    } catch (_: Exception) {
        // Do not retry-loop paid requests. The next scheduled pass or user action can try again.
        Result.failure()
    }
}

object AnalysisSchedule {
    private fun network() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun configure(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) {
            manager.cancelUniqueWork("life-periodic-analysis")
            manager.cancelUniqueWork("life-change-analysis")
            return
        }
        val periodic = PeriodicWorkRequestBuilder<AutoAnalysisWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network()).build()
        manager.enqueueUniquePeriodicWork("life-periodic-analysis", ExistingPeriodicWorkPolicy.UPDATE, periodic)
    }

    fun changed(context: Context, enabled: Boolean) {
        if (!enabled) return
        val next = OneTimeWorkRequestBuilder<AutoAnalysisWorker>().setInitialDelay(10, TimeUnit.SECONDS)
            .setConstraints(network()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("life-change-analysis", ExistingWorkPolicy.REPLACE, next)
    }
}
