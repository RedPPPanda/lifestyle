package cn.lifestatus.app.core

import java.util.UUID

enum class Metric { SATIETY, HYDRATION, ENERGY, WEIGHT, STEPS, EXERCISE_MINUTES }

enum class EventKind { MEAL, WATER, SLEEP, WEIGHT, EXERCISE, STEPS, STEPS_TOTAL, CALIBRATE, NOTE }

/** An immutable record. Values retain their original units; estimates are calculated separately. */
data class LifeEvent(
    val id: String = UUID.randomUUID().toString(),
    val batchId: String = id,
    val kind: EventKind,
    val value: Double = 0.0,
    val occurredAt: Long,
    val recordedAt: Long = occurredAt,
    val text: String = "",
    val source: String = "手动",
    val metric: Metric? = null,
    val deleted: Boolean = false,
)

/** Adjustable game rules, not measurements or medical population averages. */
data class RuleConfig(
    val satietyDecayPerHour: Double = 12.0,
    val hydrationDecayPerHour: Double = 5.0,
    val awakeEnergyDecayPerHour: Double = 5.0,
    val sleepRecoveryPerHour: Double = 10.0,
    val mealGain: Double = 65.0,
    val waterGainPer250Ml: Double = 15.0,
    val exerciseEnergyCostPerMinute: Double = 0.1,
)

data class Snapshot(
    val at: Long,
    val satiety: Double?,
    val hydration: Double?,
    val energy: Double?,
    val weightKg: Double?,
    val weightAt: Long?,
    val todayWaterMl: Double,
    val todaySteps: Int,
    val todayExerciseMinutes: Double,
    val lastSleepHours: Double?,
    val lastSleepAt: Long?,
    val sources: Map<Metric, String>,
)

data class ParseResult(
    val events: List<LifeEvent>,
    val message: String,
    val requiresAi: Boolean = false,
)

fun Snapshot.valueFor(metric: Metric): Double? = when (metric) {
    Metric.SATIETY -> satiety
    Metric.HYDRATION -> hydration
    Metric.ENERGY -> energy
    Metric.WEIGHT -> weightKg
    Metric.STEPS -> todaySteps.toDouble()
    Metric.EXERCISE_MINUTES -> todayExerciseMinutes
}
