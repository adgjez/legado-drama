package com.legado.drama.engine.orchestrator

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * LLM 结构化输出的容错解析 + 资产名→ID 绑定纯逻辑（JVM 可单测）。
 * AI 全托管流水线把文本通道返回的 JSON 解析为资产清单 / 分镜草稿，
 * 并把分镜中引用的角色名/场景名解析为本项目已落库的资产 ID（T014-arch §2.2 内部契约 3）。
 *
 * 约定（由调用方 prompt 保证，见 AiOrchestrator 实现）：
 *  - 资产 prompt 前缀：角色卡:{name}。 / 场景卡:{name}。 / 道具卡:{name}。
 *  - 提取 JSON：{"characters":[{name,desc}],"scenes":[...],"props":[...]}
 *  - 分镜 JSON：[{dialogue,narration,action,characters:[名],scene:名}]
 * 解析容忍 markdown 围栏与前后杂文本（LLM 常见行为）。
 */
object AiJsonParser {

    const val PREFIX_CHARACTER = "角色卡:"
    const val PREFIX_SCENE = "场景卡:"
    const val PREFIX_PROP = "道具卡:"

    private val json = Json { ignoreUnknownKeys = true }

    // ── 资产提取 ──

    @Serializable
    private data class AssetsDto(
        val characters: List<ItemDto> = emptyList(),
        val scenes: List<ItemDto> = emptyList(),
        val props: List<ItemDto> = emptyList(),
    )

    @Serializable
    private data class ItemDto(val name: String = "", val desc: String = "")

    /** 从 LLM 响应中抠出首个 JSON 块（容忍 ```json 围栏与前后杂文本）。找不到返回 null。
     *  数组（分镜 [..]）与对象（资产提取 {..}）均支持：按先出现的结构符判定，避免分镜数组被误截为对象。 */
    fun extractJsonBlock(text: String): String? {
        val t = text.trim()
        val objStart = t.indexOf('{')
        val arrStart = t.indexOf('[')
        val arrFirst = arrStart >= 0 && (objStart < 0 || arrStart < objStart)
        if (arrFirst) {
            val end = t.lastIndexOf(']')
            return if (end > arrStart) t.substring(arrStart, end + 1) else null
        }
        if (objStart >= 0) {
            val end = t.lastIndexOf('}')
            return if (end > objStart) t.substring(objStart, end + 1) else null
        }
        return null
    }

    /** 解析资产提取结果：角色/场景/道具各自 name+desc。解析失败返回空表（由编排器按失败处理）。 */
    fun parseAssets(text: String): List<ExtractedAssetSpec> {
        val block = extractJsonBlock(text) ?: return emptyList()
        return runCatching {
            val dto = json.decodeFromString<AssetsDto>(block)
            buildList {
                dto.characters.forEach { if (it.name.isNotBlank()) add(ExtractedAssetSpec(it.name.trim(), it.desc.trim(), "character")) }
                dto.scenes.forEach { if (it.name.isNotBlank()) add(ExtractedAssetSpec(it.name.trim(), it.desc.trim(), "scene")) }
                dto.props.forEach { if (it.name.isNotBlank()) add(ExtractedAssetSpec(it.name.trim(), it.desc.trim(), "prop")) }
            }
        }.getOrDefault(emptyList())
    }

    // ── 分镜 ──

    @Serializable
    private data class ShotDto(
        val dialogue: String? = null,
        val narration: String? = null,
        val action: String? = null,
        val characters: List<String> = emptyList(),
        val scene: String? = null,
    )

    /** 解析分镜草稿数组。解析失败返回空表。 */
    fun parseStoryboard(text: String): List<StoryboardShotDraft> {
        val block = extractJsonBlock(text) ?: return emptyList()
        return runCatching {
            val dto = json.decodeFromString<List<ShotDto>>(block)
            dto.mapIndexedNotNull { idx, s ->
                val hasContent = !s.dialogue.isNullOrBlank() || !s.narration.isNullOrBlank() || !s.action.isNullOrBlank()
                if (!hasContent) null
                else StoryboardShotDraft(
                    no = idx + 1,
                    dialogue = s.dialogue?.trim()?.takeIf { it.isNotBlank() },
                    narration = s.narration?.trim()?.takeIf { it.isNotBlank() },
                    action = s.action?.trim()?.takeIf { it.isNotBlank() },
                    characters = s.characters.map { it.trim() }.filter { it.isNotBlank() },
                    scene = s.scene?.trim()?.takeIf { it.isNotBlank() },
                )
            }
        }.getOrDefault(emptyList())
    }

    // ── 资产名匹配（分镜引用 → 本集资产 ID）──

    /**
     * 把分镜草稿中的角色/场景名解析为资产 ID：
     *  - 首帧：首个命中角色卡资产；无角色则用场景卡
     *  - 尾帧：场景卡优先；无场景则取次位角色卡（首帧不用同一张卡 → 六铁律"尾帧不得复用首帧"）
     *  - unresolvedNames：引用了但资产库缺失的名称（供编排器事件提示）
     */
    fun assignShotAssets(
        drafts: List<StoryboardShotDraft>,
        assetsByName: Map<String, String>,
    ): List<AssignedShot> {
        return drafts.map { d ->
            val charIds = d.characters.mapNotNull { name ->
                matchAsset(name, assetsByName)
            }
            val sceneId = d.scene?.let { matchAsset(it, assetsByName) }
            val missing = buildList {
                d.characters.forEach { if (matchAsset(it, assetsByName) == null) add(it) }
                d.scene?.let { if (matchAsset(it, assetsByName) == null) add(it) }
            }.distinct()

            val first = if (charIds.isNotEmpty()) listOf(charIds.first()) else sceneId?.let { listOf(it) } ?: emptyList()
            val last = when {
                sceneId != null && sceneId !in first -> listOf(sceneId)
                charIds.size > 1 -> listOf(charIds.last())
                charIds.isNotEmpty() && charIds.first() !in first -> listOf(charIds.first())
                else -> emptyList() // 六铁律：首帧不可省略在渲染队列复核时拦截
            }
            AssignedShot(d, first, last, missing)
        }
    }

    /** 精确名优先，再宽松包含匹配（LLM 输出角色名与提取名偶有偏差时兜底） */
    private fun matchAsset(name: String, assetsByName: Map<String, String>): String? {
        assetsByName[name]?.let { return it }
        val hit = assetsByName.entries.firstOrNull { (k, _) ->
            k.isNotEmpty() && (k.contains(name) || name.contains(k))
        }
        return hit?.value
    }

    /** 从资产 prompt 前缀解析资产名（"角色卡:林小满。xxx" → "林小满"） */
    fun assetNameFromPrompt(prompt: String): String? {
        val trimmed = prompt.trim()
        val prefix = listOf(PREFIX_CHARACTER, PREFIX_SCENE, PREFIX_PROP).firstOrNull { trimmed.startsWith(it) }
            ?: return null
        val rest = trimmed.removePrefix(prefix).trim()
        val name = rest.substringBefore('。').substringBefore('.').trim()
        return name.takeIf { it.isNotBlank() }
    }

    /** 资产 prompt 前缀（按 kind 生成，供编排器落库 asset.prompt 用） */
    fun prefixFor(kind: String): String = when (kind) {
        "character" -> PREFIX_CHARACTER
        "scene" -> PREFIX_SCENE
        "prop" -> PREFIX_PROP
        else -> ""
    }
}

/** 文本通道提取出的单一资产规格 */
data class ExtractedAssetSpec(
    val name: String,
    val desc: String,
    val kind: String, // character/scene/prop
)

/** 文本通道输出的分镜草稿（LLM 原始字段，未绑定资产 ID） */
data class StoryboardShotDraft(
    val no: Int,
    val dialogue: String? = null,
    val narration: String? = null,
    val action: String? = null,
    val characters: List<String> = emptyList(),
    val scene: String? = null,
)

/** 分镜草稿 + 绑定的首尾帧资产 ID */
data class AssignedShot(
    val shot: StoryboardShotDraft,
    val firstAssetIds: List<String>,
    val lastAssetIds: List<String>,
    val unresolvedNames: List<String>,
)