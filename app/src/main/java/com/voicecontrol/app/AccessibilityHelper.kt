package com.voicecontrol.app

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.util.Log

/**
 * 无障碍服务状态检测。
 *
 * 为什么需要它（商用产品的标准做法）：
 *  1. Android 出于安全不允许任何 App 静默开启无障碍（防恶意软件）；
 *  2. 系统省电/清理策略可能把它关掉（如 MIUI「一键清理」）；
 *  3. 服务被关后 App 一切动作失效，用户只看到「没反应」，不知道原因。
 *
 * 因此产品闭环是：检测 → 引导弹框 → 一键跳设置 → 回来自动续接，
 * 而不是让用户对着失效的界面猜。
 */
object AccessibilityHelper {

    /**
     * 本应用无障碍服务是否已在系统里启用。
     * 兼容完整名 / 短名两种存储格式（MIUI / HyperOS 存短名，原生存完整名）。
     */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, VoiceControlService::class.java)
        val longForm = expected.flattenToString()       // com.voicecontrol.app/com.voicecontrol.app.VoiceControlService
        val shortForm = expected.flattenToShortString() // com.voicecontrol.app/.VoiceControlService
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val component = splitter.next()
            if (component.equals(longForm, ignoreCase = true) ||
                component.equals(shortForm, ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    /**
     * 静默自愈：无障碍开关被系统撤销时（2026-09-09 实证 HyperOS 强杀进程会连开关一起置空），
     * 直接把开关写回去，系统会自动重绑服务——用户全程无感，替代「弹窗引导手动开启」。
     *
     * 前提：持有 WRITE_SECURE_SETTINGS（设置界面点不出来，只能电脑 adb 授权）。
     * **2026-09-12 实证：adb install -r 重装会丢这个授权**——每次重装后必须重新
     * `pm grant`，否则自愈静默跳过（当天用户报障「清后台后重开也不恢复」的根因即此）。
     * 未授权返回 false，调用方退回原有手动引导路径——未授权用户零感知、零损失。
     *
     * 安全边界：只写「无障碍服务开关」这一项，绝不触碰麦克风/会话——
     * 会话的开与关仍然只由用户语音和看门狗决定。
     */
    fun trySelfHeal(context: Context): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("AccessibilityHelper", "自愈跳过：未授权 WRITE_SECURE_SETTINGS")
            return false
        }
        if (isServiceEnabled(context)) return true // 本来就开着，无需修
        // v0.44.0 起统一走「保活双组件」路径：本服务 + 随选朗读（微信白名单钥匙）一起确保在场
        return ensureWeChatCompat(context)
    }

    /** 系统「随选朗读」完整组件名——微信无障碍白名单兼容所需（2026-09-12 实测定论，勿删） */
    private const val SELECT_TO_SPEAK =
        "com.google.android.marvin.talkback/com.google.android.accessibility.selecttospeak.SelectToSpeakService"

    private fun isSelectToSpeakInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("com.google.android.marvin.talkback", 0)
        true
    }.getOrDefault(false)

    /**
     * 微信编号兼容（v0.44.0，默认强制保活，零用户决策）：确保系统「随选朗读」与我们的服务同时在场——
     * 微信 8.0.52+ 只把界面树交给「场上有可信读屏服务」的设备（2026-09-12 对照实测定论）。
     * 每次 VoiceService 启动时调用：若随选朗读未启用且我们持有 WRITE_SECURE_SETTINGS，
     * 自动并入无障碍开关（幂等，不删他人服务）——HyperOS 清后台/撤开关后下次启动自动带回来。
     *
     * 边界（GUARDRAILS A4 三问）：触发=每次服务启动；最坏=多开一个系统自带无障碍服务
     * （实测零可见副作用，可随时在系统设置手动关闭，但下次会话会自动恢复——设计意图）；
     * 叫停=关闭言出法随的无障碍服务或卸载。
     * 返回 true = 随选朗读已在场（原本就开或本次已并入）。
     */
    fun ensureWeChatCompat(context: Context): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("AccessibilityHelper", "微信兼容跳过：未授权 WRITE_SECURE_SETTINGS")
            return false
        }
        val current = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val parts = current.split(':').filter { it.isNotBlank() }.toMutableList()
        val mine = ComponentName(context, VoiceControlService::class.java).flattenToString()
        var changed = false
        // ① 本服务必须在场（自愈核心：被系统/清后台撤销时写回）
        if (parts.none { it.equals(mine, ignoreCase = true) }) {
            parts.add(mine)
            changed = true
        }
        // ② 随选朗读在场（微信白名单钥匙；已安装才带——无谷歌套件的品牌走各自读屏适配）
        if (isSelectToSpeakInstalled(context) &&
            parts.none { it.equals(SELECT_TO_SPEAK, ignoreCase = true) }
        ) {
            parts.add(SELECT_TO_SPEAK)
            changed = true
        }
        if (!changed) return true
        return runCatching {
            Settings.Secure.putString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                parts.joinToString(":")
            )
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i("AccessibilityHelper", "无障碍开关已修复（双组件）: ${parts.joinToString(":")}")
            true
        }.getOrDefault(false)
    }

    /** 无障碍状态一行（导出反馈诊断用） */
    fun statusLine(context: Context): String {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: "(空)"
        val bound = isServiceEnabled(context)
        val sts = enabled.contains("SelectToSpeakService", ignoreCase = true)
        return "已启用=[$enabled]；本服务绑定=$bound；随选朗读在场=$sts"
    }
}
