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
    fun bug_ducce_tingJian_notTrigger() {
        // v0.57.10 用户实锤：说「轻点」被听成「听见」(ting jian)，旧口径与「听写」(ting xie)
        // 整音节差 1 即误入听写；声韵口径收紧后 jian/xie 声韵全差不认，
        // 句子掉回命令通道被拼音模糊兜住（听见→轻点，CommandMatcherTest 已覆盖）
        assertFalse("听见", isDictationTrigger("听见"))
    }

    @Test
    fun bug_shanChu_notTrigger() {
        // v0.57.11 用户实锤回归：说「删除删除」第二遍（折叠/分段成「删除」）反进听写——
        // 「删除」(shan chu) 对「输入」(shu ru) 每音节半差（0.5+0.5=1.0）踩在 v0.57.10 的
        // 放行线（>1.0 才拒）上；二次收紧只容一个半差后拒绝
        // v0.57.12 另加在册命令优先（VoiceService 层 matchExact 判定，见 CommandMatcherTest）
        assertFalse("删除", isDictationTrigger("删除"))
        assertFalse("删除删除", isDictationTrigger("删除删除"))
    }

    @Test
    fun knownTradeOff_shuYu_documented() {
        // 音近容错的已知代价：「属于」(shu yu) 与「输入」(shu ru) 差一个音节，会触发；
        // 无实害——12 秒无输入自动超时退出，落笔还需输入框在场
        assertTrue("属于", isDictationTrigger("属于"))
    }
}
