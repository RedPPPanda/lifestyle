package cn.lifestatus.app.data

import cn.lifestatus.app.core.RuleConfig

enum class DataScope(val label: String) {
    STATES("当前估算状态"), MEALS("进食记录"), WATER("饮水记录"),
    SLEEP("睡眠记录"), WEIGHT("体重记录"), ACTIVITY("步数与运动"),
    RAW_TEXT("所选记录的原始描述")
}

data class AppSettings(
    val autoAnalyze: Boolean = true,
    val minAnalysisMinutes: Int = 30,
    val scopes: Set<DataScope> = DataScope.entries.filter { it != DataScope.RAW_TEXT }.toSet(),
    val rules: RuleConfig = RuleConfig(),
    val stepTracking: Boolean = false
)

data class AiConfig(val baseUrl: String = "", val model: String = "", val apiKey: String = "") {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
    override fun toString(): String = "AiConfig(configured=$isConfigured, credentials=[redacted])"
}

data class AnalysisRecord(val text: String, val at: Long, val signature: String)
