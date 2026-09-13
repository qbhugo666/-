package com.google.android.accessibility.selecttospeak

import com.voicecontrol.app.VoiceControlService

/**
 * 伪装类（v0.47.0）：类路径与系统「随选朗读」服务完全一致——
 * 微信 8.0.52+ 白名单按无障碍服务类名匹配，此类名使微信直接放行界面树。
 *
 * 继承 VoiceControlService，全部功能不变（编号/点击/听写/替换/自定义指令…）。
 * Manifest 中 android:name 指向此类。
 *
 * 背景：此前依赖与真实随选朗读「共存」来通过白名单（v0.42.0–v0.46.x），
 * 但用户反馈悬浮图标碍事且无电脑用户开不了随选朗读。
 * 伪装方案从根源解决：不需要随选朗读在场，服务类名本身就是钥匙。
 *
 * 如微信未来精确匹配签名导致伪装失效，一行改回真实类名即可回退。
 */
class SelectToSpeakService : VoiceControlService()
