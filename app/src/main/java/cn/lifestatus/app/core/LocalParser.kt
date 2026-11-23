package cn.lifestatus.app.core

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Conservative offline fallback. Unclear statements become notes instead of invented measurements. */
object LocalParser {
    private const val NUM = "(?:\\d+(?:\\.\\d+)?|[零〇一二两三四五六七八九十百千万半]+(?:点[零〇一二两三四五六七八九]+)?)"
    private val action = Regex("喝|饮|吃|进食|用餐|睡|体重|称重|走|跑|运动|锻炼|骑|游泳|散步|步行|健身|早餐|午餐|晚餐")
    private val planned = Regex("明天|后天|准备|打算|计划|(?:想|要|需要|应该|提醒我)(?:去|再|先|多|少)?(?:喝|饮|吃|睡|走|跑|运动|锻炼|骑|游泳|称)")
    private val negative = Regex("(?:没(?:有)?|并未|尚未|未曾|未|不(?:想|要|用|会|曾|再)?|不要|别)[^，,。；;]{0,4}(?:吃|喝|饮|睡|跑|走|运动|步行|锻炼|称|测)")
    private val dividers = Regex("[，,。；;！!？?\\n]+|然后|并且|而且|以及|接着|但是|不过|同时|但|并(?=喝|饮|吃|睡|走|跑|运动|步行)")

    fun parse(
        text: String,
        now: Long,
        source: String = "文字",
        zone: ZoneId = ZoneId.systemDefault(),
    ): ParseResult {
        val original = text.trim().take(4_000)
        if (original.isBlank()) return ParseResult(emptyList(), "请先输入刚刚做了什么")
        val input = Normalizer.normalize(original, Normalizer.Form.NFKC)
        val batchId = UUID.randomUUID().toString()
        val safeSource = source.ifBlank { "文字" }
        val events = mutableListOf<LifeEvent>()
        val messages = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var requiresAi = false
        val prefix = input.take(action.find(input)?.range?.first ?: 0)
        val inheritedTime = resolveTime(prefix, now, now, zone)

        fun add(kind: EventKind, value: Double, time: ResolvedTime, suffix: String = "") {
            events += LifeEvent(
                batchId = batchId,
                kind = kind,
                value = value,
                occurredAt = time.at,
                recordedAt = now,
                text = original,
                source = buildString {
                    append(safeSource)
                    if (suffix.isNotEmpty()) append("（$suffix）")
                    if (time.estimated) append("（时间估算）")
                },
            )
            if (time.estimated) messages += "未给具体时刻，按该日同一时间记录，可修改"
        }

        for (rawClause in input.split(dividers).take(12)) {
            val clause = rawClause.trim()
            if (clause.isBlank()) continue
            if (planned.containsMatchIn(clause)) {
                notes += clause
                messages += "计划已保留，未更新状态"
                continue
            }
            if (negative.containsMatchIn(clause.replace("不小心", "意外"))) {
                notes += clause
                messages += "否定描述已保留，未更新状态"
                continue
            }
            if (Regex("吃.{0,3}(?:药|维生素|保健品)|喝.{0,5}(?:酒|药)").containsMatchIn(clause)) {
                notes += clause
                requiresAi = true
                continue
            }
            val time = resolveTime(clause, now, inheritedTime.at, zone).let {
                if (it.at == inheritedTime.at && inheritedTime.estimated && !it.explicit) it.copy(estimated = true) else it
            }
            if (time.at > now || time.at < 0) {
                notes += clause
                messages += "时间尚未发生或无法确定，已保留原文"
                continue
            }
            val beforeCount = events.size

            // Parse amounts only after their action verb so “2 小时前跑步 30 分钟” uses 30 minutes.
            val drink = Regex("喝|饮(?:水|用)?").find(clause)
            if (drink != null) {
                val part = clause.substring(drink.range.first)
                val ml = Regex("($NUM)\\s*(毫升|ml|升|l)", RegexOption.IGNORE_CASE).find(part)
                val cups = Regex("($NUM)\\s*(?:个)?(?:杯|小杯|大杯)").find(part)
                val amount = when {
                    ml != null -> numeric(ml.groupValues[1])?.times(if (ml.groupValues[2].lowercase() in setOf("升", "l")) 1_000 else 1)
                    cups != null -> numeric(cups.groupValues[1])?.times(250)
                    else -> null
                }
                if (amount != null && amount > 0 && amount <= 5_000) {
                    add(EventKind.WATER, amount, time, if (ml == null) "按每杯250mL估算" else "")
                    messages += "饮水 ${display(amount)} mL" + if (ml == null) "（杯量估算）" else ""
                }
            }

            val sleep = Regex("睡|补觉").find(clause)
            if (sleep != null) {
                val minutes = durationMinutes(clause.substring(sleep.range.first))
                if (minutes != null && minutes > 0 && minutes <= 1_440) {
                    add(EventKind.SLEEP, minutes / 60.0, time)
                    messages += "睡眠 ${display(minutes / 60.0)} 小时"
                }
            }

            val weightChanged = Regex("体重.{0,5}(?:下降|减少|减了|增加|重了|轻了|涨了)").containsMatchIn(clause)
            if (!weightChanged) {
                val weight = Regex("(?:体重|称重|称了|称到|称出)[^0-9零〇一二两三四五六七八九十百千万半]{0,6}($NUM)\\s*(公斤|千克|kg|斤)?", RegexOption.IGNORE_CASE).find(clause)
                if (weight != null) {
                    val value = numeric(weight.groupValues[1])?.times(if (weight.groupValues[2] == "斤") 0.5 else 1.0)
                    if (value != null && value > 0 && value <= 500) {
                        add(EventKind.WEIGHT, value, time, if (weight.groupValues[2].isBlank()) "默认单位kg" else "")
                        messages += "体重 ${display(value)} kg"
                    }
                }
            }

            val movement = Regex("走|跑|运动|锻炼|骑行|骑车|游泳|散步|步行|健身").find(clause)
            val compact = clause.replace(Regex("\\s+"), "")
            val stepsComparison = Regex("比昨天|比前天|减少|少了|多了|增加了|下降|上升").containsMatchIn(clause)
            val totalSteps = Regex("(?:(?:今天|今日|当天|本日)(?:的)?(?:总步数|累计步数|步数)|(?:总步数|累计步数|步数总计|步数合计)|(?:今天|今日)(?:总共|一共|累计)(?:走了?|步行了?))(?:为|是|有|共|总共|约|达到|到了|:)*($NUM)(?:步)?").find(compact)
            if (totalSteps != null && !stepsComparison) {
                val total = numeric(totalSteps.groupValues[1])
                if (total != null && total in 0.0..200_000.0 && total % 1.0 == 0.0) {
                    add(EventKind.STEPS_TOTAL, total, time)
                    messages += "今日步数总量 ${display(total)} 步"
                }
            }
            if (movement != null) {
                val part = clause.substring(movement.range.first)
                val stepMatch = Regex("($NUM)\\s*步").find(part)
                val steps = stepMatch?.groupValues?.get(1)?.let(::numeric)
                if (totalSteps == null && !stepsComparison && steps != null && steps > 0 && steps <= 200_000 && steps % 1.0 == 0.0) {
                    add(EventKind.STEPS, steps, time)
                    messages += "步数 +${display(steps)}"
                }
                val minutes = durationMinutes(part)
                if (minutes != null && minutes > 0 && minutes <= 1_440) {
                    add(EventKind.EXERCISE, minutes, time)
                    messages += "运动 ${display(minutes)} 分钟"
                }
            }

            val meal = Regex("吃|进食|用餐|早餐|午餐|晚餐|早饭|午饭|晚饭").find(clause)
            if (meal != null && !Regex("吃水|吃亏|吃惊|吃力|吃苦").containsMatchIn(clause)) {
                val part = clause.substring(meal.range.first)
                val portionMatch = Regex("($NUM)\\s*(?:份|碗|盘|顿)").find(part)
                val portion = when {
                    portionMatch != null -> numeric(portionMatch.groupValues[1])
                    Regex("一点|一些|小点心|零食|苹果|香蕉|饼干").containsMatchIn(part) -> 0.3
                    else -> 1.0
                }
                if (portion != null && portion in 0.1..3.0) {
                    add(EventKind.MEAL, portion, time, "餐量估算")
                    messages += "进食约 ${display(portion)} 份（估算）"
                }
            }

            if (events.size == beforeCount) {
                notes += clause
                requiresAi = true
            }
        }

        if (notes.isNotEmpty()) {
            events += LifeEvent(
                batchId = batchId,
                kind = EventKind.NOTE,
                occurredAt = now,
                recordedAt = now,
                text = if (events.isEmpty()) original else notes.joinToString("；"),
                source = safeSource,
            )
        }
        if (events.isEmpty()) {
            events += LifeEvent(batchId = batchId, kind = EventKind.NOTE, occurredAt = now, text = original, source = safeSource)
            requiresAi = true
        }
        val summary = if (events.all { it.kind == EventKind.NOTE }) {
            messages.distinct().joinToString("；").ifBlank { "原文已保存；暂未识别出可更新的数值，可连接 AI 或手动校准" }
        } else {
            "已记录：" + messages.distinct().joinToString("；") + if (requiresAi) "；其余内容已保留为备注" else ""
        }
        return ParseResult(events, summary, requiresAi)
    }

    private data class ResolvedTime(val at: Long, val estimated: Boolean = false, val explicit: Boolean = false)

    private fun resolveTime(text: String, now: Long, fallback: Long, zone: ZoneId): ResolvedTime {
        val relative = Regex("($NUM)\\s*(小时|分钟|分|天)前").find(text)
        if (relative != null) {
            val amount = numeric(relative.groupValues[1]) ?: return ResolvedTime(fallback)
            val scale = when (relative.groupValues[2]) { "小时" -> 3_600_000.0; "天" -> 86_400_000.0; else -> 60_000.0 }
            return if (amount in 0.0..2_160.0 && amount * scale <= 90 * 86_400_000.0) {
                ResolvedTime(now - (amount * scale).toLong(), explicit = true)
            } else ResolvedTime(-1, explicit = true)
        }
        var at = Instant.ofEpochMilli(fallback).atZone(zone)
        val nowAt = Instant.ofEpochMilli(now).atZone(zone)
        val dateOffset = when {
            "前天" in text -> -2L
            Regex("昨天|昨日|昨晚").containsMatchIn(text) -> -1L
            Regex("今天|今日|今早|今晚").containsMatchIn(text) -> 0L
            else -> null
        }
        if (dateOffset != null) {
            at = nowAt.minusDays(-dateOffset)
        }
        val clock = Regex("(凌晨|早上|早晨|上午|中午|下午|晚上|晚间)?\\s*(\\d{1,2})\\s*(?:点|:)(?:(\\d{1,2})\\s*分?|半)?").find(text)
        if (clock != null) {
            var hour = clock.groupValues[2].toInt()
            val minute = clock.groupValues[3].toIntOrNull() ?: if (clock.value.endsWith("半")) 30 else 0
            val period = clock.groupValues[1]
            if (period in setOf("下午", "晚上", "晚间") && hour in 1..11) hour += 12
            if (period == "中午" && hour in 1..10) hour += 12
            if (period == "凌晨" && hour == 12) hour = 0
            if (hour !in 0..23 || minute !in 0..59) return ResolvedTime(-1, explicit = true)
            at = at.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            return ResolvedTime(at.toInstant().toEpochMilli(), explicit = true)
        }
        return ResolvedTime(at.toInstant().toEpochMilli(), estimated = dateOffset != null, explicit = dateOffset != null)
    }

    private fun durationMinutes(text: String): Double? {
        val hours = Regex("($NUM)\\s*(?:个)?\\s*(半)?\\s*(?:小时|钟头|h)(?:\\s*($NUM)\\s*(?:分钟|分|min))?", RegexOption.IGNORE_CASE).find(text)
        if (hours != null) {
            val whole = numeric(hours.groupValues[1]) ?: return null
            val half = if (hours.groupValues[2].isNotBlank()) 0.5 else 0.0
            val minutes = hours.groupValues[3].takeIf(String::isNotBlank)?.let(::numeric) ?: 0.0
            return (whole + half) * 60 + minutes
        }
        val minutes = Regex("($NUM)\\s*(?:分钟|分|min)", RegexOption.IGNORE_CASE).find(text)
        return minutes?.groupValues?.get(1)?.let(::numeric)
    }

    private fun numeric(value: String): Double? {
        value.toDoubleOrNull()?.let { return it.takeIf(Double::isFinite) }
        if (value == "半") return 0.5
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if ('点' in value) {
            val parts = value.split('点')
            if (parts.size != 2 || parts[1].isEmpty() || parts[1].any { it !in digits }) return null
            val whole = numeric(parts[0]) ?: return null
            val fraction = ("0." + parts[1].map { digits.getValue(it) }.joinToString("")).toDoubleOrNull() ?: return null
            return whole + fraction
        }
        var total = 0L
        var section = 0L
        var digit = 0L
        for (char in value) {
            if (char in digits) { digit = digits.getValue(char).toLong(); continue }
            val unit = when (char) { '十' -> 10; '百' -> 100; '千' -> 1_000; '万' -> 10_000; else -> return null }
            if (unit == 10_000) {
                total += (section + digit).coerceAtLeast(1) * unit
                section = 0; digit = 0
            } else {
                section += digit.coerceAtLeast(1) * unit
                digit = 0
            }
        }
        return (total + section + digit).toDouble().takeIf { it.isFinite() && it >= 0 }
    }

    private fun display(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
}
