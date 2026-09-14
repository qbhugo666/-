package com.voicecontrol.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 自定义说法绑定（v0.39.0）：用户用语音把自己的说法绑到既有动作上。
 * 场景：说话口齿不清的用户说「向上滑动」被识别成「乡上花东」——绑定后，说这句话就执行向上滑动。
 * 录入全程无打字：绑定页选动作 → 语音录入（捕获识别原文）→ 保存。
 *
 * 存储：filesDir/custom_bindings.json，格式 [{"phrase":"乡上花东","action":"swipe_up"}]。
 * 规则：
 *  - 一词一动作：同一短语重复绑定 = 覆盖（最新意图优先）；一个动作可挂多个说法
 *  - 绑定是「外挂别名」，不改动 commands.json 词表本体，标准说法照常可用
 *  - 校验：2~10 个汉字；语气词拒收；「退出」「继续」为保护词（求救/看门狗专用）不可绑
 *  - 保存即生效：VoiceService 按文件时间戳自动重建匹配器（绑定立刻参与匹配与热词偏置）
 */
object CustomBindings {
    data class Binding(val phrase: String, val action: String)

    /** 动作展示名（绑定页/列表用）：action id -> 标准说法。与 commands.json 的动作一一对应（退出除外：红线不可绑） */
    val ACTION_LABELS: Map<String, String> = mapOf(
        "swipe_up" to "向上滑动", "swipe_down" to "向下滑动",
        "swipe_left" to "向左滑动", "swipe_right" to "向右滑动",
        "go_back" to "返回", "go_home" to "回主屏幕", "open_recents" to "打开 App 切换器",
        "tap" to "轻点", "double_tap" to "双击", "long_press" to "长按",
        "show_labels" to "显示编号", "show_grid" to "显示网格", "hide_overlays" to "隐藏显示",
        "nudge_up" to "向上摇移", "nudge_down" to "向下摇移",
        "nudge_left" to "向左摇移", "nudge_right" to "向右摇移",
        "volume_up" to "增加音量", "volume_down" to "降低音量", "volume_mute" to "静音",
        "lock_screen" to "锁屏", "show_notifications" to "通知中心", "show_quick_settings" to "控制中心",
        "take_screenshot" to "截屏",
        "zoom_in" to "双指放大", "zoom_out" to "双指缩小", "grid_back" to "退回",
        "text_cursor_left" to "光标左移", "text_cursor_right" to "光标右移",
        "text_delete" to "删除", "text_clear" to "清空输入",
    )

    /** 绑定可选的动作（按展示名排序；不含 exit_session——退出走红线直达，绑定既无意义也危险） */
    val BINDABLE_ACTIONS: List<Pair<String, String>> =
        ACTION_LABELS.entries.map { it.key to it.value }.sortedBy { it.second }

    // 数字绑定（v0.50.0 用户提议，二级入口选数字避免摊平动作列表）：
    // tap_number_N=点击编号 N（编号 1~50，用户拍板）；grid_tap_N=点击第 N 格——
    // 网格 3×4 一层就 12 格，「第 N 格」永远指当前层，绑 13+ 永远点不到=坑用户，故上限 12
    const val MAX_NUMBER = 50
    const val GRID_CELLS = 12
    const val ACTION_PREFIX_TAP_NUMBER = "tap_number_"
    const val ACTION_PREFIX_GRID_TAP = "grid_tap_"

    /** 数字动作 → N（正数）；非数字动作返回 null */
    fun numberedActionValue(action: String): Int? {
        val n = when {
            action.startsWith(ACTION_PREFIX_TAP_NUMBER) ->
                action.removePrefix(ACTION_PREFIX_TAP_NUMBER).toIntOrNull()
            action.startsWith(ACTION_PREFIX_GRID_TAP) ->
                action.removePrefix(ACTION_PREFIX_GRID_TAP).toIntOrNull()
            else -> null
        }
        return if (n != null && n > 0) n else null
    }

    /** 动作 id 是否可绑：固定动作（ACTION_LABELS）或合法范围的数字动作（备份导入共用此门） */
    fun isValidAction(action: String): Boolean {
        if (action in ACTION_LABELS) return true
        val n = numberedActionValue(action) ?: return false
        return when {
            action.startsWith(ACTION_PREFIX_TAP_NUMBER) -> n in 1..MAX_NUMBER
            else -> n in 1..GRID_CELLS
        }
    }

    /** 动作展示名：固定动作查表；数字动作拼显示（「点击编号 7」「点击第 3 格」）；未知原样返回 */
    fun displayAction(action: String): String {
        ACTION_LABELS[action]?.let { return it }
        val n = numberedActionValue(action) ?: return action
        return if (action.startsWith(ACTION_PREFIX_TAP_NUMBER)) "点击编号 $n" else "点击第 $n 格"
    }

    // 保护词：求救/看门狗专用通道，不允许被自定义说法占用
    private val PROTECTED = setOf("退出", "继续")

    // 语气词：ASR 常把残音识别成这些，绑上去只会误触（与 VoiceService.NOISE_WORDS 同源口径）
    private val NOISE = setOf(
        "喂", "喂喂", "嗯", "嗯嗯", "呃", "啊", "啊啊", "哦", "噢",
        "哎", "唉", "呀", "哈", "哈哈", "嘿", "诶", "欸"
    )

    private fun file(context: Context): File = File(context.filesDir, "custom_bindings.json")

    /** 文件时间戳作为匹配器缓存版本（-1 = 无文件/无绑定） */
    fun stamp(context: Context): Long = file(context).let { if (it.exists()) it.lastModified() else -1L }

    fun all(context: Context): List<Binding> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val p = o.optString("phrase")
            val a = o.optString("action")
            if (p.isNotBlank() && a.isNotBlank()) Binding(p, a) else null
        }
    }.getOrDefault(emptyList())

    fun save(context: Context, list: List<Binding>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(JSONObject().put("phrase", b.phrase).put("action", b.action))
        }
        file(context).writeText(arr.toString())
    }

    /** 新增/覆盖（同短语覆盖旧绑定）。返回错误提示；null = 成功 */
    fun upsert(context: Context, rawPhrase: String, action: String): String? {
        val phrase = rawPhrase.replace(" ", "").trim()
        if (!isValidAction(action)) return "未知动作"
        if (phrase.length < 2 || phrase.length > 10) return "说法需要 2~10 个汉字"
        if (!phrase.all { it.code in 0x4E00..0x9FFF }) return "只能是汉字"
        if (phrase in NOISE) return "这是语气词，换一个有内容的说法"
        if (phrase in PROTECTED) return "「$phrase」是系统保护词，不能绑定"
        val rest = all(context).filterNot { it.phrase == phrase }
        save(context, rest + Binding(phrase, action))
        return null
    }

    fun remove(context: Context, phrase: String) {
        save(context, all(context).filterNot { it.phrase == phrase })
    }
}
