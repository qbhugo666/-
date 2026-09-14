package com.voicecontrol.app

import android.content.Context

/**
 * 识别灵敏度（v0.38.0，用户为声音小/口齿不清的病友提出）：
 * 滑块 1~10 格，默认 5 = 作者日常基准（恰好等于历史常量：增益 1.0×、VAD 门槛 0.6），默认零行为变化。
 *
 * 双旋钮联动（用户拍板）：
 *  - 软件增益：识别循环里把麦克风采样按倍数放大再喂 VAD/识别引擎（安卓无公开麦克风硬件增益 API，
 *    数字放大是"功率更强"的等价实现）；小声话被放大，识别器"听得清"。
 *  - VAD 说话门槛：Silero 概率阈值，越低越小的声音也算"在说话"，极小声才开得了闸。
 *    只放大不降门槛，极小声开不了闸；只降门槛不放大，听到了也认不准——所以两旋钮必须联动。
 *
 * 风险边界：滑块只是一个开会话时读一次的浮点数，零结构变化、无崩溃面；
 * 高灵敏度的最坏情况是误识别变多，由既有三道保险兜底——媒体音量 80% 硬帽 / 同命令 1.5s 冷却 /
 * 8s≥6 次熔断（均为抖音外放事故定过的铁闸）。
 */
object RecognitionSensitivity {
    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 10
    const val DEFAULT_LEVEL = 5
    private const val PREF_KEY = "sensitivity_level"

    fun level(context: Context): Int =
        context.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getInt(PREF_KEY, DEFAULT_LEVEL).coerceIn(MIN_LEVEL, MAX_LEVEL)

    fun save(context: Context, level: Int) {
        context.getSharedPreferences("app", Context.MODE_PRIVATE)
            .edit().putInt(PREF_KEY, level.coerceIn(MIN_LEVEL, MAX_LEVEL)).apply()
    }

    /**
     * 软件增益（倍数）：两段居中线性格式，格 5 恰好 1.0×。
     * 格 1=0.5×（嘈杂环境防误触），格 10=2.25×（小声说话放大）。
     * 调整映射时必须保持格 5 = 1.0×、格 5 阈值 = 0.60f（历史基准，默认零变化）。
     */
    fun gain(level: Int): Float = when {
        level >= 10 -> 3.0f                          // v0.54.1 极限远场：3 倍放大（远场信号弱，clip 风险远场可忽略）
        level <= 5 -> 0.5f + (level - 1) * 0.125f    // 1→0.50 … 5→1.00
        else -> 1.0f + (level - 5) * 0.25f           // 5→1.00 … 9→2.00
    }

    /** VAD 说话门槛（Silero 概率阈值）：格 5 恰好 0.60（历史值）。
     *  v0.54.0 弱声专档：格 9=0.44；v0.54.1 极限远场：格 10=0.30（Silero 实践下限，再低开始把噪音当语音）；
     *  格 1~8 不变 */
    fun vadThreshold(level: Int): Float = when {
        level <= 5 -> 0.6f + (5 - level) * 0.05f    // 1→0.80 … 5→0.60
        level == 9 -> 0.44f
        level >= 10 -> 0.30f
        else -> 0.6f - (level - 5) * 0.036f         // 6→0.564 … 8→0.492
    }

    /** 弱声专档总开关（v0.54.0）：9~10 格=病友模式——更低门槛 + 停顿容忍 + 慢语速切句放宽 + AGC 强制开。
     *  适用：声音小/构音不清（鼻音重）的病友；代价是对环境声更敏感，嘈杂环境用 1~8 格。
     *  格 10 为极限远场（v0.54.1）：稍远距离/极弱声操控，近距离正常音量建议 9 格以下（3 倍增益近讲易削波） */
    fun weakVoiceMode(level: Int): Boolean = level >= 9

    /** VAD 句间停顿容忍（秒）：格 9=0.55s（构音障碍者字间停顿长）；v0.54.1 格 10=0.7s（远场混响+远距离停顿更散）；
     *  其余格 0.4s（说完到动手的延迟不变） */
    fun minSilence(level: Int): Float = when {
        level >= 10 -> 0.7f
        level == 9 -> 0.55f
        else -> 0.4f
    }

    /** VAD 超长句强制切句（秒）：格 9=5s（慢语速长句不被腰斩）；v0.54.1 格 10=8s（远场慢语速极限容忍）；其余格 3s */
    fun maxSpeech(level: Int): Float = when {
        level >= 10 -> 8f
        level == 9 -> 5f
        else -> 3f
    }

    /** VAD 最短开口时长（秒）：v0.54.1 格 10=0.2s（远场/极弱声的短促开口也能开门），其余格 0.25s */
    fun minSpeech(level: Int): Float = if (level >= 10) 0.2f else 0.25f
}
