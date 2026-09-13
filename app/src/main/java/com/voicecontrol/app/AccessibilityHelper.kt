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
     * v0.47.0 起无障碍服务注册名为伪装类路径（微信白名单按类名匹配，
     * 不需要随选朗读/悬浮窗/ST S 共存）。此属性为唯一 ComponentName 来源。
     */
    val SERVICE_COMPONENT: ComponentName
        get() = ComponentName(
            "com.voicecontrol.app",
            "com.google.android.accessibility.selecttospeak.SelectToSpeakService"
        )

    /**
     * 本应用无障碍服务是否已在系统里启用。
     * 兼容完整名 / 短名两种存储格式（MIUI / HyperOS 存短名，原生存完整名）。
     */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = SERVICE_COMPONENT
        val longForm = expected.flattenToString()
        val shortForm = expected.flattenToShortString()
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
     * 直接把开关写回去，系统会自动重绑服务——用户全程无感。
     *
     * 前提：持有 WRITE_SECURE_SETTINGS（设置界面点不出来，只能电脑 adb 授权）。
     * 2026-09-12 实证：adb install -r 重装会丢这个授权——每次重装后必须重新
     * pm grant，否则自愈静默跳过。
     *
     * 安全边界：只写「无障碍服务开关」这一项，绝不触碰麦克风/会话。
     */
    fun trySelfHeal(context: Context): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("AccessibilityHelper", "自愈跳过：未授权 WRITE_SECURE_SETTINGS")
            return false
        }
        if (isServiceEnabled(context)) return true // 本来就开着，无需修
        return runCatching {
            val expected = SERVICE_COMPONENT.flattenToString()
            val current = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val others = current.split(':')
                .filter { it.isNotBlank() && !it.equals(expected, ignoreCase = true) }
            val merged = if (others.isNotEmpty()) (others + expected).joinToString(":") else expected
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                merged
            )
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i("AccessibilityHelper", "自愈：无障碍开关已写回，等待系统重绑")
            true
        }.getOrDefault(false)
    }
}
