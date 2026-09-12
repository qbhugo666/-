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
        return runCatching {
            val expected = ComponentName(context, VoiceControlService::class.java)
            // 写完整组件名（与 adb 验证过的写法一致）；只追加自己，不动用户的其他无障碍服务
            val current = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val mine = expected.flattenToString()
            val others = current.split(':')
                .filter { it.isNotBlank() && !it.equals(mine, ignoreCase = true) }
            val merged = when {
                others.isNotEmpty() -> (others + mine).joinToString(":")
                // 2026-09-12 实测 HyperOS 撤开关是「外科手术式」只删本服务、其他服务保留，
                // 正常走上面分支即可；但若整串被清空（极端情况），只恢复自己会静默弄丢微信编号
                // （微信白名单依赖系统随选朗读在场，见 FEATURES/HANDOFF）——此时把它一起带上
                isSelectToSpeakInstalled(context) -> "$mine:$SELECT_TO_SPEAK"
                else -> mine
            }
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

    /** 系统「随选朗读」完整组件名——微信无障碍白名单兼容所需（2026-09-12 实测定论，勿删） */
    private const val SELECT_TO_SPEAK =
        "com.google.android.marvin.talkback/com.google.android.accessibility.selecttospeak.SelectToSpeakService"

    private fun isSelectToSpeakInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("com.google.android.marvin.talkback", 0)
        true
    }.getOrDefault(false)
}
