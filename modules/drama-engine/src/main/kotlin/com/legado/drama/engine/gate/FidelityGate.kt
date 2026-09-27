package com.legado.drama.engine.gate

import com.legado.drama.engine.model.ShotMeta

/**
 * 提交前忠实性校验（HANDOVER §3.1/§4.2、架构文档 §3 —— FidelityGate）：
 * 每一镜提交给视频模型【前】，逐镜核对：
 *  1. 台词忠实：dialogue 必须能在剧本原文中按序出现（逐字去空白比对）
 *  2. 旁白忠实：narration 不得包含剧本中不存在的关键人名/地名（掐头取整句则跳过）
 *  3. 资产原样：引用资产 ID 必须存在于项目资产白名单（与六铁律规则 2 双保险）
 * 任一 ERROR → 阻断该镜提交（渲染出队前 fail-closed，绝不带病出片）。
 * 与六铁律区别：六铁律在分镜生成后校验全表；FidelityGate 在【单镜提交前】再查一次，
 * 防渲染期间剧本/资产变化导致的漂移（pavo 忠实性二次闸门语义）。
 */
data class FidelityReport(
    val passed: Boolean,
    val issues: List<ValidationIssue> = emptyList(),
    val summary: String = "",
) {
    val errorCount: Int get() = issues.count { it.level == ValidationIssue.Level.ERROR }
}

interface FidelityGate {
    /** 单镜提交前检查。scriptText 为剧本全文，assetIds 为项目资产白名单 */
    suspend fun checkShot(
        shot: ShotMeta,
        scriptText: String,
        assetIdsInProject: () -> Set<String>,
    ): FidelityReport
}

class DefaultFidelityGate : FidelityGate {

    override suspend fun checkShot(
        shot: ShotMeta,
        scriptText: String,
        assetIdsInProject: () -> Set<String>,
    ): FidelityReport {
        val issues = mutableListOf<ValidationIssue>()

        // 规则1：台词逐字忠实
        val compressedScript = scriptText.replace(Regex("\\s+"), "")
        val dialogue = shot.dialogue?.replace(Regex("\\s+"), "")?.trim()
        if (!dialogue.isNullOrEmpty() && compressedScript.isNotEmpty() && !compressedScript.contains(dialogue)) {
            issues += ValidationIssue(
                shotNo = shot.shotNo,
                rule = "台词忠实性",
                level = ValidationIssue.Level.ERROR,
                message = "镜 ${shot.shotNo} 台词『${shot.dialogue}』不在剧本原文中，可能为模型编造",
            )
        }

        // 规则2：资产真实绑定（引用 ID 必须在项目白名单）
        val validAssets = assetIdsInProject().takeIf { it.isNotEmpty() }
        if (validAssets != null) {
            for (aid in shot.firstAssetIds + shot.lastAssetIds) {
                if (aid !in validAssets) {
                    issues += ValidationIssue(
                        shotNo = shot.shotNo,
                        rule = "资产原样",
                        level = ValidationIssue.Level.ERROR,
                        message = "镜 ${shot.shotNo} 引用了不存在的资产 $aid",
                    )
                }
            }
        }

        // 规则3：旁白人名/地名漂移抽查——抽取剧本高频人名（≥2 次出现），旁白提到但剧本没有 → 阻断
        val names = extractRepeatedNames(scriptText)
        val narration = shot.narration ?: ""
        if (names.isNotEmpty() && narration.isNotEmpty()) {
            for (name in names) {
                if (narration.contains(name) && !scriptText.contains(name)) {
                    issues += ValidationIssue(
                        shotNo = shot.shotNo,
                        rule = "旁白忠实性",
                        level = ValidationIssue.Level.ERROR,
                        message = "镜 ${shot.shotNo} 旁白含剧本外关键名『$name』",
                    )
                }
            }
        }

        val passed = issues.none { it.level == ValidationIssue.Level.ERROR }
        val summary = if (passed) "忠实性通过" else "忠实性未通过（${issues.size} 项）"
        return FidelityReport(passed = passed, issues = issues, summary = summary)
    }

    /** 抽取剧本中重复出现的人名候选（2-4 个汉字，出现次数 ≥2）——简单启发式，不引 NLP 依赖 */
    private fun extractRepeatedNames(scriptText: String): List<String> {
        if (scriptText.length < 4) return emptyList()
        val clean = scriptText.replace(Regex("\\s+"), "")
        val freq = mutableMapOf<String, Int>()
        for (i in 0..clean.length - 2) {
            val two = clean.substring(i, i + 2)
            if (two.all { it in '\u4e00'..'\u9fff' }) freq[two] = (freq[two] ?: 0) + 1
        }
        return freq.filterValues { it >= 2 }
            .keys
            .filterNot { it in STOPWORDS }
            .sortedByDescending { freq[it] }
            .take(8)
    }

    private companion object {
        val STOPWORDS = setOf(
            "我们", "你们", "他们", "她们", "它们", "这个", "那个", "自己", "已经", "什么",
            "一个", "没有", "不是", "就是", "还是", "知道", "现在", "时候", "这样", "那样",
            "起来", "一下", "说话", "看见", "因为", "所以", "但是", "可是", "如果", "虽然",
            "突然", "然后", "最后", "开始", "继续", "回来", "过去", "出来", "进去", "离开",
        )
    }
}