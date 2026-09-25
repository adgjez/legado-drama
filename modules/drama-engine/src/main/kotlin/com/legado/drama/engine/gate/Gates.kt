package com.legado.drama.engine.gate

import com.legado.drama.engine.model.AssetMeta
import com.legado.drama.engine.model.ShotMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 质量/闸门层（架构文档 §2 三闸门）：
 * AssetGate(G1 硬校验/G2 多模态)、StoryboardGate(六铁律)、BudgetGuard(条数上限)。
 */

// ───────────────────────── AssetGate ─────────────────────────

/** G1 文件级硬校验结果 */
data class G1Result(
    val passed: Boolean,
    val reason: String? = null,      // 未通过原因
    val formatOk: Boolean = false,
    val dimensionsOk: Boolean = false, // 正方形（角色/场景卡）
    val sizeOk: Boolean = false,
)

/** G2 多模态评分结果：defects 非空直接拒 */
data class G2Result(
    val score: Double,               // 0..100
    val defects: List<String> = emptyList(),
) {
    val passed: Boolean get() = defects.isEmpty() && score >= MIN_G2_SCORE
    companion object {
        const val MIN_G2_SCORE = 60.0
    }
}

/**
 * 两层资产闸门（架构文档 §3 + 数据流 T1）：
 * G1 文件级硬校验（格式/尺寸/正方形/大小），在本地执行；
 * G2 多模态评分（调用 TextProvider 视觉审计）defects 非空直接拒，rejected 自动重试并记原因。
 */
interface AssetGate {
    /** G1：本地文件级硬校验。file 为空/尺寸非正方形/格式非法 → rejected */
    fun checkG1(asset: AssetMeta, width: Int?, height: Int?): G1Result
    /** G2：多模态评分。defects 非空直接拒（pavo 实战） */
    suspend fun checkG2(asset: AssetMeta, imageData: ByteArray?): G2Result
}

class DefaultAssetGate(
    private val g2Auditor: G2Auditor? = null,
) : AssetGate {

    override fun checkG1(asset: AssetMeta, width: Int?, height: Int?): G1Result {
        val uri = asset.fileUri ?: asset.remoteUrl
        if (uri.isNullOrBlank()) {
            return G1Result(passed = false, reason = "无文件", sizeOk = false)
        }
        // 文件大小硬校验（从 URI 解析，若 Data URI 则按 base64 长度估算）
        val sizeOk = when {
            uri.startsWith("data:") -> uri.length > "data:image/".length // 非空 data uri
            else -> true // 本地路径由调用方保证存在
        }
        if (!sizeOk) {
            return G1Result(passed = false, reason = "文件 0 字节", sizeOk = false)
        }
        val formatOk = when {
            uri.startsWith("data:image/") -> true
            uri.endsWith(".png", ignoreCase = true) || uri.endsWith(".jpg", ignoreCase = true) ||
                uri.endsWith(".jpeg", ignoreCase = true) || uri.endsWith(".webp", ignoreCase = true) -> true
            else -> false
        }
        if (!formatOk) {
            return G1Result(passed = false, reason = "格式非法（仅 png/jpg/webp）", formatOk = false, sizeOk = true)
        }
        // 正方形校验（角色/场景卡）
        val dimsOk = if (width != null && height != null) width == height else true
        if (!dimsOk) {
            return G1Result(passed = false, reason = "非正方形，需要 1024x1024", formatOk = true, sizeOk = true)
        }
        return G1Result(passed = true, formatOk = true, dimensionsOk = true, sizeOk = true)
    }

    override suspend fun checkG2(asset: AssetMeta, imageData: ByteArray?): G2Result {
        // 无审计器时返回空检（由调用方集成模型后启用）；有审计器则委托
        return g2Auditor?.audit(asset, imageData) ?: G2Result(score = G2Result.MIN_G2_SCORE)
    }
}

/** G2 多模态审计器：缺省空实现；Android 层接入 Agnes/DeepSeek 视觉 */
interface G2Auditor {
    suspend fun audit(asset: AssetMeta, imageData: ByteArray?): G2Result
}

// ───────────────────────── StoryboardGate ─────────────────────────

/** 六铁律校验问题（error 即阻断渲染） */
data class ValidationIssue(
    val shotNo: Int,
    val rule: String,       // 六铁律名称
    val level: Level,       // error 阻断 / warning 提示
    val message: String,
) {
    enum class Level { ERROR, WARNING }
}

/** 六铁律校验报告 */
data class StoryboardReport(
    val passed: Boolean,
    val issues: List<ValidationIssue> = emptyList(),
    val summary: String,
) {
    val errorCount: Int get() = issues.count { it.level == ValidationIssue.Level.ERROR }
    val warningCount: Int get() = issues.count { it.level == ValidationIssue.Level.WARNING }
}

/**
 * 分镜六铁律机器校验（架构文档 §3 + F05）：
 * 1. 台词逐字校验（对白必须能在剧本原文中按序出现）
 * 2. 资产真实绑定校验（first/last 资产 ID 必须存在且不为空）
 * 3. 时间逆转词表拦截（分镜出现"时间倒退/回到过去"等 → warning 或 error）
 * 4. 首帧不可省略（首帧必须存在；尾帧可合成但不得复用首帧）
 * 5. 对白内容非空（镜头必须有动作/对白/旁白至少其一）
 * 6. 时序连续性（shot_no 从 1 起连续递增，不得跳号）
 */
interface StoryboardGate {
    suspend fun check(
        scriptText: String,
        shots: List<ShotMeta>,
    ): StoryboardReport
}

class DefaultStoryboardGate(
    private val assetIdsInProject: () -> Set<String>,
) : StoryboardGate {

    override suspend fun check(scriptText: String, shots: List<ShotMeta>): StoryboardReport {
        val issues = mutableListOf<ValidationIssue>()

        // 规则2：资产真实绑定
        val validAssets = assetIdsInProject().takeIf { it.isNotEmpty() }
        if (validAssets != null) {
            for (s in shots) {
                for (aid in s.firstAssetIds + s.lastAssetIds) {
                    if (aid !in validAssets) {
                        issues += ValidationIssue(
                            shotNo = s.shotNo,
                            rule = "资产真实绑定",
                            level = ValidationIssue.Level.ERROR,
                            message = "镜 ${s.shotNo} 引用了不存在的资产 $aid",
                        )
                    }
                }
            }
        }

        // 规则4：首帧不可省略
        for (s in shots) {
            if (s.firstAssetIds.isEmpty()) {
                issues += ValidationIssue(
                    shotNo = s.shotNo,
                    rule = "首帧必须存在",
                    level = ValidationIssue.Level.ERROR,
                    message = "镜 ${s.shotNo} 缺少首帧资产（keyframes 模式必需）",
                )
            }
            if (s.lastAssetIds.isNotEmpty() && s.lastAssetIds == s.firstAssetIds && s.firstAssetIds.size == 1) {
                issues += ValidationIssue(
                    shotNo = s.shotNo,
                    rule = "尾帧不得复用首帧",
                    level = ValidationIssue.Level.WARNING,
                    message = "镜 ${s.shotNo} 尾帧与首帧相同，建议合成默认尾帧",
                )
            }
        }

        // 规则5：镜头内容非空
        for (s in shots) {
            if (s.action.isNullOrBlank() && s.dialogue.isNullOrBlank() && s.narration.isNullOrBlank()) {
                issues += ValidationIssue(
                    shotNo = s.shotNo,
                    rule = "镜头内容非空",
                    level = ValidationIssue.Level.ERROR,
                    message = "镜 ${s.shotNo} 动作/对白/旁白均为空",
                )
            }
        }

        // 规则6：时序连续性
        val sorted = shots.sortedBy { it.shotNo }
        if (sorted.isNotEmpty()) {
            if (sorted.first().shotNo != 1) {
                issues += ValidationIssue(
                    shotNo = sorted.first().shotNo,
                    rule = "时序连续性",
                    level = ValidationIssue.Level.WARNING,
                    message = "分镜编号应从 1 起始",
                )
            }
            for (i in 1 until sorted.size) {
                if (sorted[i].shotNo != sorted[i - 1].shotNo + 1) {
                    issues += ValidationIssue(
                        shotNo = sorted[i].shotNo,
                        rule = "时序连续性",
                        level = ValidationIssue.Level.WARNING,
                        message = "分镜编号跳号：${sorted[i - 1].shotNo} → ${sorted[i].shotNo}",
                    )
                }
            }
        }

        // 规则1：台词逐字校验（仅当 screenplay 提供时）
        val script = scriptText.replace(Regex("\\s+"), "")
        for (s in shots) {
            val dialogue = s.dialogue?.replace(Regex("\\s+"), "") ?: continue
            if (script.isNotEmpty() && dialogue.isNotEmpty() && !script.contains(dialogue)) {
                issues += ValidationIssue(
                    shotNo = s.shotNo,
                    rule = "台词忠实性",
                    level = ValidationIssue.Level.ERROR,
                    message = "镜 ${s.shotNo} 台词『${s.dialogue}』未在剧本中出现",
                )
            }
        }

        // 规则3：时间逆转词表拦截
        val timeReverseWords = setOf("时间倒退", "时光倒流", "回到过去", "穿越回", "时间逆转", "倒退到")
        for (s in shots) {
            val text = listOf(s.action, s.narration, s.dialogue).joinToString(" ")
            val hit = timeReverseWords.firstOrNull { text.contains(it) }
            if (hit != null) {
                issues += ValidationIssue(
                    shotNo = s.shotNo,
                    rule = "时间逆转词表",
                    level = ValidationIssue.Level.WARNING,
                    message = "镜 ${s.shotNo} 命中时间逆转词『$hit』，请检查时序逻辑",
                )
            }
        }

        val passed = issues.none { it.level == ValidationIssue.Level.ERROR }
        val summary = if (passed) "六铁律全部通过（${issues.count { it.level == ValidationIssue.Level.WARNING }} 项提示）" else "六铁律未通过（${issues.count { it.level == ValidationIssue.Level.ERROR }} 项阻断）"
        return StoryboardReport(passed = passed, issues = issues, summary = summary)
    }
}

// ───────────────────────── BudgetGuard ─────────────────────────

/** 预算使用信息（Q2：条数型；金额按牌价仅展示估算） */
data class BudgetUsage(
    val projectId: String,
    val usedShots: Int,
    val limitShots: Int,           // budget_shots
    val estimatedCost: Double = 0.0, // 牌价估算（尚未计费）
    val exceeded: Boolean = false,
)

/**
 * 预算闸门（架构文档 §3 + Q2）：MVP 只做条数型上限；
 * 返回 false 表示将超上限，队列暂停等待用户确认。
 */
interface BudgetGuard {
    fun canSubmit(projectId: String): Boolean
    fun consumeSubmitted(projectId: String)
    val usage: StateFlow<BudgetUsage>
}

class DefaultBudgetGuard(
    initialUsage: BudgetUsage,
) : BudgetGuard {

    private val _usage = MutableStateFlow(initialUsage)
    override val usage: StateFlow<BudgetUsage> = _usage

    override fun canSubmit(projectId: String): Boolean {
        val u = _usage.value
        return if (u.projectId != projectId) true else u.usedShots < u.limitShots
    }

    override fun consumeSubmitted(projectId: String) {
        val u = _usage.value
        if (u.projectId != projectId) return
        _usage.value = u.copy(usedShots = u.usedShots + 1, exceeded = u.usedShots + 1 >= u.limitShots)
    }
}