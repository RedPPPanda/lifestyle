package cn.lifestatus.app.core

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class StateEngineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 11, 22, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val hour = 3_600_000L

    private fun event(
        kind: EventKind,
        value: Double,
        at: Long = now,
        metric: Metric? = null,
        id: String = java.util.UUID.randomUUID().toString(),
    ) = LifeEvent(id = id, kind = kind, value = value, occurredAt = at, metric = metric)

    @Test fun missingMeasurementsRemainUnknown() {
        val value = StateEngine.snapshot(emptyList(), now, zone = zone)
        assertNull(value.satiety)
        assertNull(value.hydration)
        assertNull(value.energy)
        assertNull(value.weightKg)
        assertTrue(value.sources.isEmpty())
    }

    @Test fun reopeningAppRecalculatesElapsedTimeWithoutTimers() {
        val records = listOf(
            event(EventKind.CALIBRATE, 80.0, now - 2 * hour, Metric.SATIETY),
            event(EventKind.CALIBRATE, 70.0, now - 2 * hour, Metric.HYDRATION),
        )
        val value = StateEngine.snapshot(records, now, zone = zone)
        assertEquals(56.0, value.satiety!!, 0.001)
        assertEquals(60.0, value.hydration!!, 0.001)
    }

    @Test fun historicalEntryIsReplayedBeforeLaterCalibration() {
        val before = event(EventKind.WATER, 250.0, now - 3 * hour)
        val calibration = event(EventKind.CALIBRATE, 60.0, now - hour, Metric.HYDRATION)
        val value = StateEngine.snapshot(listOf(calibration, before), now, zone = zone)
        assertEquals(55.0, value.hydration!!, 0.001)
        assertEquals(250.0, value.todayWaterMl, 0.001)
        assertEquals(value, StateEngine.snapshot(listOf(before, calibration), now, zone = zone))
    }

    @Test fun sleepReplacesAwakeDecayAndOverlapsAreNotCountedTwice() {
        val records = listOf(
            event(EventKind.CALIBRATE, 20.0, now - 6 * hour, Metric.ENERGY),
            event(EventKind.SLEEP, 4.0, now - hour), // [now-5h, now-1h]
            event(EventKind.SLEEP, 3.0, now), // [now-3h, now]
        )
        val value = StateEngine.snapshot(records, now, zone = zone)
        // One waking hour: -5, then five unique sleeping hours: +50.
        assertEquals(65.0, value.energy!!, 0.001)
    }

    @Test fun calibrationInsideSleepIsNewBaseline() {
        val records = listOf(
            event(EventKind.SLEEP, 8.0, now),
            event(EventKind.CALIBRATE, 20.0, now - 2 * hour, Metric.ENERGY),
        )
        assertEquals(40.0, StateEngine.snapshot(records, now, zone = zone).energy!!, 0.001)
    }

    @Test fun deletionAndDuplicateIdsDoNotApplyOneRecordTwice() {
        val water = event(EventKind.WATER, 250.0, id = "water")
        assertEquals(250.0, StateEngine.snapshot(listOf(water, water), now, zone = zone).todayWaterMl, 0.001)
        val afterUndo = StateEngine.snapshot(listOf(water, water.copy(deleted = true)), now, zone = zone)
        assertEquals(0.0, afterUndo.todayWaterMl, 0.001)
        assertNull(afterUndo.hydration)
    }

    @Test fun futureAndNonFiniteMeasurementsCannotCorruptState() {
        val records = listOf(
            event(EventKind.WATER, Double.NaN),
            event(EventKind.WATER, Double.POSITIVE_INFINITY),
            event(EventKind.WATER, -250.0),
            event(EventKind.CALIBRATE, 120.0, metric = Metric.ENERGY),
            event(EventKind.WEIGHT, 68.0, now + hour),
        )
        val value = StateEngine.snapshot(records, now, zone = zone)
        assertNull(value.hydration)
        assertNull(value.energy)
        assertNull(value.weightKg)
        assertEquals(0.0, value.todayWaterMl, 0.001)
    }

    @Test fun realUnitsSurviveDayRolloverAndStateCalibration() {
        val midnight = ZonedDateTime.of(2026, 11, 22, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val records = listOf(
            event(EventKind.WATER, 600.0, midnight - 1),
            event(EventKind.WATER, 250.0, midnight),
            event(EventKind.CALIBRATE, 80.0, now, Metric.HYDRATION),
            event(EventKind.WEIGHT, 68.2, midnight - hour),
        )
        val value = StateEngine.snapshot(records, now, zone = zone)
        assertEquals(250.0, value.todayWaterMl, 0.001)
        assertEquals(80.0, value.hydration!!, 0.001)
        assertEquals(68.2, value.weightKg!!, 0.001)
        assertEquals(midnight - hour, value.weightAt)
    }

    @Test fun stepCalibrationIsDailyAndDoesNotCreateExerciseDuration() {
        val records = listOf(
            event(EventKind.STEPS, 1_000.0, now - 24 * hour),
            event(EventKind.STEPS, 2_000.0, now - 3 * hour),
            event(EventKind.CALIBRATE, 5_000.0, now - 2 * hour, Metric.STEPS),
            event(EventKind.STEPS, 300.0, now - hour),
        )
        val value = StateEngine.snapshot(records, now, zone = zone)
        assertEquals(5_300, value.todaySteps)
        assertEquals(0.0, value.todayExerciseMinutes, 0.001)
        assertNull(value.energy)
    }

    @Test fun estimatesStayBoundedAndInvalidRulesUseSafeValues() {
        val meal = event(EventKind.MEAL, 3.0)
        assertEquals(100.0, StateEngine.snapshot(listOf(meal), now, zone = zone).satiety!!, 0.001)
        val old = meal.copy(occurredAt = now - 100 * hour)
        val badRules = RuleConfig(satietyDecayPerHour = Double.NaN, hydrationDecayPerHour = -3.0)
        assertEquals(0.0, StateEngine.snapshot(listOf(old), now, badRules, zone).satiety!!, 0.001)
    }

    @Test fun screenshotTotalReplacesOverlappingSensorStepsThenAddsNewMovement() {
        val records = listOf(
            event(EventKind.STEPS, 2_000.0, now - 2 * hour).copy(source = "系统计步"),
            event(EventKind.STEPS_TOTAL, 2_000.0, now - hour).copy(source = "健康App截图"),
            event(EventKind.STEPS, 150.0, now).copy(source = "系统计步"),
        )
        val value = StateEngine.snapshot(records, now, zone = zone)
        assertEquals(2_150, value.todaySteps)
        assertEquals(0.0, value.todayExerciseMinutes, 0.001)
    }

    @Test fun observationsFromTwoAppsAreAnchorsNotAdditiveSources() {
        val records = listOf(
            event(EventKind.STEPS_TOTAL, 3_000.0, now - hour).copy(source = "手机健康"),
            event(EventKind.STEPS_TOTAL, 3_000.0, now - hour).copy(source = "手环截图"),
            event(EventKind.STEPS_TOTAL, 3_200.0, now).copy(source = "手机健康"),
        )
        assertEquals(3_200, StateEngine.snapshot(records, now, zone = zone).todaySteps)
    }

    @Test fun totalIncludesIncrementAtExactSameTimestampRegardlessOfArrivalOrder() {
        val total = event(EventKind.STEPS_TOTAL, 2_000.0, now).copy(recordedAt = now - 1, source = "截图")
        val increment = event(EventKind.STEPS, 100.0, now).copy(source = "系统计步")
        assertEquals(2_000, StateEngine.snapshot(listOf(total, increment), now, zone = zone).todaySteps)
        assertEquals(2_000, StateEngine.snapshot(listOf(increment, total), now, zone = zone).todaySteps)
    }

    @Test fun backdatedTotalReplaysBeforeAlreadyStoredLaterSensorEvents() {
        val records = listOf(
            event(EventKind.STEPS, 500.0, now - 3 * hour),
            event(EventKind.STEPS, 200.0, now - hour),
            event(EventKind.STEPS_TOTAL, 5_000.0, now - 2 * hour).copy(recordedAt = now),
            event(EventKind.STEPS, 100.0, now),
        )
        assertEquals(5_300, StateEngine.snapshot(records, now, zone = zone).todaySteps)
    }

    @Test fun undoTotalRestoresUnderlyingIncrementsAndDoesNotCarryIntoNextDay() {
        val midnight = ZonedDateTime.of(2026, 11, 22, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val records = listOf(
            event(EventKind.STEPS_TOTAL, 8_000.0, midnight - 1),
            event(EventKind.STEPS, 200.0, midnight + hour),
            event(EventKind.STEPS_TOTAL, 3_000.0, now - hour).copy(deleted = true),
            event(EventKind.STEPS, 100.0, now),
        )
        assertEquals(300, StateEngine.snapshot(records, now, zone = zone).todaySteps)
    }

    @Test fun zeroTotalIsValidButFractionalAndFutureTotalsAreIgnored() {
        val records = listOf(
            event(EventKind.STEPS, 500.0, now - hour),
            event(EventKind.STEPS_TOTAL, 0.0, now),
            event(EventKind.STEPS_TOTAL, 100.5, now),
            event(EventKind.STEPS_TOTAL, 1_000.0, now + hour),
        )
        assertEquals(0, StateEngine.snapshot(records, now, zone = zone).todaySteps)
    }
}
