package com.voicecontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 命令匹配层单元测试（阶段一：测试基建）。
 *
 * 每个 bug 都固化成一条用例，改完代码跑一次就知道有没有回归，
 * 不再依赖真机手测 + 抓 logcat。
 *
 * 注意：v0.6 起 match() 拆成 matchStrict()（精确/包含/同音）与 matchFuzzy()（拼音编辑距离兜底），
 * 用例据此分别断言，与识别层的分层调用顺序一致。
 */
class CommandMatcherTest {

    private val matcher = CommandMatcher.fromJson(TEST_JSON)

    // ===== 精确匹配（matchStrict） =====

    @Test fun `精确匹配 向左滑动`() {
        assertEquals("swipe_left", matcher.matchStrict("向左滑动")?.action)
    }

    @Test fun `精确匹配 上滑`() {
        assertEquals("swipe_up", matcher.matchStrict("上滑")?.action)
    }

    @Test fun `精确匹配 下滑`() {
        assertEquals("swipe_down", matcher.matchStrict("下滑")?.action)
    }

    @Test fun `精确匹配 返回`() {
        assertEquals("go_back", matcher.matchStrict("返回")?.action)
    }

    @Test fun `主页别名已下架 不再匹配`() {
        // v0.55.13 用户拍板：主页/回主页/最近应用/关闭 属高频误触别名，下架
        assertNull(matcher.matchStrict("主页"))
    }

    // ===== 包含匹配（多字/漏字） =====

    @Test fun `包含匹配 向右滑`() {
        assertEquals("swipe_right", matcher.matchStrict("向右滑")?.action)
    }

    @Test fun `包含匹配 在右滑动`() {
        assertEquals("swipe_right", matcher.matchStrict("在右滑动")?.action)
    }

    // ===== 同音字纠错（pinyin_exact，属于 strict） =====

    @Test fun `同音字 华动 匹配 滑动`() {
        assertEquals("swipe_up", matcher.matchStrict("向上华动")?.action)
    }

    // ===== 拼音模糊匹配（matchFuzzy 兜底） =====

    @Test fun `bug回归 向左华 应匹配左滑而非上滑`() {
        assertEquals("swipe_left", matcher.matchFuzzy("向左华")?.action)
    }

    @Test fun `bug回归 又滑动 应匹配右滑而非上滑`() {
        assertEquals("swipe_right", matcher.matchFuzzy("又滑动")?.action)
    }

    // ===== 匹配不到的情况 =====

    @Test fun `无意义文本 不匹配`() {
        assertNull(matcher.matchStrict("个活动"))
        assertNull(matcher.matchFuzzy("个活动"))
    }

    // ===== 自定义说法绑定（v0.39.0：动作自适应用户自己的说法） =====

    private val boundMatcher = CommandMatcher.fromJson(
        TEST_JSON,
        customBindings = listOf("乡上花东" to "swipe_up", "花花动" to "go_back"),
    )

    @Test fun `自定义说法 精确命中`() {
        assertEquals("swipe_up", boundMatcher.matchStrict("乡上花东")?.action)
        assertEquals("go_back", boundMatcher.matchStrict("花花动")?.action)
    }

    @Test fun `自定义说法 包含命中`() {
        assertEquals("swipe_up", boundMatcher.matchStrict("帮我乡上花东一下")?.action)
    }

    @Test fun `自定义说法 平分时优先于标准别名`() {
        // 「返回」既是标准 go_back 别名又被用户绑到 swipe_up：自定义是用户显式意图，应赢
        val m = CommandMatcher.fromJson(TEST_JSON, customBindings = listOf("返回" to "swipe_up"))
        assertEquals("swipe_up", m.matchStrict("返回")?.action)
    }

    @Test fun `自定义说法 进入热词表`() {
        org.junit.Assert.assertTrue(boundMatcher.hotwords().contains("乡上花东"))
    }

    @Test fun `未知动作的绑定 被跳过不影响标准词表`() {
        val m = CommandMatcher.fromJson(TEST_JSON, customBindings = listOf("随便啥" to "nope"))
        assertNull(m.matchStrict("随便啥"))
        assertEquals("swipe_up", m.matchStrict("上滑")?.action)
    }

    @Test fun `无绑定 标准匹配零变化`() {
        assertEquals("go_back", matcher.matchStrict("返回")?.action)
    }

    // ===== 文字输入命令（v0.40.0，commands.json v7 text_edit 组） =====

    private val textMatcher = CommandMatcher.fromJson(
        TEST_JSON.replace(
            """{ "id": "open_recents",""",
            """{ "id": "text_cursor_left",  "command": "光标左移", "aliases": ["光标向左", "左移光标"], "action": "text_cursor_left" },
                    { "id": "text_cursor_right", "command": "光标右移", "aliases": ["光标向右", "右移光标"], "action": "text_cursor_right" },
                    { "id": "text_delete",       "command": "删除",     "aliases": ["删掉", "删一个字", "退格"], "action": "text_delete" },
                    { "id": "text_clear",        "command": "清空输入", "aliases": ["清空输入框", "清空"], "action": "text_clear" },
                    { "id": "open_recents","""
        )
    )

    @Test fun `文字输入 光标左移 精确匹配`() {
        assertEquals("text_cursor_left", textMatcher.matchStrict("光标左移")?.action)
    }

    @Test fun `文字输入 删除 精确匹配`() {
        assertEquals("text_delete", textMatcher.matchStrict("删除")?.action)
    }

    @Test fun `文字输入 清空输入框 别名匹配`() {
        assertEquals("text_clear", textMatcher.matchStrict("清空输入框")?.action)
    }

    // ===== 替换找词（v0.41.0 findFuzzyRange：精确 + 拼音模糊滑窗） =====

    @Test fun `替换找词 精确子串`() {
        assertEquals(4..5, CommandMatcher.findFuzzyRange("今天天气不错", "不错"))
    }

    @Test fun `替换找词 拼音相同听岔命中`() {
        // 屏幕是「不错」、照读被听成「不措」：拼音相同即命中
        assertEquals(4..5, CommandMatcher.findFuzzyRange("今天天气不错", "不措"))
    }

    @Test fun `替换找词 无命中返回null`() {
        assertNull(CommandMatcher.findFuzzyRange("今天天气不错", "抖音"))
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
                    { "id": "go_home",     "command": "前往主屏幕","aliases": ["回主屏幕", "回桌面", "回首页"], "action": "go_home" },
                    { "id": "open_recents","command": "打开 App 切换器", "aliases": ["最近任务", "后台"],   "action": "open_recents" }
                  ]
                }
              ]
            }
        """.trimIndent()
    }
}
