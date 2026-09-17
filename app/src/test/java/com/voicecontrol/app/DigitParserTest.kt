package com.voicecontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 数字同音表大扩容（v0.55.11）回归测试：用户实测 1~10 识别准确度一般，
 * 表扩到每位数全量同音/近音覆盖。这里固化「听岔输出 → 正确数字」的关键映射。
 */
class DigitParserTest {

    /** 同音纠正：ASR 常见听岔 → 数字汉字 */
    @Test
    fun normalize_coversCommonHomophones() {
        // 一
        assertEquals("一", DigitParser.normalizeDigitHomophones("衣"))
        assertEquals("一", DigitParser.normalizeDigitHomophones("医"))
        assertEquals("一", DigitParser.normalizeDigitHomophones("以"))
        // 二 / 三（v0.55.11 前三的同音完全缺席）
        assertEquals("二", DigitParser.normalizeDigitHomophones("饵"))
        assertEquals("三", DigitParser.normalizeDigitHomophones("山"))
        assertEquals("三", DigitParser.normalizeDigitHomophones("伞"))
        // 四
        assertEquals("四", DigitParser.normalizeDigitHomophones("斯"))
        assertEquals("四", DigitParser.normalizeDigitHomophones("死"))
        assertEquals("四", DigitParser.normalizeDigitHomophones("丝"))
        // 五 / 六
        assertEquals("五", DigitParser.normalizeDigitHomophones("误"))
        assertEquals("六", DigitParser.normalizeDigitHomophones("流"))
        // 七 / 九
        assertEquals("七", DigitParser.normalizeDigitHomophones("骑"))
        assertEquals("九", DigitParser.normalizeDigitHomophones("旧"))
        // 十（shi 音节全家桶）
        assertEquals("十", DigitParser.normalizeDigitHomophones("是"))
        assertEquals("十", DigitParser.normalizeDigitHomophones("事"))
        assertEquals("十", DigitParser.normalizeDigitHomophones("师"))
    }

    /** 组合纠错 + 解析：整条「听岔的两位数」→ 正确数值 */
    @Test
    fun parse_misheardTwoDigits() {
        fun num(raw: String): Int? = DigitParser.parseChineseNumber(DigitParser.normalizeDigitHomophones(raw))
        assertEquals(18, num("是八"))     // 「十八」的十被听成是
        assertEquals(40, num("斯十"))     // 「四十」的四被听成斯
        assertEquals(45, num("斯舞"))     // 「四十五」缺十的逐位读法变体
        assertEquals(14, num("一四"))     // 逐位读法兜底（既有行为）
        assertEquals(16, num("一六"))
        assertEquals(2, num("两"))
        assertEquals(10, num("十"))
    }

    /** 解析直读：规范输入不受扩容影响（防回归） */
    @Test
    fun parse_plainInputsUnchanged() {
        assertEquals(40, DigitParser.parseChineseNumber("四十"))
        assertEquals(14, DigitParser.parseChineseNumber("十四"))
        assertEquals(99, DigitParser.parseChineseNumber("九十九"))
        assertEquals(7, DigitParser.parseChineseNumber("七"))
        assertEquals(8, DigitParser.parseChineseNumber("8"))
        assertNull(DigitParser.parseChineseNumber("urchin"))
    }

    /** 普通汉字不受同音表影响（表只在数字解析环节使用，但也要保证非数字字原样保留） */
    @Test
    fun normalize_keepsNonDigitChars() {
        assertEquals("滑动", DigitParser.normalizeDigitHomophones("滑动"))
        assertEquals("你好", DigitParser.normalizeDigitHomophones("你好"))
    }
}
