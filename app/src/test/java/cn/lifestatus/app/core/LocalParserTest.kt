package cn.lifestatus.app.core

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class LocalParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 11, 22, 14, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun parse(text: String) = LocalParser.parse(text, now, zone = zone)

    @Test fun compoundRecordUsesOneBatchAndCorrectUnits() {
        val result = parse("喝了250毫升水，走了两千步，跑步30分钟，体重136.4斤")
        assertEquals(setOf(EventKind.WATER, EventKind.STEPS, EventKind.EXERCISE, EventKind.WEIGHT), result.events.map { it.kind }.toSet())
        assertEquals(1, result.events.map { it.batchId }.distinct().size)
        assertEquals(250.0, result.events.first { it.kind == EventKind.WATER }.value, 0.001)
        assertEquals(2_000.0, result.events.first { it.kind == EventKind.STEPS }.value, 0.001)
        assertEquals(68.2, result.events.first { it.kind == EventKind.WEIGHT }.value, 0.001)
        assertFalse(result.requiresAi)
    }

    @Test fun guessedCupVolumeAndMealAmountAreMarkedAsEstimates() {
        val result = parse("喝了一杯水，吃了半碗饭")
        assertEquals(250.0, result.events.first { it.kind == EventKind.WATER }.value, 0.001)
        assertTrue(result.events.first { it.kind == EventKind.WATER }.source.contains("估算"))
        assertEquals(0.5, result.events.first { it.kind == EventKind.MEAL }.value, 0.001)
        assertTrue(result.events.first { it.kind == EventKind.MEAL }.source.contains("估算"))
    }

    @Test fun plansAndNegationsNeverRaiseState() {
        for (text in listOf("我今天没喝250毫升水", "准备喝250ml水", "明天跑步30分钟", "想睡8小时")) {
            val result = parse(text)
            assertTrue(text, result.events.all { it.kind == EventKind.NOTE })
            val snapshot = StateEngine.snapshot(result.events, now, zone = zone)
            assertNull(text, snapshot.hydration)
            assertNull(text, snapshot.energy)
            assertEquals(text, 0.0, snapshot.todayExerciseMinutes, 0.001)
        }
    }

    @Test fun positiveClauseSurvivesSeparateNegativeClause() {
        val result = parse("没喝水，但是走了2000步")
        assertEquals(2_000, StateEngine.snapshot(result.events, now, zone = zone).todaySteps)
        assertTrue(result.events.none { it.kind == EventKind.WATER })
    }

    @Test fun relativeTimeDoesNotBecomeExerciseDuration() {
        val result = parse("2小时前跑步30分钟")
        val record = result.events.single { it.kind == EventKind.EXERCISE }
        assertEquals(30.0, record.value, 0.001)
        assertEquals(now - 2 * 3_600_000L, record.occurredAt)
    }

    @Test fun yesterdayRecordsUseYesterdayAndKeepTimestampUncertaintyVisible() {
        val result = parse("昨天喝了500毫升水")
        val record = result.events.single { it.kind == EventKind.WATER }
        assertEquals(now - 24 * 3_600_000L, record.occurredAt)
        assertTrue(record.source.contains("时间估算"))
        assertEquals(0.0, StateEngine.snapshot(result.events, now, zone = zone).todayWaterMl, 0.001)
    }

    @Test fun explicitClockTimeAndMixedSleepUnitsAreHandled() {
        val result = parse("今天8点睡了7小时10分钟")
        val record = result.events.single { it.kind == EventKind.SLEEP }
        assertEquals(7 + 10.0 / 60.0, record.value, 0.001)
        assertEquals(now - 6 * 3_600_000L, record.occurredAt)
    }

    @Test fun unknownStatementsAndWeightDeltasDoNotInventAbsoluteValues() {
        for (text in listOf("今天天气不错", "体重下降了2kg", "喝了一大瓶水", "下午18点喝250ml水")) {
            val result = parse(text)
            assertTrue(text, result.events.all { it.kind == EventKind.NOTE })
        }
    }

    @Test fun absurdMeasurementsAreSavedWithoutChangingValues() {
        val result = parse("喝了999999ml水，体重9999kg，睡了90小时")
        assertTrue(result.events.all { it.kind == EventKind.NOTE })
    }

    @Test fun spokenChineseDecimalsAreNotTruncated() {
        val result = parse("体重六十八点二公斤，睡了七点五小时，喝了零点五升水")
        assertEquals(68.2, result.events.single { it.kind == EventKind.WEIGHT }.value, 0.001)
        assertEquals(7.5, result.events.single { it.kind == EventKind.SLEEP }.value, 0.001)
        assertEquals(500.0, result.events.single { it.kind == EventKind.WATER }.value, 0.001)
    }

    @Test fun explicitDailyStepObservationsBecomeTotals() {
        for (text in listOf("今天步数2000步", "今日总步数2000步", "今天步数是两千步", "今天总共走了2000步")) {
            val result = parse(text)
            val record = result.events.single { it.kind == EventKind.STEPS_TOTAL }
            assertEquals(text, 2_000.0, record.value, 0.001)
            assertTrue(text, result.events.none { it.kind == EventKind.STEPS })
        }
        assertEquals(0.0, parse("今日总步数0步").events.single { it.kind == EventKind.STEPS_TOTAL }.value, 0.001)
    }

    @Test fun ordinaryWalkIsStillAnIncrementAndCanIncludeDuration() {
        val result = parse("刚刚走了2000步用时30分钟")
        assertEquals(2_000.0, result.events.single { it.kind == EventKind.STEPS }.value, 0.001)
        assertEquals(30.0, result.events.single { it.kind == EventKind.EXERCISE }.value, 0.001)
        assertTrue(result.events.none { it.kind == EventKind.STEPS_TOTAL })
    }

    @Test fun stepComparisonIsNotAnAbsoluteTotalOrAnIncrement() {
        val result = parse("今天比昨天少走了2000步")
        assertTrue(result.events.all { it.kind == EventKind.NOTE })
    }
}
