package com.legado.drama.engine.gate

/**
 * 时代红线（架构文档 §2——「时代红线」+ HANDOVER F3/P0-FIX F3）：
 * 8 朝代风格预设（StylePreset）：汉/唐/宋/明/清/民国/现代/架空。
 * 每套预设给出图像生成的正面服饰/场景约束与负向禁词：
 *  - eraPositive：加到资产图 prompt（服饰、发饰、场景色调约束）
 *  - eraNegative：负向 prompt（古代剧禁现代物 ANCIENT_NEGATIVE；现代剧禁古装词）
 *  - characterPoses：角色 DNA 6 姿态（供 6pose 行派生，pavo 实战）
 *
 * 使用方：AI 全托管流水线在创建剧集时用 EraDetector.detect() 推断朝代并写
 * 入项目 style_preset；生成图像时用 StylePreset.presetFor(eraKey) 取正向/负向约束。
 * 规则兜底（ruleBased）不依赖 LLM；LLM 优先路径由 AppGraph 按文本模型注入。
 */
data class StylePreset(
    val eraKey: String,          // han/tang/song/ming/qing/republic/modern/any
    val label: String,           // 朝代名
    val eraPositive: String,     // 服饰/发饰/场景/色调正面约束（拼入图像 prompt）
    val eraNegative: String,     // 负面禁词（拼入 negative prompt；语义可直接追加）
    val characterPoses: List<String> = DEFAULT_POSES,
) {
    companion object {
        /** 角色 DNA 6 姿态（HANDOVER §4.2：角色 6pose 包） */
        val DEFAULT_POSES: List<String> = listOf(
            "front_anchor", "side_45", "profile", "back_view", "closeup", "full_body",
        )

        /** 古代剧通用负向词：禁现代化物件穿帮（pavo 实战 ANCIENT_NEGATIVE 语义） */
        const val ANCIENT_NEGATIVE: String =
            "现代服装, 现代建筑, 手机, 汽车, 电线杆, 路灯, 霓虹灯, 塑料制品, 激光, 现代科技产品, 文字水印, 简体字标牌, 卡通, 低分辨率, 模糊"

        /** 现代剧负向词：防古装错穿（HANDOVER F3 验收：现代剧 negative 可为空/禁古装） */
        const val MODERN_NEGATIVE: String =
            "古代服饰, 汉服, 清朝辫子, 铠甲, 长袍马褂, 宫殿, 古建筑, 青铜器, 水墨画风, 卡通, 低分辨率, 模糊"
    }
}

/** 8 朝代预设表（顺序即排序优先级，ruleBased 命中靠前朝代优先） */
object StylePresets {
    val HAN = StylePreset(
        eraKey = "han", label = "汉",
        eraPositive = "汉代服饰：交领右衽深衣、曲裾袍、宽袖、束发冠冕；场景为汉代宫廷/集市/帷帐，暖木色调，古朴质感。",
        eraNegative = StylePreset.ANCIENT_NEGATIVE,
    )
    val TANG = StylePreset(
        eraKey = "tang", label = "唐",
        eraPositive = "唐代服饰：齐胸襦裙、圆领袍、胡服、高髻金钗；长安宫苑/酒肆场景，盛唐华丽色彩，金红主调。",
        eraNegative = StylePreset.ANCIENT_NEGATIVE,
    )
    val SONG = StylePreset(
        eraKey = "song", label = "宋",
        eraPositive = "宋代服饰：褙子、交领襦衫、幞头、简约素雅；市井街巷/园林书斋场景，青灰淡雅色调，宋瓷釉色。",
        eraNegative = StylePreset.ANCIENT_NEGATIVE,
    )
    val MING = StylePreset(
        eraKey = "ming", label = "明",
        eraPositive = "明代服饰：直裰、比甲、凤冠霞帔、乌纱帽；府邸/科举/市集场景，明式家具与红墙黛瓦。",
        eraNegative = StylePreset.ANCIENT_NEGATIVE,
    )
    val QING = StylePreset(
        eraKey = "qing", label = "清",
        eraPositive = "清代服饰：旗装、马褂、褂子、发辫与旗头；四合院/王府/街市场景，青砖灰瓦色调。",
        eraNegative = StylePreset.ANCIENT_NEGATIVE,
    )
    val REPUBLIC = StylePreset(
        eraKey = "republic", label = "民国",
        eraPositive = "民国服饰：中山装、旗袍、学生装、西装礼帽；上海石库门/电车/百乐门场景，怀旧胶片色调。",
        eraNegative = StylePreset.ANCIENT_NEGATIVE,
    )
    val MODERN = StylePreset(
        eraKey = "modern", label = "现代",
        eraPositive = "现代都市服饰：日常便装、西装、校服、休闲卫衣；写字楼/街道/咖啡厅场景，自然光摄影质感。",
        eraNegative = StylePreset.MODERN_NEGATIVE,
    )
    val ANY = StylePreset(
        eraKey = "any", label = "架空",
        eraPositive = "架空奇幻：融合古今元素的原创服饰，不与任何真实朝代绑定；幻想场景，强调电影感与光影。",
        eraNegative = StylePreset.ANCIENT_NEGATIVE,
    )

    val ALL: List<StylePreset> = listOf(HAN, TANG, SONG, MING, QING, REPUBLIC, MODERN, ANY)

    private val byKey: Map<String, StylePreset> = ALL.associateBy { it.eraKey }

    /** 按 eraKey 取预设；未知 key 兜底架空（any，含宽正向约束） */
    fun presetFor(eraKey: String?): StylePreset = byKey[eraKey] ?: ANY
}

/**
 * 时代红线检测（HANDOVER F3 / P0-FIX F3）：
 *  - detect：LLM 优先（注入 textProvider 时走语义断代），规则兜底（ruleBased 关键词表）；
 *             模型无 Key/异常时自动降级规则。返回 StylePreset（含 eraKey）。
 *  - 验收锚点：现代剧 → eraKey="modern"（负向禁古装、正向不含"深衣曲裾"类词）；
 *              西汉/汉代剧 → eraKey="han"（含汉约束与 ANCIENT_NEGATIVE）。
 */
interface EraDetector {
    /** 语义断代（LLM 优先），llmBlock 为空时只走规则兜底 */
    suspend fun detect(
        scriptText: String,
        llmBlock: (suspend (prompt: String) -> String)? = null,
    ): StylePreset
}

class DefaultEraDetector : EraDetector {

    override suspend fun detect(
        scriptText: String,
        llmBlock: (suspend (prompt: String) -> String)?,
    ): StylePreset {
        // LLM 优先：语义识别较关键词更准（P0-FIX F3：LLM 优先、规则兜底）
        if (llmBlock != null) {
            val answer = runCatching {
                llmBlock(ERA_LLM_PROMPT + scriptText.take(4000))
            }.getOrNull()?.trim()?.substringBefore("\n")?.trim()
            if (answer != null) {
                for (p in StylePresets.ALL) {
                    if (answer.contains(p.eraKey, ignoreCase = true) || answer.contains(p.label)) {
                        return p
                    }
                }
            }
            // LLM 无有效回答 → 落到规则兜底，不谎报
        }
        return ruleBased(scriptText)
    }

    /** 规则兜底：朝代关键词命中表（命中多朝代时先到先得，顺序 = 预设表顺序） */
    fun ruleBased(scriptText: String): StylePreset {
        val text = scriptText
        val hit = StylePresets.ALL.firstOrNull { p ->
            if (p.eraKey == "any") return@firstOrNull false
            val keywords = RULE_KEYWORDS[p.eraKey].orEmpty()
            keywords.any { text.contains(it) }
        }
        return hit ?: StylePresets.MODERN
    }

    companion object {
        const val ERA_LLM_PROMPT: String =
            "你是影视历史顾问。请判断下面剧本故事发生的朝代/时代背景，只需输出朝代英文 key（" +
                "han/tang/song/ming/qing/republic/modern/any 之一），不要任何解释。\n剧本内容：\n"

        /** 朝代关键词表（覆盖服装/称谓/器物/场景；"汉"单字太宽，用偏正词） */
        val RULE_KEYWORDS: Map<String, List<String>> = mapOf(
            "han" to listOf("汉朝", "汉代", "西汉", "东汉", "汉宫", "大汉", "楚汉", "曲裾", "深衣", "未央"),
            "tang" to listOf("唐朝", "唐代", "大唐", "贞观", "开元", "长安", "胡人", "齐胸襦裙", "圆领袍", "武周"),
            "song" to listOf("宋朝", "宋代", "大宋", "北宋", "南宋", "汴京", "临安", "包拯", "苏轼", "李清照"),
            "ming" to listOf("明朝", "明代", "大明", "洪武", "永乐", "锦衣卫", "凤冠霞帔", "乌纱帽", "朱元璋"),
            "qing" to listOf("清朝", "清代", "大清", "康熙", "乾隆", "慈禧", "旗装", "辫子", "格格", "阿哥"),
            "republic" to listOf("民国", "军阀", "租界", "抗日", "北伐", "中山装", "旗袍", "上海滩", "百乐门"),
            "modern" to listOf("现代", "都市", "当代", "校园", "办公室", "手机", "地铁", "微信", "直播间", "医院", "出租车", "写字楼"),
        )
    }
}