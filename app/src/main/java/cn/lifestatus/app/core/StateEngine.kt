package cn.lifestatus.app.core

import java.time.Instant
import java.time.ZoneId

/** Replays records against the current clock; no background timer is needed for decay. */
object StateEngine {
    private const val HOUR_MS = 3_600_000.0

    fun snapshot(
        events: List<LifeEvent>,
        now: Long,
        rules: RuleConfig = RuleConfig(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Snapshot {
        // Last stored version wins, including a tombstone for an undone event.
        val records = events.associateBy { it.id }.values
            .filter { isValid(it, now) }
            .sortedWith(compareBy<LifeEvent> { it.occurredAt }
                // A daily observation includes steps at its timestamp; only later increments add to it.
                .thenBy { if (it.kind == EventKind.STEPS_TOTAL || (it.kind == EventKind.CALIBRATE && it.metric == Metric.STEPS)) 1 else 0 }
                .thenBy { it.recordedAt }.thenBy { it.id })
        val config = safeRules(rules)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val sleeps = mergeSleeps(records.filter { it.kind == EventKind.SLEEP })
        val recordsAt = records.groupBy { it.occurredAt }
        val boundaries = buildSet {
            add(now)
            records.forEach { add(it.occurredAt) }
            sleeps.forEach { add(it.start); add(it.end) }
        }.sorted()
        var satiety: Double? = null
        var hydration: Double? = null
        var energy: Double? = null
        var weight: Double? = null
        var weightAt: Long? = null
        var waterMl = 0.0
        var steps = 0L
        var exerciseMinutes = 0.0
        var lastSleepHours: Double? = null
        var lastSleepAt: Long? = null
        val sources = mutableMapOf<Metric, String>()
        var previousTime = boundaries.first()
        var sleepIndex = 0

        for (time in boundaries) {
            val hours = (time - previousTime).toDouble() / HOUR_MS
            if (hours > 0) {
                satiety = satiety?.let { bounded(it - config.satietyDecayPerHour * hours) }
                hydration = hydration?.let { bounded(it - config.hydrationDecayPerHour * hours) }
                while (sleepIndex < sleeps.size && sleeps[sleepIndex].end <= previousTime) sleepIndex++
                val sleep = sleeps.getOrNull(sleepIndex)
                    ?.takeIf { previousTime >= it.start && previousTime < it.end }
                if (sleep != null) {
                    // An observed sleep period establishes an estimate even if energy was unknown.
                    if (energy == null) sources[Metric.ENERGY] = sleep.source
                    energy = bounded((energy ?: 0.0) + config.sleepRecoveryPerHour * hours)
                } else {
                    energy = energy?.let { bounded(it - config.awakeEnergyDecayPerHour * hours) }
                }
            }
            for (event in recordsAt[time].orEmpty()) {
                val isToday = Instant.ofEpochMilli(event.occurredAt).atZone(zone).toLocalDate() == today
                when (event.kind) {
                    EventKind.MEAL -> {
                        satiety = bounded((satiety ?: 0.0) + event.value * config.mealGain)
                        sources[Metric.SATIETY] = event.source
                    }
                    EventKind.WATER -> {
                        hydration = bounded((hydration ?: 0.0) + event.value / 250.0 * config.waterGainPer250Ml)
                        sources[Metric.HYDRATION] = event.source
                        if (isToday) waterMl += event.value
                    }
                    EventKind.SLEEP -> {
                        // Recovery has already been integrated over the union of sleep intervals.
                        lastSleepHours = event.value
                        lastSleepAt = event.occurredAt
                        sources[Metric.ENERGY] = event.source
                    }
                    EventKind.WEIGHT -> {
                        weight = event.value
                        weightAt = event.occurredAt
                        sources[Metric.WEIGHT] = event.source
                    }
                    EventKind.EXERCISE -> {
                        energy = energy?.let { bounded(it - event.value * config.exerciseEnergyCostPerMinute) }
                        if (energy != null) sources[Metric.ENERGY] = event.source
                        if (isToday) {
                            exerciseMinutes += event.value
                            sources[Metric.EXERCISE_MINUTES] = event.source
                        }
                    }
                    EventKind.STEPS -> if (isToday) {
                        steps = (steps + event.value.toLong()).coerceAtMost(Int.MAX_VALUE.toLong())
                        sources[Metric.STEPS] = event.source
                    }
                    EventKind.STEPS_TOTAL -> if (isToday) {
                        steps = event.value.toLong()
                        sources[Metric.STEPS] = event.source
                    }
                    EventKind.CALIBRATE -> when (event.metric) {
                        Metric.SATIETY -> { satiety = event.value; sources[Metric.SATIETY] = event.source }
                        Metric.HYDRATION -> { hydration = event.value; sources[Metric.HYDRATION] = event.source }
                        Metric.ENERGY -> { energy = event.value; sources[Metric.ENERGY] = event.source }
                        Metric.WEIGHT -> {
                            weight = event.value; weightAt = event.occurredAt; sources[Metric.WEIGHT] = event.source
                        }
                        Metric.STEPS -> if (isToday) {
                            steps = event.value.toLong(); sources[Metric.STEPS] = event.source
                        }
                        Metric.EXERCISE_MINUTES -> if (isToday) {
                            exerciseMinutes = event.value; sources[Metric.EXERCISE_MINUTES] = event.source
                        }
                        null -> Unit
                    }
                    EventKind.NOTE -> Unit
                }
            }
            previousTime = time
        }
        return Snapshot(
            at = now,
            satiety = satiety,
            hydration = hydration,
            energy = energy,
            weightKg = weight,
            weightAt = weightAt,
            todayWaterMl = waterMl,
            todaySteps = steps.toInt(),
            todayExerciseMinutes = exerciseMinutes,
            lastSleepHours = lastSleepHours,
            lastSleepAt = lastSleepAt,
            sources = sources.toMap(),
        )
    }

    private fun isValid(event: LifeEvent, now: Long): Boolean {
        if (event.deleted || event.id.isBlank() || event.occurredAt < 0 || event.occurredAt > now || !event.value.isFinite()) return false
        return when (event.kind) {
            EventKind.MEAL -> event.value in 0.1..3.0
            EventKind.WATER -> event.value > 0.0 && event.value <= 5_000.0
            EventKind.SLEEP -> event.value > 0.0 && event.value <= 24.0
            EventKind.WEIGHT -> event.value > 0.0 && event.value <= 500.0
            EventKind.EXERCISE -> event.value > 0.0 && event.value <= 1_440.0
            EventKind.STEPS -> event.value in 0.0..200_000.0 && event.value % 1.0 == 0.0
            EventKind.STEPS_TOTAL -> event.value in 0.0..200_000.0 && event.value % 1.0 == 0.0
            EventKind.CALIBRATE -> when (event.metric) {
                Metric.SATIETY, Metric.HYDRATION, Metric.ENERGY -> event.value in 0.0..100.0
                Metric.WEIGHT -> event.value > 0.0 && event.value <= 500.0
                Metric.STEPS -> event.value in 0.0..200_000.0 && event.value % 1.0 == 0.0
                Metric.EXERCISE_MINUTES -> event.value in 0.0..1_440.0
                null -> false
            }
            EventKind.NOTE -> true
        }
    }

    private data class SleepInterval(val start: Long, val end: Long, val source: String)

    private fun mergeSleeps(events: List<LifeEvent>): List<SleepInterval> {
        val intervals = events.map {
            val duration = (it.value * HOUR_MS).toLong()
            SleepInterval((it.occurredAt - duration).coerceAtLeast(0), it.occurredAt, it.source)
        }.sortedBy { it.start }
        val merged = mutableListOf<SleepInterval>()
        for (interval in intervals) {
            val previous = merged.lastOrNull()
            if (previous != null && interval.start <= previous.end) {
                merged[merged.lastIndex] = previous.copy(
                    end = maxOf(previous.end, interval.end),
                    source = if (interval.end >= previous.end) interval.source else previous.source,
                )
            } else {
                merged += interval
            }
        }
        return merged
    }

    private fun bounded(value: Double) = value.coerceIn(0.0, 100.0)

    private fun safeRules(rules: RuleConfig): RuleConfig {
        val defaults = RuleConfig()
        fun safe(value: Double, fallback: Double, maximum: Double = 100.0): Double =
            if (value.isFinite()) value.coerceIn(0.0, maximum) else fallback
        return RuleConfig(
            satietyDecayPerHour = safe(rules.satietyDecayPerHour, defaults.satietyDecayPerHour),
            hydrationDecayPerHour = safe(rules.hydrationDecayPerHour, defaults.hydrationDecayPerHour),
            awakeEnergyDecayPerHour = safe(rules.awakeEnergyDecayPerHour, defaults.awakeEnergyDecayPerHour),
            sleepRecoveryPerHour = safe(rules.sleepRecoveryPerHour, defaults.sleepRecoveryPerHour),
            mealGain = safe(rules.mealGain, defaults.mealGain),
            waterGainPer250Ml = safe(rules.waterGainPer250Ml, defaults.waterGainPer250Ml),
            exerciseEnergyCostPerMinute = safe(rules.exerciseEnergyCostPerMinute, defaults.exerciseEnergyCostPerMinute, 10.0),
        )
    }
}
