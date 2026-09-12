package com.voicecontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 常用词校验单元测试（v0.42.0）：validateWord 各拒绝分支 + 指令撞车拦截。
 * validateWord 为纯函数（collisionCheck 注入），JVM 可测。
 */
class CustomVocabTest {

    private fun validate(word: String, existing: List<String> = emptyList()) =
        CustomVocab.validateWord(word, existing) { word ->
            // 模拟撞车检查：与「滑动」发音相近即拒绝（真实实现用 CommandMatcher.commandCollision）
            if (word == "话动" || word == "化动") "与指令「滑动」发音相近" else null
        }

    @Test fun `正常词语 通过`() {
        assertNull(validate("黄信豪"))
        assertNull(validate("康复中心"))
    }

    @Test fun `长度不足 拒绝`() {
        assertNotNull(validate("黄"))
    }

    @Test fun `长度超限 拒绝`() {
        assertNotNull(validate("黄信豪黄信豪黄信豪黄信豪黄"))
    }

    @Test fun `非汉字 拒绝`() {
        assertNotNull(validate("abc123"))
    }

    @Test fun `保护词 退出 拒绝`() {
        assertNotNull(validate("退出"))
        assertNotNull(validate("继续"))
    }

    @Test fun `语气词 拒绝`() {
        assertNotNull(validate("嗯嗯"))
    }

    @Test fun `重复添加 拒绝`() {
        assertNotNull(validate("黄信豪", existing = listOf("黄信豪")))
    }

    @Test fun `与指令发音相近 撞车拒绝`() {
        assertNotNull(validate("话动"))
        assertNotNull(validate("化动"))
    }

    // ===== commandCollision 真实实现（基于 commands.json 词表） =====

    private val matcher = CommandMatcher.fromJson(TEST_JSON)

    @Test fun `撞车检查 话动 命中 滑动`() {
        // 返回发音最接近的具体条目（别名「向上滑动」比标准词「向上轻扫」更贴近用户说法，提示更友好）
        assertEquals("向上滑动", matcher.commandCollision("话动"))
    }

    @Test fun `撞车检查 黄信豪 无撞车`() {
        assertNull(matcher.commandCollision("黄信豪"))
    }

    // ===== 听写纠错（v0.42.0 correctText：识别近音片段改写为常用词） =====

    @Test fun `纠错 王信豪 改写为 黄信豪`() {
        // 真机 wav 实证：TTS 念「黄信豪」，ASR 输出「王信豪」（wang vs huang 近音）→ 应改写
        assertEquals("黄信豪", CustomVocab.correctText("王信豪", listOf("黄信豪")))
    }

    @Test fun `纠错 句中近音片段`() {
        assertEquals("叫黄信豪来吃饭", CustomVocab.correctText("叫王信豪来吃饭", listOf("黄信豪")))
    }

    @Test fun `纠错 无常用词 原文返回`() {
        assertEquals("今天天气不错", CustomVocab.correctText("今天天气不错", emptyList()))
    }

    @Test fun `纠错 拼音差太远 不误改`() {
        assertEquals("快手极速版", CustomVocab.correctText("快手极速版", listOf("黄信豪")))
    }

    private companion object {
        // 与 assets/commands.json 中「滑动 + 导航」核心命令一致（测试基准，只覆盖已实现动作）
        val TEST_JSON = """
            {
              "groups": [
                {
                  "id": "basic_navigation",
                  "name": "基本浏览",
                  "commands": [
                    { "id": "swipe_up",    "command": "向上轻扫",  "aliases": ["向上滑动", "上滑", "往上滑"], "action": "swipe_up" },
                    { "id": "swipe_down",  "command": "向下轻扫",  "aliases": ["向下滑动", "下滑", "往下滑"], "action": "swipe_down" },
                    { "id": "swipe_left",  "command": "向左轻扫",  "aliases": ["向左滑动", "左滑", "往左滑"], "action": "swipe_left" },
                    { "id": "swipe_right", "command": "向右轻扫",  "aliases": ["向右滑动", "右滑", "往右滑"], "action": "swipe_right" },
                    { "id": "go_back",     "command": "返回",      "aliases": ["后退", "上一页"],           "action": "go_back" },
                    { "id": "go_home",     "command": "前往主屏幕","aliases": ["回主屏幕", "回桌面", "主页"], "action": "go_home" },
                    { "id": "open_recents","command": "打开 App 切换器", "aliases": ["最近任务", "后台"],   "action": "open_recents" }
                  ]
                }
              ]
            }
        """.trimIndent()
    }
}
