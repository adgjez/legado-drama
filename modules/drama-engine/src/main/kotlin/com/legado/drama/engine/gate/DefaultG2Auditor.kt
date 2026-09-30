package com.legado.drama.engine.gate

import com.legado.drama.engine.model.AssetMeta
import com.legado.drama.engine.provider.ChatMessage
import com.legado.drama.engine.provider.ChatRequest
import com.legado.drama.engine.provider.TextProvider

/**
 * G2 多模态质量审计器（对齐源 ai-drama-factory core/quality/AssetAuditor.kt 的 G2 部分语义）。
 *
 * 先硬后软：G1 文件级硬校验由调用方（DefaultAssetGate.checkG1 / 编排层）先行完成并落 g1State；
 * 本审计器只负责 G2 多模态打分：
 *  1. 无图（imageData == null）→ 无法审计，直接拒（0 分，等价源 inspection 失败分支）；
 *  2. 输入熔断：发送前估算 token（prompt + 图像 base64），超 40 万安全上限直接拒、绝不发 API
 *     （对齐源 ERROR_CONTEXT_OVERLOAD：防 ContextWindowExceeded）；
 *  3. 循环调 TextProvider.chat（OpenAI 视觉格式：content = text + image_url data URI），
 *     失败仅计数，成功即 break（退避由 Provider/rateGate 层负责，对齐源"仅计数"约定）；
 *  4. 解析模型 JSON {score, notes, defects, face_ratio}（容错：取首个完整 {...}，容忍前后废话）；
 *  5. defects 非空 → 直接拒（硬惩罚，不被总分稀释，对齐源 ERROR_DEFECT_DETECTED）；
 *  6. score < minQualityScore → 拒（对齐源 ERROR_QUALITY_BELOW_THRESHOLD）。
 *
 * score 统一为本地契约 0..100 制（G2Result.passed = defects 空 && score >= MIN_G2_SCORE=60）。
 * 纯 Kotlin + 注入 [TextProvider]，JVM 可单测；不引入 Android / 网络依赖。
 */
class DefaultG2Auditor(
    private val textProvider: TextProvider,
    private val model: String = "",
    private val rules: G2AuditRules = G2AuditRules(),
) : G2Auditor {

    /** 单次审计通道返回（对齐源 ChannelResult，maxAttempts 内最后一次成功结果）。 */
    data class ChannelResult(
        val score: Double = 0.0,               // 0..1（模型输出制式，下同）
        val notes: String = "",
        val defects: List<String> = emptyList(),
        val faceRatio: Double? = null,
    )

    /** 审计规则（对齐源 AuditRules；minQualityScore 映射为 0..1 制式以便与模型输出直比）。 */
    data class G2AuditRules(
        /** 通过线：模型 score(0..1)。本地契约为 0.6（G2Result.MIN_G2_SCORE=60/100）；源工程默认 0.7 更严，可按需调严 */
        val minQualityScore: Double = G2Result.MIN_G2_SCORE / 100.0,
        /** 失败重试次数上限（≤3） */
        val maxAttempts: Int = 3,
        /** 质量打分 prompt（带图，{description} 占位） */
        val qualityPrompt: String = DEFAULT_QUALITY_PROMPT,
    ) {
        init {
            require(maxAttempts in 1..3) { "maxAttempts 必须在 1..3（对齐源重试上限）" }
        }
    }

    override suspend fun audit(asset: AssetMeta, imageData: ByteArray?): G2Result {
        if (imageData == null || imageData.isEmpty()) {
            return G2Result(score = 0.0, defects = emptyList()) // 无图无法审计，直接拒
        }
        val imageDataUri = "data:image/png;base64,${base64Encode(imageData)}"
        val prompt = rules.qualityPrompt.replace("{description}", asset.prompt)
        // 熔断预检：发送前估算 token，超安全上限直接拒（对齐源第十轮 ContextWindowExceeded 根因修复）
        if (inputOverloaded(prompt, imageDataUri)) {
            return G2Result(score = 0.0, defects = emptyList())
        }

        var lastRaw = ""
        var lastErr: String? = null
        var channel: ChannelResult? = null
        // 视觉审计附加指令（与 qualityPrompt 配合，对齐源 agnesDescriber）
        val instruction = "请查看这张生成图，严格按后续要求输出质检JSON。\n"
        for (attempt in 1..rules.maxAttempts) {
            try {
                val resp = textProvider.chat(
                    ChatRequest(
                        messages = listOf(
                            ChatMessage(
                                role = "user",
                                content = instruction + prompt,
                                imageUrl = imageDataUri, // OpenAI 视觉格式：Provider 组装 text+image_url
                            ),
                        ),
                        model = model,
                        maxTokens = 1024,
                        temperature = 0.0,
                        enableThinking = false,
                    ),
                )
                lastRaw = resp.content
                channel = parseScore(lastRaw)
                break
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
                // 重试：退避由 Provider/rateGate 层负责，这里仅计数（对齐源）
            }
        }
        if (channel == null) {
            // 多模态调用失败（maxAttempts 次内均异常）→ 拒；lastErr 已在循环中累计
            return G2Result(score = 0.0, defects = emptyList())
        }

        // defects 非空 → 硬惩罚直接拒（不被总分稀释）
        if (channel.defects.isNotEmpty()) {
            return G2Result(score = 0.0, defects = channel.defects)
        }
        // score 换算为本地 0..100 制式（模型 0..1 × 100）
        val score100 = (channel.score * 100.0).coerceIn(0.0, 100.0)
        if (channel.score < rules.minQualityScore) {
            return G2Result(score = score100, defects = emptyList()) // 低于通过线 → 拒
        }
        return G2Result(score = score100, defects = emptyList())
    }

    companion object {
        /** 熔断阈值：发送前估算输入 token 上限（官方 512K，预留输出，对齐源 MAX_INPUT_TOKENS） */
        const val MAX_INPUT_TOKENS = 400_000L

        /** 估算 token：中文≈1字符1token、ASCII≈4字符1token（对齐源 estimateTokens） */
        fun estimateTokens(text: String): Long {
            var cjk = 0L
            for (c in text) if (c.code > 0x2E80) cjk++
            val ascii = text.length - cjk
            return cjk + ascii / 4
        }

        /** true=输入超安全上限，调用方应放弃请求并提示，绝不发 API（对齐源 inputOverloaded） */
        fun inputOverloaded(vararg texts: String): Boolean {
            val total = texts.sumOf { estimateTokens(it) }
            return total > MAX_INPUT_TOKENS
        }

        /** 解析模型 JSON 输出：{score, notes, defects, face_ratio}（对齐源 parseScore）。 */
        internal fun parseScore(text: String): ChannelResult {
            val block = extractJsonObject(text) ?: return ChannelResult(notes = "unparseable: ${text.take(120)}")
            val map = parseJsonToMap(block) ?: return ChannelResult(notes = "unparseable: ${text.take(120)}")
            val score = (map["score"] as? Number)?.toDouble() ?: 0.0
            val notes = when (val n = map["notes"]) {
                is String -> n
                is Map<*, *> -> n["missing_features"]?.toString() ?: ""
                else -> ""
            }
            val defects = when (val d = map["defects"]) {
                is List<*> -> d.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotBlank() } }
                else -> emptyList()
            }
            val fr = (map["face_ratio"] as? Number)?.toDouble()
            return ChannelResult(score = score, notes = notes, defects = defects, faceRatio = fr)
        }

        /** 从文本中提取第一个完整 {...} JSON 对象（容忍模型前后废话，对齐源 extractJsonObject）。 */
        internal fun extractJsonObject(text: String): String? {
            val start = text.indexOf('{')
            if (start < 0) return null
            var depth = 0
            var inStr = false
            var esc = false
            for (i in start until text.length) {
                val c = text[i]
                if (esc) { esc = false; continue }
                when (c) {
                    '\\' -> esc = true
                    '"' -> inStr = !inStr
                    '{' -> if (!inStr) depth++
                    '}' -> if (!inStr) {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
            return null
        }

        /** 极简 JSON 对象解析（仅支持 {k:v,...}，值支持 number/string/boolean/array/嵌套对象；够用即可，避免引第三方，对齐源 parseJsonToMap）。 */
        private fun parseJsonToMap(s: String): Map<String, Any?>? = try {
            val trimmed = s.trim()
            val map = mutableMapOf<String, Any?>()
            val inner = trimmed.removePrefix("{").removeSuffix("}")
            readEntries(inner, map)
            map
        } catch (e: Exception) {
            null
        }

        private fun readEntries(inner: String, map: MutableMap<String, Any?>) {
            var i = 0
            val n = inner.length
            while (i < n) {
                while (i < n && (inner[i].isWhitespace() || inner[i] == ',')) i++
                if (i >= n) break
                if (inner[i] != '"') { // 跳过非字符串 key（容错）
                    i++
                    continue
                }
                val keyEnd = inner.indexOf('"', i + 1); if (keyEnd < 0) break
                val key = inner.substring(i + 1, keyEnd)
                i = keyEnd + 1
                while (i < n && (inner[i].isWhitespace() || inner[i] == ':')) i++
                if (i >= n) break
                val (value, next) = readValue(inner, i)
                map[key] = value
                i = next
            }
        }

        private fun readValue(s: String, start: Int): Pair<Any?, Int> {
            val c = s[start]
            return when {
                c == '"' -> {
                    val end = s.indexOf('"', start + 1); if (end < 0) return null to s.length
                    Pair(s.substring(start + 1, end), end + 1)
                }
                c == '{' -> {
                    val end = matchBrace(s, start)
                    val sub = s.substring(start, end + 1)
                    val m = mutableMapOf<String, Any?>(); readEntries(sub.removePrefix("{").removeSuffix("}"), m)
                    Pair(m, end + 1)
                }
                c == '[' -> {
                    val out = mutableListOf<Any?>()
                    var i = start + 1
                    val n = s.length
                    while (i < n) {
                        while (i < n && (s[i].isWhitespace() || s[i] == ',')) i++
                        if (i >= n || s[i] == ']') { if (i < n) i++; break }
                        val (v, next) = readValue(s, i)
                        out.add(v)
                        i = next
                    }
                    Pair(out, i)
                }
                c == 't' -> Pair(true, start + 4)
                c == 'f' -> Pair(false, start + 5)
                c == 'n' -> Pair(null, start + 4)
                else -> {
                    var i = start
                    while (i < s.length && (s[i] != ',' && s[i] != '}' && s[i] != ']' && !s[i].isWhitespace())) i++
                    val raw = s.substring(start, i)
                    Pair(
                        raw.toDoubleOrNull() ?: raw,
                        i,
                    )
                }
            }
        }

        private fun matchBrace(s: String, open: Int): Int {
            var depth = 0
            var inStr = false
            var esc = false
            for (i in open until s.length) {
                val c = s[i]
                if (esc) { esc = false; continue }
                when (c) {
                    '\\' -> esc = true
                    '"' -> inStr = !inStr
                    '{' -> if (!inStr) depth++
                    '}' -> if (!inStr) { depth--; if (depth == 0) return i }
                }
            }
            return s.length - 1
        }

        /** 极简 base64 编码（无 Android/Java 依赖，JVM 与 Android 通用；仅用于把图像字节转 data URI）。 */
        internal fun base64Encode(data: ByteArray): String {
            val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val sb = StringBuilder((data.size + 2) / 3 * 4)
            var i = 0
            while (i < data.size) {
                val b0 = data[i].toInt() and 0xFF
                val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
                val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else -1
                sb.append(table[b0 ushr 2])
                sb.append(table[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 ushr 4 else 0)])
                sb.append(if (b1 >= 0) table[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 ushr 6 else 0)] else '=')
                sb.append(if (b2 >= 0) table[b2 and 0x3F] else '=')
                i += 3
            }
            return sb.toString()
        }

        val DEFAULT_QUALITY_PROMPT = """
            你是一名严格的短剧资产质检员。请审查这张生成图是否符合资产卡要求。
            资产要求描述：{description}
            只回答一个 JSON 对象，不要任何解释：
            {"score": 0.0-1.0, "notes": "问题简述", "defects": ["杂斑/水印/文字/贴纸等缺陷词，无则空数组"], "face_ratio": 0.0-1.0}
            若画面含杂斑/水斑/文字/水印/贴纸等缺陷，务必列入 defects；人脸占比（头像占画面比例）填入 face_ratio，无人物为0。
        """.trimIndent()
    }
}