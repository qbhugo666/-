package com.voicecontrol.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 听写触发扩容（v0.55.12）回归测试：新增触发词、拼音容错、防误触三组 */
class DictationTriggerTest {

    @Test
    fun exactTriggers_allFire() {
        DICTATION_TRIGGERS.forEach { assertTrue(it, isDictationTrigger(it)) }
    }

    @Test
    fun pinyinNearMiss_rescued() {
        // 「输入」shu ru 的同音/近音听岔
        assertTrue("书入", isDictationTrigger("书入"))
        assertTrue("叔入", isDictationTrigger("叔入"))
        assertTrue("输如", isDictationTrigger("输如"))
        // 「打字」da zi 的近音
        assertTrue("大字", isDictationTrigger("大字"))
        // 「听写」ting xie 的近音
        assertTrue("停写", isDictationTrigger("停写"))
    }

    @Test
    fun otherCommands_andNoise_doNotFire() {
        // 现有 2 音节命令一个都不能抢
        assertFalse("返回", isDictationTrigger("返回"))
        assertFalse("主页", isDictationTrigger("主页"))
        assertFalse("上滑", isDictationTrigger("上滑"))
        assertFalse("右滑", isDictationTrigger("右滑"))
        assertFalse("双击", isDictationTrigger("双击"))
        assertFalse("轻点", isDictationTrigger("轻点"))
        // 3 音节及以上的普通句子不触发（容错只针对 2 音节整句）
        assertFalse("点击微信", isDictationTrigger("点击微信"))
        assertFalse("你好", isDictationTrigger("你好"))
        assertFalse("", isDictationTrigger(""))
    }

    @Test
    fun knownTradeOff_shuYu_documented() {
        // 音近容错的已知代价：「属于」(shu yu) 与「输入」(shu ru) 差一个音节，会触发；
        // 无实害——12 秒无输入自动超时退出，落笔还需输入框在场
        assertTrue("属于", isDictationTrigger("属于"))
    }
}
