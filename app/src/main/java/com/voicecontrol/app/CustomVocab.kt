package com.voicecontrol.app

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 自定义常用词（v0.42.0）：用户自己的个人热词（人名/地名等），加入识别引擎的热词偏置——
 * 说 huang xin hao 被优先解码成「黄信豪」。与「自定义说法绑定」互相独立：
 * 绑定=说法触发动作；常用词=让听写/聊天把声音转成正确的字，全线（含听写落笔）受益。
 *
 * 存储：files/custom_vocab.json，格式 ["黄信豪","钉钉群",...]（纯字符串数组）。
 *
 * **不干扰操控的三层保障（用户明确要求，2026-09-12）**：
 * 1. 独立名单独立文件，不写进 commands.json 指令词表；
 * 2. 指令词的热词偏置（8.0 分）零改动，常用词只是额外来源；
 * 3. [validateWord] 拦截「与指令发音相近」的撞车词（CommandMatcher.commandCollision）——
 *    名单里永远不会出现能抢指令的词（如撞「滑动」的「话动」）。
 *
 * 生效时机：writeHotwords() 在每次会话建识别器时重新生成 → 保存后**下次会话生效**。
 */
object CustomVocab {
    const val MAX_WORDS = 50
    const val MIN_LEN = 2
    const val MAX_LEN = 10

    /** 保护词：求救/看门狗专用通道，绝不允许被加成常用词（防 ASR 把话误转成「退出」→断会话） */
    private val PROTECTED = setOf("退出", "继续")

    /** 语气词：无信息量，进热词只会误触（与 VoiceService.NOISE_WORDS 同源口径） */
    private val NOISE = setOf(
        "喂", "喂喂", "嗯", "嗯嗯", "呃", "啊", "啊啊", "哦", "噢",
        "哎", "唉", "呀", "哈", "哈哈", "嘿", "诶", "欸"
    )

    private fun file(context: Context): File = File(context.filesDir, "custom_vocab.json")

    /** 文件时间戳作为匹配器/热词缓存版本（-1 = 无文件/无词） */
    fun stamp(context: Context): Long = file(context).let { if (it.exists()) it.lastModified() else -1L }

    fun all(context: Context): List<String> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { i ->
            val w = arr.optString(i)
            if (w.isNotBlank()) w else null
        }
    }.getOrDefault(emptyList())

    fun save(context: Context, words: List<String>) {
        val arr = JSONArray()
        words.forEach { arr.put(it) }
        file(context).writeText(arr.toString())
    }

    /**
     * 校验（纯函数，JVM 可测）：返回错误提示；null = 通过。
     * [collisionCheck] 由调用方注入指令撞车检查（需要加载指令词表）。
     */
    fun validateWord(
        raw: String,
        existing: List<String>,
        collisionCheck: (String) -> String? = { null },
    ): String? {
        val word = raw.replace(" ", "").trim()
        if (word.length < MIN_LEN || word.length > MAX_LEN) return "词语需要 $MIN_LEN~$MAX_LEN 个字"
        if (!word.all { it.code in 0x4E00..0x9FFF }) return "只能是汉字"
        if (word in NOISE) return "这是语气词，不能作为常用词"
        if (word in PROTECTED) return "「$word」是系统保护词，不能添加"
        if (existing.contains(word)) return "该词已存在"
        if (existing.size >= MAX_WORDS) return "常用词最多 $MAX_WORDS 个"
        collisionCheck(word)?.let { return it }
        return null
    }

    /** 新增（调用前先 validateWord）。返回错误提示；null = 成功 */
    fun add(context: Context, raw: String, collisionCheck: (String) -> String? = { null }): String? {
        val word = raw.replace(" ", "").trim()
        val err = validateWord(word, all(context), collisionCheck)
        if (err != null) return err
        save(context, all(context) + word)
        return null
    }

    fun remove(context: Context, word: String) {
        save(context, all(context).filterNot { it == word })
    }

    /**
     * 听写纠错（v0.42.0 纯函数，JVM 可测）：识别原文里若出现与常用词拼音相近的片段，
     * 改写为常用词——「王信豪」→「黄信豪」（ASR 同音/近音字不可控，热词偏置在 greedy 下
     * 实测无效，故在文本层兜底）。长词优先，逐词替换；无命中原文返回。
     * 只用于听写落笔（打字内容），绝不用于指令匹配——操控与输入互不干扰。
     */
    fun correctText(text: String, vocab: List<String>): String {
        var result = text
        for (word in vocab.sortedByDescending { it.length }) {
            val range = CommandMatcher.findFuzzyRange(result, word) ?: continue
            if (result.substring(range.first, range.last + 1) == word) continue
            result = result.substring(0, range.first) + word + result.substring(range.last + 1)
        }
        return result
    }
}
