package com.voicecontrol.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 诊断信息收集器（v0.48.0）：环形缓冲记录关键事件 + 一键收集设备/权限/无障碍状态。
 * 导出问题反馈时包含全部诊断段，远程定位问题不再依赖猜测。
 */
object DiagnosticsHelper {

    private const val MAX_EVENTS = 50
    private val events = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)

    /** 记录一条诊断事件（线程安全，环形缓冲自动淘汰旧条目） */
    @Synchronized
    fun log(event: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(Date())
        events.addLast("[$ts] $event")
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    /** 导出全部诊断事件（带时间戳，最新在后） */
    @Synchronized
    fun dumpEvents(): String {
        if (events.isEmpty()) return "（无事件）"
        return events.joinToString("\n")
    }

    /** 收集无障碍完整状态（导出用） */
    fun a11yStatus(context: Context): String {
        val sb = StringBuilder()
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: "(空)"
        val a11yOn = Settings.Secure.getInt(
            context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0
        )
        val btnTarget = Settings.Secure.getString(
            context.contentResolver, "accessibility_button_targets"
        ) ?: "(无)"
        sb.appendLine("无障碍开关原始值: $enabled")
        sb.appendLine("无障碍总开关: $a11yOn")
        sb.appendLine("无障碍按钮指派: $btnTarget")
        sb.appendLine("本服务绑定: ${AccessibilityHelper.isServiceEnabled(context)}")
        return sb.toString()
    }

    /** 收集设备信息（导出用） */
    fun deviceInfo(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("机型: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("系统: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        sb.appendLine("内存: 总 ${mem.totalMem / 1048576}MB / 可用 ${mem.availMem / 1048576}MB (低内存模式=${mem.lowMemory})")
        sb.appendLine("isLowRamDevice: ${am.isLowRamDevice}")
        val rt = Runtime.getRuntime()
        sb.appendLine("应用堆: 已用 ${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB / 上限 ${rt.maxMemory() / 1048576}MB")
        val dm = context.resources.displayMetrics
        sb.appendLine("屏幕: ${dm.widthPixels}x${dm.heightPixels} (密度 ${dm.densityDpi}dpi)")
        return sb.toString()
    }

    /** 收集权限状态（导出用） */
    fun permissionStatus(context: Context): String {
        val sb = StringBuilder()
        val perms = listOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.WRITE_SECURE_SETTINGS,
            android.Manifest.permission.FOREGROUND_SERVICE,
            android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        )
        for (p in perms) {
            val granted = context.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
            sb.appendLine("  $p: ${if (granted) "已授权" else "未授权"}")
        }
        return sb.toString()
    }
}
