package com.voicecontrol.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃记录器（v0.43.0，问题反馈升级）：注册全局未捕获异常处理器——
 * 崩溃瞬间把堆栈 + 机型 + 内存水位写入 files/crash/crash_时间.txt，
 * 记录后**原样交还系统默认处理器**（闪退行为不变，只多留一份现场），随后交由
 * 使用记录页「导出」打包进问题反馈——治「导出的日志抓不到崩溃证据」的结构性缺陷
 * （此前只过滤导出 VoiceControl 标签，AndroidRuntime/DEBUG/低内存杀进程记录全被排除）。
 *
 * 边界：只记录，不吞异常、不阻止闪退；native 崩溃（信号级）不经过 Java 处理器，
 * 由导出里的「系统错误日志」段（logcat -b crash + *:E）兜住。
 */
object CrashCatcher {

    private const val KEEP = 5

    fun register(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return   // 幂等：已注册过（双入口重复调用无害）
        Thread.setDefaultUncaughtExceptionHandler(Handler(context.applicationContext, previous))
    }

    fun crashDir(context: Context): File = File(context.filesDir, "crash")

    fun latest(context: Context): File? =
        crashDir(context).listFiles()?.maxByOrNull { it.lastModified() }

    fun clear(context: Context) {
        crashDir(context).listFiles()?.forEach { it.delete() }
    }

    private class Handler(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            runCatching { writeReport(context, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
                ?: android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashDir(context)
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val file = File(dir, "crash_$stamp.txt")
        PrintWriter(file.bufferedWriter(Charsets.UTF_8)).use { w ->
            val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            w.println("崩溃时间：${df.format(Date())}")
            w.println("线程：${thread.name}")
            w.println("机型：${Build.MANUFACTURER} ${Build.MODEL}  Android ${Build.VERSION.RELEASE}")
            runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                w.println("版本：v${info.versionName} (${info.longVersionCode})")
            }
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mem)
            w.println("内存：可用 ${mem.availMem / 1048576}MB / 总 ${mem.totalMem / 1048576}MB" +
                "（低内存模式=${mem.lowMemory}）")
            w.println()
            w.println(throwable.stackTraceToString())
            if (throwable.cause != null) {
                w.println("---- cause ----")
                w.println(throwable.cause?.stackTraceToString())
            }
        }
        // 只留最近 KEEP 份
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
    }

    /** 拼接全部崩溃记录文本（导出用）；无崩溃返回 null */
    fun dumpAll(context: Context): String? {
        val files = crashDir(context).listFiles()?.sortedByDescending { it.lastModified() } ?: return null
        if (files.isEmpty()) return null
        return files.joinToString("\n\n") { f ->
            "---- ${f.name} ----\n" + runCatching { f.readText() }.getOrDefault("(读取失败)")
        }
    }

    /** 内存画像一行（导出/崩溃记录通用） */
    fun memoryLine(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val runtime = Runtime.getRuntime()
        return "总 ${mem.totalMem / 1048576}MB / 可用 ${mem.availMem / 1048576}MB" +
            "（低内存模式=${mem.lowMemory}，isLowRam=${am.isLowRamDevice}）" +
            "；应用堆 已用 ${(runtime.totalMemory() - runtime.freeMemory()) / 1048576}MB" +
            " / 上限 ${runtime.maxMemory() / 1048576}MB"
    }
}
