package com.voicecontrol.app

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType
import org.json.JSONObject

/** 汉字转拼音（无声调、小写、空格分隔音节；非汉字原样保留）。文件级私有，实例与 companion 共用。 */
private fun pinyinOf(s: String): String {
    val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }
    val sb = StringBuilder()
    for (ch in s) {
        if (ch.isWhitespace()) { sb.append(' '); continue }
        val arr = try {
            PinyinHelper.toHanyuPinyinStringArray(ch, format)
        } catch (e: Exception) { null }
        if (arr != null && arr.isNotEmpty()) sb.append(arr[0]) else sb.append(ch)
        sb.append(' ')
    }
    return sb.toString().trim()
}

/**
 * 命令匹配层：把语音识别结果纠正到命令词表。
 *
 * 匹配优先级（从高到低，命中即取最高分）：
 *   1. exact         —— 原文与命令/别名完全一致
 *   2. contains      —— 互相包含（治「向右滑动一下」「向右滑」这类多字/漏字）
 *   3. pinyin_exact  —— 拼音完全一致（治同音字，如「华动」→「滑动」）
 *   4. pinyin_fuzzy  —— 拼音音节级编辑距离，距离越小分越高（治近音、个别音节听错）
 *
 * 纯 Kotlin、无 Android 依赖，可做 JVM 单元测试（阶段一：测试基建）。
 * 用 [fromJson] 从 commands.json 文本构建；Android 端由调用方读 assets 后传入。
 */
class CommandMatcher private constructor(
    private val entries: List<Entry>,
) {
    data class Entry(
        val id: String,
        val action: String,
        val group: String,
        val word: String,
        val pinyin: String,
    )

    data class Match(
        val id: String,
        val action: String,
        val group: String,
        val matchedWord: String,
        val method: String,
    )

    /** 精确匹配：exact / contains / pinyin_exact（可靠，最优先）。匹配不到返回 null。 */
    fun matchStrict(text: String): Match? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val tp = pinyinOf(t)

        var best: Match? = null
        var bestScore = 0

        for (e in entries) {
            val score: Int
            val method: String
            when {
                t == e.word -> { score = 100; method = "exact" }
                t.contains(e.word) && e.word.length >= 2 -> { score = 90; method = "contains" }
                e.word.contains(t) && t.length >= 2 -> { score = 88; method = "contains" }
                tp == e.pinyin -> { score = 80; method = "pinyin_exact" }
                else -> continue
            }
            if (score > bestScore) {
                bestScore = score
                best = Match(e.id, e.action, e.group, e.word, method)
            }
        }
        return best
    }

    /** 模糊匹配：拼音音节级编辑距离（兜底纠错，仅在精确匹配和文字点击都未命中时用） */
    fun matchFuzzy(text: String): Match? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val tp = pinyinOf(t)

        var best: Match? = null
        var bestScore = 0

        for (e in entries) {
            val d = pinyinFuzzyDist(tp, e.pinyin)
            if (d >= 0) {
                val score = 70 - d * 10
                if (score > bestScore) {
                    bestScore = score
                    best = Match(e.id, e.action, e.group, e.word, "pinyin_fuzzy")
                }
            }
        }
        return best
    }

    /** 在候选词里找与 text 拼音最接近的词（文字点击目标纠错，如「抖婴」→「抖音」）；无相近返回 null */
    fun resolveClosest(text: String, candidates: List<String>): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (candidates.any { it == t }) return t
        val tp = pinyinOf(t)
        // 单音节目标不做热词纠错：App 名/屏幕词都是多字词，单字（如「吧」ba）会被拼音模糊距离
        // 误匹配到含该字的双字词（「贴吧」tie ba），造成「点击8→吧→贴吧」这类错误。
        if (tp.split(" ").count { it.isNotEmpty() } <= 1) return null
        var best: String? = null
        var bestDist = Int.MAX_VALUE
        for (c in candidates) {
            val d = pinyinFuzzyDist(tp, pinyinOf(c))
            if (d >= 0 && d < bestDist) {
                bestDist = d
                best = c
            }
        }
        return best
    }

    /** 提取所有热词（命令词 + 别名，去重、长词优先），供识别引擎做 contextual biasing */
    fun hotwords(): List<String> {
        return entries.map { it.word }.distinct().sortedByDescending { it.length }
    }

    /**
     * 指令撞车检查（v0.42.0 常用词准入）：候选词若与任何指令词/别名发音相近（拼音模糊距离达标），
     * 返回撞车的指令词；无撞车返回 null。用于把「能抢指令的常用词」挡在准入门外——
     * 例：「话动」撞「滑动」。纯比对，无副作用，JVM 可测。
     */
    fun commandCollision(word: String): String? {
        val wp = pinyinOf(word)
        if (wp.split(" ").count { it.isNotEmpty() } == 0) return null
        var best: String? = null
        var bestDist = Int.MAX_VALUE
        for (e in entries) {
            val d = pinyinFuzzyDist(wp, e.pinyin)
            if (d >= 0 && d < bestDist) {
                bestDist = d
                best = e.word
            }
        }
        return best
    }

    companion object {
        // 拼音模糊匹配：音节级编辑距离，最多容忍相差几个音节
        private const val PINYIN_FUZZY_MAX_DIST = 3
        // 拼音模糊匹配：归一化距离阈值（音节数越多越宽松）
        private const val PINYIN_FUZZY_THRESHOLD = 0.5

        /**
         * 在 text 中寻找与 target 拼音最接近的字符窗口（v0.41.0 替换功能用）：
         * 先精确 indexOf；否则按 target 字数滑窗，取拼音编辑距离最小且达标的窗口——
         * 治「屏幕上是『不错』、用户照读却被听成『不措』」的听岔（拼音同即命中）。
         * 返回命中的字符区间（含头不含尾）；找不到返回 null。
         */
        fun findFuzzyRange(text: String, target: String): IntRange? {
            if (target.isEmpty()) return null
            val exact = text.indexOf(target)
            if (exact >= 0) return IntRange(exact, exact + target.length - 1)
            if (text.isEmpty()) return null
            val targetPy = pinyinOf(target)
            if (targetPy.split(" ").count { it.isNotEmpty() } == 0) return null
            var best: IntRange? = null
            var bestDist = Int.MAX_VALUE
            for (start in 0..text.length - target.length) {
                val window = text.substring(start, start + target.length)
                val d = pinyinFuzzyDist(pinyinOf(window), targetPy)
                if (d in 0 until bestDist) {
                    bestDist = d
                    best = IntRange(start, start + target.length - 1)
                    if (d == 0) break   // 同音即最优，无需继续滑
                }
            }
            return best
        }

        /** 拼音音节级编辑距离：返回距离（不匹配返回 -1），距离越小越接近 */
        private fun pinyinFuzzyDist(a: String, b: String): Int {
            val aa = a.split(" ").filter { it.isNotEmpty() }
            val bb = b.split(" ").filter { it.isNotEmpty() }
            if (aa.isEmpty() || bb.isEmpty()) return -1
            val dist = levenshteinArr(aa, bb)
            val maxLen = maxOf(aa.size, bb.size)
            return if (dist <= PINYIN_FUZZY_MAX_DIST && dist.toDouble() / maxLen <= PINYIN_FUZZY_THRESHOLD) dist else -1
        }

        private fun levenshteinArr(a: List<String>, b: List<String>): Int {
            if (a.isEmpty()) return b.size
            if (b.isEmpty()) return a.size
            val dp = Array(a.size + 1) { IntArray(b.size + 1) }
            for (i in 0..a.size) dp[i][0] = i
            for (j in 0..b.size) dp[0][j] = j
            for (i in 1..a.size) {
                for (j in 1..b.size) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i - 1][j - 1] + cost)
                }
            }
            return dp[a.size][b.size]
        }

        /**
         * 从 commands.json 的文本内容构建匹配器；解析失败返回空匹配器（匹配不到任何命令）。
         * customBindings = (自定义说法, 目标动作)：v0.39.0 用户语音绑定的外挂别名——
         * 复用目标命令的 id/action/group 生成条目并**前插**（匹配同分时自定义优先=用户显式意图）；
         * 目标动作不存在则跳过（UI 只列既有动作，正常不会发生）。
         */
        fun fromJson(
            json: String,
            customBindings: List<Pair<String, String>> = emptyList(),
        ): CommandMatcher {
            val entries = mutableListOf<Entry>()
            return try {
                val root = JSONObject(json)
                val groups = root.getJSONArray("groups")
                for (gi in 0 until groups.length()) {
                    val g = groups.getJSONObject(gi)
                    val gid = g.getString("id")
                    val cmds = g.getJSONArray("commands")
                    for (ci in 0 until cmds.length()) {
                        val c = cmds.getJSONObject(ci)
                        val id = c.getString("id")
                        val action = c.getString("action")
                        entries.add(Entry(id, action, gid, c.getString("command"), pinyinOf(c.getString("command"))))
                        val aliases = c.optJSONArray("aliases")
                        if (aliases != null) {
                            for (ai in 0 until aliases.length()) {
                                val w = aliases.getString(ai)
                                entries.add(Entry(id, action, gid, w, pinyinOf(w)))
                            }
                        }
                    }
                }
                for ((phrase, action) in customBindings) {
                    val base = entries.firstOrNull { it.action == action }
                    if (base != null) {
                        entries.add(0, Entry(base.id, base.action, base.group, phrase, pinyinOf(phrase)))
                    } else if (action.startsWith("tap_number_") || action.startsWith("grid_tap_")) {
                        // 数字绑定（v0.50.0）：commands.json 无本体可搭车，自成条目前插（自定义优先）。
                        // action 合法性由 CustomBindings.isValidAction 在存储层把关，此处信任存储
                        entries.add(0, Entry("custom_$action", action, "custom", phrase, pinyinOf(phrase)))
                    }
                    // 动作既不存在也非数字 → 跳过（UI 只列可绑动作，正常不会发生）
                }
                CommandMatcher(entries)
            } catch (e: Exception) {
                CommandMatcher(emptyList())
            }
        }
    }
}
