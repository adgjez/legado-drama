package io.legado.app.ui.book.source.usedapi

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * 内置 API 命中分类。
 */
enum class ApiType {
    /** 内置绑定变量（如 `cookie.get(...)`、`result`） */
    VARIABLE,

    /** 内置函数，`xxx()` 或 `java.xxx()` 调用 */
    FUNCTION,

    /** 源对象方法，`source.xxx()` 调用 */
    SOURCE_METHOD
}

/**
 * 单个内置 API 的一处使用位置。
 *
 * @param tabKey 命中所属 tab（base/search/explore/info/toc/content，对应书源编辑器）
 * @param fieldKey 命中字段名（与编辑器 fieldKey 协议一致）
 * @param snippet 命中点前后的片段文本，过长自动截断并加省略号
 */
data class ApiUsageLocation(
    val tabKey: String,
    val fieldKey: String,
    val snippet: String
)

/**
 * 单个内置 API 条目。
 *
 * @param name 目录名称（规则中的调用名）
 * @param used 该书源是否用到了该 API
 * @param locations 命中位置（used 为 true 时非空）
 */
data class ApiItem(
    val name: String,
    val used: Boolean,
    val locations: List<ApiUsageLocation> = emptyList()
)

/**
 * 一组内置 API 及命中情况，命中的条目排在前。
 *
 * @param type 分类
 * @param items 该分类下的全部条目
 */
data class ApiCategory(
    val type: ApiType,
    val items: List<ApiItem>
) {
    /** 该分类下命中的数量 */
    val usedCount: Int
        get() = items.count { it.used }
}

/**
 * 静态扫描书源规则文本，判定其用到了 [BuiltInApiCatalog] 中的哪些内置 API，
 * 并为命中项记录具体使用位置（所属 tab/字段 + 片段）。
 *
 * 输入为 `Gson.toJson(bookSource)` 序列化后的完整规则 JSON（含 searchUrl、jsLib、
 * loginUrl、全部 rule 子对象及规则里的 `@js:`、`<js>`、`{{...}}` 片段），
 * 一次覆盖源的全部 JS 代码。
 */
object ApiUsageScanner {

    /** 片段预览：命中点左右各截取的最大字符数 */
    private const val SNIPPET_RADIUS = 60

    /**
     * 书源编辑器六个 tab 的字段键白名单（与 `SourceContentSearchDialog.TAB_FIELDS` 一致）。
     * 编辑器字段定位协议（`tabKey` + `fieldKey`）以此为边界。
     */
    private val TAB_FIELDS = mapOf(
        "base" to setOf(
            "bookSourceUrl", "bookSourceName", "bookSourceGroup", "bookSourceComment",
            "loginUrl", "loginUi", "loginCheckJs", "coverDecodeJs", "bookUrlPattern",
            "header", "variableComment", "concurrentRate", "jsLib"
        ),
        "search" to setOf(
            "searchUrl", "checkKeyWord", "bookList", "name", "author", "kind",
            "wordCount", "lastChapter", "intro", "coverUrl", "bookUrl"
        ),
        "explore" to setOf(
            "exploreUrl", "bookList", "name", "author", "kind", "wordCount",
            "lastChapter", "intro", "coverUrl", "bookUrl"
        ),
        "info" to setOf(
            "init", "name", "author", "kind", "wordCount", "lastChapter", "intro",
            "coverUrl", "tocUrl", "canReName", "downloadUrls"
        ),
        "toc" to setOf(
            "preUpdateJs", "preCheckJs", "chapterList", "chapterName", "formatJs",
            "isVolume", "updateTime", "isVip", "isPay", "nextTocUrl"
        ),
        "content" to setOf(
            "content", "nextContentUrl", "subContent", "replaceRegex", "ChapterName",
            "sourceRegex", "imageStyle", "imageDecode", "webJs", "payAction", "callBackJs"
        )
    )

    /** JSON 中规则子对象 key → 编辑器 tabKey */
    private val RULE_OBJECT_TO_TAB = mapOf(
        "ruleSearch" to "search",
        "ruleExplore" to "explore",
        "ruleBookInfo" to "info",
        "ruleToc" to "toc",
        "ruleContent" to "content"
    )

    /**
     * 扫描书源规则文本，返回三个分类的完整目录、命中标记与命中位置。
     *
     * 匹配示例：
     * - 变量命中：`cookie.get(...)`、`result`（变量名后需紧跟 `.`/`[`/`(`/运算符等续符）
     * - 函数命中：`ajax(url)` 与 `java.ajax(url)` 两种调用形式都命中
     * - 源对象方法命中：`source.getVariable("x")`
     *
     * 注：纯文本扫描对规则字段名/CSS 选择器中的同名词存在少量误报，
     * 靠「变量名后随续符」「函数名后随 `(`」「`source` 前缀」收窄；
     * 字段名如 `bookSourceUrl`、`chapterName` 因其后随词字符不会误报。
     */
    fun scan(ruleJsonText: String): List<ApiCategory> {
        val fieldTexts = collectFieldTexts(ruleJsonText)
        val locationsByKey = collectLocations(fieldTexts)
        return listOf(
            ApiCategory(
                ApiType.VARIABLE,
                matchItems(BuiltInApiCatalog.bindingVariables, ApiType.VARIABLE, locationsByKey)
            ),
            ApiCategory(
                ApiType.FUNCTION,
                matchItems(BuiltInApiCatalog.javaMethods, ApiType.FUNCTION, locationsByKey)
            ),
            ApiCategory(
                ApiType.SOURCE_METHOD,
                matchItems(BuiltInApiCatalog.sourceMethods, ApiType.SOURCE_METHOD, locationsByKey)
            )
        )
    }

    private fun matchItems(
        names: List<String>,
        type: ApiType,
        locationsByKey: Map<String, List<ApiUsageLocation>>
    ): List<ApiItem> = names
        .map { name ->
            val locations = locationsByKey[locationKey(type, name)].orEmpty()
            ApiItem(name = name, used = locations.isNotEmpty(), locations = locations)
        }
        .sortedByDescending { it.used }

    /**
     * 将书源 JSON 拍平为白名单内的字段文本列表（tabKey, fieldKey, 值文本）。
     * 顶层字段归 base；rule 子对象按 [RULE_OBJECT_TO_TAB] 归位；
     * 字符串数组（如 downloadUrls）拆元素逐个收集，非字符串值忽略。
     */
    private fun collectFieldTexts(ruleJsonText: String): List<FieldText> {
        val result = mutableListOf<FieldText>()
        val root = try {
            JsonParser.parseString(ruleJsonText).asJsonObject
        } catch (e: Exception) {
            return result
        }
        for (fieldKey in TAB_FIELDS.getValue("base")) {
            collectFieldText(root.get(fieldKey), "base", fieldKey, result)
        }
        // 搜索/发现的 URL 模板在顶层，不在 rule 子对象内
        collectFieldText(root.get("searchUrl"), "search", "searchUrl", result)
        collectFieldText(root.get("exploreUrl"), "explore", "exploreUrl", result)
        for ((objKey, tabKey) in RULE_OBJECT_TO_TAB) {
            val obj = root.get(objKey)?.asJsonObject ?: continue
            for (fieldKey in TAB_FIELDS.getValue(tabKey)) {
                collectFieldText(obj.get(fieldKey), tabKey, fieldKey, result)
            }
        }
        return result
    }

    private fun collectFieldText(
        element: JsonElement?,
        tabKey: String,
        fieldKey: String,
        out: MutableList<FieldText>
    ) {
        when (element) {
            is JsonPrimitive -> {
                if (element.isString) {
                    out += FieldText(tabKey, fieldKey, element.asString)
                }
            }
            is JsonArray -> for (item in element) {
                if (item.isJsonPrimitive && item.asJsonPrimitive.isString) {
                    out += FieldText(tabKey, fieldKey, item.asString)
                }
            }
            else -> Unit
        }
    }

    /**
     * 对每个字段文本跑三类正则的 findAll，按「分类 + API 名」聚合所有命中位置。
     */
    private fun collectLocations(fieldTexts: List<FieldText>): Map<String, List<ApiUsageLocation>> {
        val result = HashMap<String, MutableList<ApiUsageLocation>>()
        for (field in fieldTexts) {
            for (type in ApiType.entries) {
                for (name in namesOf(type)) {
                    val regex = regexOf(type, name)
                    if (!regex.containsMatchIn(field.text)) continue
                    regex.findAll(field.text).forEach { match ->
                        result.getOrPut(locationKey(type, name)) { mutableListOf() }
                            .add(
                                ApiUsageLocation(
                                    tabKey = field.tabKey,
                                    fieldKey = field.fieldKey,
                                    snippet = makeSnippet(field.text, match.range)
                                )
                            )
                    }
                }
            }
        }
        return result
    }

    private fun namesOf(type: ApiType): List<String> = when (type) {
        ApiType.VARIABLE -> BuiltInApiCatalog.bindingVariables
        ApiType.FUNCTION -> BuiltInApiCatalog.javaMethods
        ApiType.SOURCE_METHOD -> BuiltInApiCatalog.sourceMethods
    }

    /**
     * 片段预览：以命中点为中心截取前后各 [SNIPPET_RADIUS] 字符，越界处加省略号。
     */
    private fun makeSnippet(text: String, match: IntRange): String {
        val start = (match.first - SNIPPET_RADIUS).coerceAtLeast(0)
        val end = (match.last + 1 + SNIPPET_RADIUS).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end) + suffix
    }

    /**
     * 绑定变量正则：变量名前不能是词字符/`.`/`$`，后随 `.`、`[`、`(`、空白或常见 JS 运算符。
     */
    private fun variableRegex(name: String): Regex =
        Regex("(?<![.\\w$])${Regex.escape(name)}(?=[.\\[(\\s=+\\-*/%&|!,?;<>}\\])])")

    /**
     * 函数正则：方法名前不能是词字符/`$`，后随 `(`（`java.ajax(` 中 `.` 前亦命中）。
     */
    private fun functionRegex(name: String): Regex =
        Regex("(?<![\\w$])${Regex.escape(name)}\\s*\\(")

    /**
     * 源对象方法正则：`source` 后随 `.` 与方法名与 `(`。
     */
    private fun sourceMethodRegex(name: String): Regex =
        Regex("(?<![\\w$])source\\s*\\.\\s*${Regex.escape(name)}\\s*\\(")

    private fun regexOf(type: ApiType, name: String): Regex = when (type) {
        ApiType.VARIABLE -> variableRegex(name)
        ApiType.FUNCTION -> functionRegex(name)
        ApiType.SOURCE_METHOD -> sourceMethodRegex(name)
    }

    private fun locationKey(type: ApiType, name: String) = "${type.name}:$name"

    /** 单字段文本及其归属 */
    private data class FieldText(
        val tabKey: String,
        val fieldKey: String,
        val text: String
    )
}