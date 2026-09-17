package com.voicecontrol.app

/**
 * 听写触发词与判定（v0.40.0 仅「输入/听写」整句精确匹配；v0.55.12 扩容）。
 *
 * 背景：用户实测「输入」难触发——二字皆软辅音（sh+r）极易被听糊，而原判定是
 * 整句精确等于，识别输出「书入/叔入」就完全不认。三招扩容：
 *   1. 更多好念的触发词（打字/我要打字…——爆破音 d/z 响亮清晰）；
 *   2. 短句拼音容错：整句 2 音节、与核心词（输入/听写/打字）至多差 1 个音节即命中
 *      （「书入」= shu ru 同音必中；「大字」近音也救回）；
 *   3. 抽为纯 Kotlin 顶层函数，可 JVM 单元测试固化。
 * 已知代价（可接受）：整句「属于」音近也会触发听写——无实害（12 秒无输入自动超时退出，
 * 且听写落笔需要输入框在场，无框只提示不静默）。
 */

/** 听写触发词（整句精确命中；长短语给足声学信号更稳，短词保快） */
val DICTATION_TRIGGERS = listOf(
    "输入", "听写", "打字",
    "我要输入", "我要打字", "开始输入", "开始打字",
    "语音输入", "帮我输入", "帮我打字",
)

/** 核心短触发词：短句拼音容错以此为基准 */
private val CORE_TRIGGERS = listOf("输入", "听写", "打字")

private fun syllablesOf(s: String): List<String> =
    pinyinOf(s).split(' ').filter { it.isNotEmpty() }

/** 整句音节数与触发词相同、且至多差 1 个音节（同音/近音听岔容错） */
private fun nearSyllables(text: String, triggerSyllables: List<String>): Boolean {
    val a = syllablesOf(text)
    if (a.size != triggerSyllables.size) return false
    var diff = 0
    for (i in a.indices) {
        if (a[i] != triggerSyllables[i]) {
            diff++
            if (diff > 1) return false
        }
    }
    return true
}

/** 是否为听写触发句：在「继续」检查之后、一般命令匹配之前调用 */
fun isDictationTrigger(text: String): Boolean {
    // 剥首尾标点：SenseVoice 长句带标点输出，别让「输入，」功亏一篑
    val t = text.trim()
        .trim('，', '。', '！', '？', '…', ',', '.', '!', '?')
        .trim()
    if (t.isEmpty()) return false
    if (t in DICTATION_TRIGGERS) return true
    if (syllablesOf(t).size != 2) return false   // 容错只针对核心短词的 2 音节整句
    return CORE_TRIGGERS.any { nearSyllables(t, syllablesOf(it)) }
}
