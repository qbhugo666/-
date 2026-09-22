package com.voicecontrol.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 使用记录（v0.31.0）：由 SessionState.lastMatch 的自定义 setter 中央挂钩——
 * 一处改动捕获全部命令执行结果（成功✅/失败/提示），零散布点。
 * v0.57.0：记录识别原文（机器听到的话）——handleRecognized 开头 appendHeard() 建条，
 * 之后本句触发的 lastMatch 赋值自动关联进同一条；未触发任何操作的句子（语气词/未命中）
 * text 留空，使用记录页显示「未触发操作」。这样每句话「听到什么 → 做了什么」都能对上，
 * 误识别（说了 A 被听成 B）一眼可查。
 * 存储：内存列表 + filesDir/usage_log.json（上限 200 条，写穿）。
 * 保留期（v0.36.0）：仅保留最近 48 小时（用户拍板：隐私优先 + 不占存储），加载/写入时自动清理。
 * 持久化格式：v0.57.0 起为对象 {"t":时间,"h":原文,"x":结果}；加载兼容旧对象 {"t","x"}
 * 与更旧的数组 [[t,x],...]（v0.43.1 修复过的格式），旧条目 heard 为空照常显示。
 */
object UsageLog {

    data class Entry(val time: Long, val text: String, val heard: String = "")

    private const val MAX_ENTRIES = 200

    // 记录保留期：48 小时，超期自动清除
    private const val RETENTION_MS = 48 * 60 * 60 * 1000L

    private val entries = mutableListOf<Entry>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm", Locale.CHINA)

    /** 幂等初始化（VoiceService/MainActivity onCreate 各调一次）；加载历史记录并清理超期项。
     *  兼容三种持久化格式：新对象 {"t","h","x"}、旧对象 {"t","x"}、数组 [[t,x],...]，
     *  单条解析失败跳过、不整批丢弃 */
    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "usage_log.json")
        runCatching {
            if (file!!.exists()) {
                val arr = JSONArray(file!!.readText())
                for (i in 0 until arr.length()) {
                    val item = arr.get(i)
                    val entry = when (item) {
                        is JSONObject -> Entry(item.getLong("t"), item.optString("x"), item.optString("h"))
                        is JSONArray -> Entry(item.getLong(0), item.getString(1))
                        else -> null
                    } ?: continue
                    entries.add(entry)
                }
            }
            prune()
        }
    }

    /** 记录一句识别原文（v0.57.0）：handleRecognized 开头调用，本句后续的 lastMatch
     *  赋值经 append() 自动落到这一条上 */
    fun appendHeard(text: String) {
        if (text.isBlank()) return
        prune()
        entries.add(Entry(System.currentTimeMillis(), "", text.trim()))
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        persist()
    }

    fun append(text: String) {
        if (text.isBlank()) return
        prune()
        val last = entries.lastOrNull()
        if (last != null && last.heard.isNotBlank() && last.text.isBlank()) {
            // 本句识别已建条 → 执行结果关联进去
            entries[entries.size - 1] = last.copy(text = text)
        } else {
            entries.add(Entry(System.currentTimeMillis(), text))
        }
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        persist()
    }

    fun all(): List<Entry> = entries.toList()

    fun timeLabel(t: Long): String = fmt.format(Date(t))

    fun clear() {
        entries.clear()
        persist()
    }

    /** 清除超过保留期（48 小时）的记录 */
    private fun prune() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        entries.removeAll { it.time < cutoff }
    }

    private fun persist() {
        val f = file ?: return
        runCatching {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(JSONObject().put("t", e.time).put("h", e.heard).put("x", e.text))
            }
            f.writeText(arr.toString())
        }
    }
}
