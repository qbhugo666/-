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
 * 存储：内存列表 + filesDir/usage_log.json（上限 100 条，写穿）。
 * 保留期（v0.36.0）：仅保留最近 48 小时（用户拍板：隐私优先 + 不占存储），加载/写入时自动清理。
 */
object UsageLog {

    data class Entry(val time: Long, val text: String)

    private const val MAX_ENTRIES = 100

    // 记录保留期：48 小时，超期自动清除
    private const val RETENTION_MS = 48 * 60 * 60 * 1000L

    private val entries = mutableListOf<Entry>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm", Locale.CHINA)

    /** 幂等初始化（VoiceService/MainActivity onCreate 各调一次）；加载历史记录并清理超期项。
     *  兼容两种持久化格式（v0.43.1 修复：persist 写的是数组套数组 [[t,x],...]，
     *  init 原来按对象 {"t","x"} 解析——格式不匹配导致每次进程重启历史清零，
     *  单条解析失败跳过、不整批丢弃） */
    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "usage_log.json")
        runCatching {
            if (file!!.exists()) {
                val arr = JSONArray(file!!.readText())
                for (i in 0 until arr.length()) {
                    val item = arr.get(i)
                    val entry = when (item) {
                        is JSONObject -> Entry(item.getLong("t"), item.getString("x"))
                        is JSONArray -> Entry(item.getLong(0), item.getString(1))
                        else -> null
                    } ?: continue
                    entries.add(entry)
                }
            }
            prune()
        }
    }

    fun append(text: String) {
        if (text.isBlank()) return
        prune()
        entries.add(Entry(System.currentTimeMillis(), text))
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
            entries.forEach { arr.put(JSONArray().put(it.time).put(it.text)) }
            f.writeText(arr.toString())
        }
    }
}
