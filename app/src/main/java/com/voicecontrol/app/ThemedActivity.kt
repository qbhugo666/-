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
 * 手动指定时用 createConfigurationContext 覆盖 uiMode 的黑夜位，
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
            config.uiMode = (config.uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or nightBit
            return ctx.createConfigurationContext(config)
        }
    }
}
