package com.legado.drama.provider

/**
 * Agnes 站点分池（对齐源工程 ai-drama-factory SettingsPage 的 AgnesRegion）：
 * - INTERNATIONAL：国际站（默认，apihub.agnes-ai.com），Key 存 KeyVault 的 "agnes" 条目
 * - CHINA：中国站（api.agnes-ai.cn），Key 独立存 "agnes-cn" 条目
 * 两站 Key 独立、分开保存；切换站点后 provider_configs 与 KeyVault 均按分池 id 读写。
 */
enum class AgnesRegion { INTERNATIONAL, CHINA }

/**
 * 按站点分池 configId（对齐源工程 agnesScopedConfigId 语义）：
 * - INTERNATIONAL：原样返回
 * - CHINA：文本模型 "text-agnes*" → "text-agnes-cn*"；其余 "agnes*" → "agnes-cn*"
 *   （如 "agnes"→"agnes-cn"、"agnes-video"→"agnes-cn-video"）；非 agnes 系原样返回
 * 不做跨池回退：两站 Key 各自配置、各自校验。
 */
fun agnesScopedConfigId(configId: String, region: AgnesRegion): String = when (region) {
    AgnesRegion.INTERNATIONAL -> configId
    AgnesRegion.CHINA -> when {
        configId.startsWith("text-agnes") -> configId.replaceFirst("text-agnes", "text-agnes-cn")
        configId.startsWith("agnes") -> configId.replaceFirst("agnes", "agnes-cn")
        else -> configId
    }
}