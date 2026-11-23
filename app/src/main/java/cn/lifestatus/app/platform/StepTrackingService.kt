package cn.lifestatus.app.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import cn.lifestatus.app.data.AppRepository
import java.util.concurrent.Executors

/** Counts observed increments after activation, never claims to read another health app's history. */
class StepTrackingService : Service(), SensorEventListener {
    private lateinit var repository: AppRepository
    private lateinit var sensors: SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var latestCounter: Double? = null
    private var latestAt = 0L
    private var scheduled = false
    private var bootId = ""
    private var sessionId = ""
    private var active = false
    private var closing = false
    private val flush = Runnable {
        scheduled = false
        queueCheckpoint()
    }

    override fun onCreate() {
        super.onCreate()
        repository = AppRepository(this)
        sensors = getSystemService(SensorManager::class.java)
        bootId = readBootId(this)
        instance = this
        sessionId = repository.prepareSensorSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop()
            return START_NOT_STICKY
        }
        if (!hasPermission(this) || !isAvailable(this)) {
            setStatus(this, "计步不可用：请检查运动权限和计步传感器")
            disableTracking()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!runCatching { repository.loadSettings().stepTracking }.getOrDefault(false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "后台计步", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "记录启用后的手机步数增量"; setShowBadge(false) })
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            else startForeground(NOTIFICATION_ID, notification)
            if (!active) {
                val sensor = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: throw IllegalStateException()
                if (!sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 10_000_000)) throw IllegalStateException()
                active = true
                setStatus(this, "启用后计步中；不包含启用前的健康 App 历史步数")
            }
        } catch (_: Exception) {
            setStatus(this, "后台计步启动失败，请检查运动权限及系统后台限制")
            disableTracking()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!active || event == null || event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val counter = event.values.firstOrNull()?.toDouble() ?: return
        if (!counter.isFinite() || counter < 0) return
        val ageMillis = ((SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000).coerceAtLeast(0)
        val at = System.currentTimeMillis() - ageMillis.coerceAtMost(24L * 60 * 60 * 1000)
        val first = latestCounter == null
        latestCounter = counter
        latestAt = at
        if (!scheduled) {
            scheduled = true
            handler.postDelayed(flush, if (first) 0L else 10_000L)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun queueCheckpoint() {
        val counter = latestCounter ?: return
        val at = latestAt
        io.execute {
            try {
                repository.recordSensorCounter(counter, bootId, at, sessionId)
            } catch (_: Exception) {
                setStatus(this, "计步保存失败，已有记录已保留，请重新打开 App 检查")
                handler.post { disableTracking(); stopSelf() }
            }
        }
    }

    private fun disableTracking() {
        active = false
        if (::sensors.isInitialized) sensors.unregisterListener(this)
        runCatching { repository.setStepTracking(false) }
        runCatching { repository.finishSensorSession(sessionId) }
    }

    private fun requestStop() {
        if (closing) return
        closing = true
        active = false
        sensors.unregisterListener(this)
        handler.removeCallbacks(flush)
        queueCheckpoint()
        io.execute {
            runCatching { repository.finishSensorSession(sessionId) }
            runCatching { repository.setStepTracking(false) }
            handler.post { stopSelf() }
        }
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val content = PendingIntent.getActivity(this, 11, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 12,
            Intent(this, StepTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("个人状态 · 计步中")
            .setContentText("记录手机步数增量；点击查看，或停止计步")
            .setContentIntent(content).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "停止计步", stop).build()).build()
    }

    override fun onDestroy() {
        active = false
        handler.removeCallbacks(flush)
        if (::sensors.isInitialized) sensors.unregisterListener(this)
        if (!closing) queueCheckpoint()
        io.shutdown()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "life-status-steps"
        private const val NOTIFICATION_ID = 7101
        private const val ACTION_STOP = "cn.lifestatus.app.STOP_STEPS"
        @Volatile private var instance: StepTrackingService? = null

        fun isAvailable(context: Context): Boolean =
            context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

        fun start(context: Context) {
            check(isAvailable(context)) { "手机没有提供可读取的计步传感器" }
            check(hasPermission(context)) { "请先允许身体活动／运动权限" }
            check(instance?.closing != true) { "计步正在停止，请稍后再开启" }
            val repository = AppRepository(context)
            repository.prepareSensorSession()
            repository.setStepTracking(true)
            try {
                context.startForegroundService(Intent(context, StepTrackingService::class.java))
            } catch (_: Exception) {
                repository.setStepTracking(false)
                repository.finishSensorSession()
                throw IllegalStateException("无法启动后台计步，请回到 App 前台并检查系统权限")
            }
        }

        fun stop(context: Context) {
            val repository = AppRepository(context)
            repository.setStepTracking(false)
            val running = instance
            if (running != null) running.handler.post { running.requestStop() }
            else {
                context.stopService(Intent(context, StepTrackingService::class.java))
                repository.finishSensorSession()
            }
            setStatus(context, "计步已停止，已有记录保留")
        }

        fun status(context: Context): String = context.getSharedPreferences("step-service-status", Context.MODE_PRIVATE)
            .getString("message", "尚未启用手机计步") ?: "尚未启用手机计步"

        private fun hasPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 29 ||
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        private fun setStatus(context: Context, message: String) {
            context.getSharedPreferences("step-service-status", Context.MODE_PRIVATE).edit().putString("message", message).apply()
        }

        private fun readBootId(context: Context): String {
            val count = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()
            if (count != null) return "boot-$count"
            val prefs = context.getSharedPreferences("step-service-status", Context.MODE_PRIVATE)
            val stamp = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            val previous = prefs.getLong("boot_stamp", 0L)
            val stable = if (previous > 0 && kotlin.math.abs(stamp - previous) < 120_000) previous else stamp
            prefs.edit().putLong("boot_stamp", stable).apply()
            return "boot-$stable"
        }
    }
}
