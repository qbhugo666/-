package com.voicecontrol.app

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

/**
 * 昼夜主题基类（v0.33.0 深色模式）：所有页面继承本类。
 *
 * 偏好存 SharedPreferences「app」/「dark_mode」：
 *   0=跟随系统（默认，系统黑夜自动变黑）· 1=浅色 · 2=深色
 * 原理：跟随系统时不做任何干预，-night 资源自动生效；
 * 手动指定时用 createConfigurationContext 只覆盖 uiMode 的黑夜位（类型位保留），
 * 让 values-night 资源在系统白天也能生效（无需 AppCompat）。
 * 改完偏好调用 recreate() 即可整页重载。
 */
abstract class ThemedActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(applyDarkPref(newBase))
    }

    companion object {
        const val DARK_FOLLOW_SYSTEM = 0
        const val DARK_LIGHT = 1
        const val DARK_DARK = 2

        fun applyDarkPref(ctx: Context): Context {
            val mode = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
                .getInt("dark_mode", DARK_FOLLOW_SYSTEM)
            if (mode == DARK_FOLLOW_SYSTEM) return ctx
            val nightBit = if (mode == DARK_DARK) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }
            val config = Configuration(ctx.resources.configuration)
            // 只清「夜间位」再放新值。2026-09-14 修复：旧代码误用 UI_MODE_TYPE_MASK（类型位），
            // 夜间位没被清掉——系统浅色时选深色会叠成非法值 0x30，values-night 永不匹配，选了不变黑
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightBit
            return ctx.createConfigurationContext(config)
        }
    }
}
