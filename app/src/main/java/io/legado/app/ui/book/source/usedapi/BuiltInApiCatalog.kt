package io.legado.app.ui.book.source.usedapi

/**
 * App 内置 JS API 目录。
 *
 * 「源所用API」界面的完整数据清单，按类别分为三组：
 * - [BINDING_VARIABLES] 内置变量：各 JS 执行入口注入到 bindings 的全局变量
 * - [JAVA_METHODS] 内置函数：`java` 对象（或直接裸调用）可用的方法
 * - [SOURCE_METHODS] 源对象方法：`source` 对象可用的方法
 *
 * ## 同步约定
 *
 * 本清单为静态目录，与下列代码同步维护，新增/改名内置 API 后需一并更新：
 * - 内置变量：`model/analyzeRule/AnalyzeRule.evalJS`、`data/entities/BaseSource.evalJS`、
 *   `model/analyzeRule/AnalyzeUrl.evalJS` 及各执行入口的 `bindings["..."]` / `put("...", ...)`
 * - 内置函数：`help/JsExtensions.kt`（接口 `JsExtensions : JsEncodeUtils`）声明的 public 方法
 * - 源对象方法：`data/entities/BaseSource.kt` 声明的 public 方法（不含继承自 JsExtensions 的）
 */
object BuiltInApiCatalog {

    /**
     * 全局绑定变量（bindings 注入的键名）。
     * 主要来自 AnalyzeRule.evalJS / BaseSource.evalJS / AnalyzeUrl.evalJS 等入口。
     */
    val bindingVariables: List<String> = listOf(
        "author",
        "baseUrl",
        "book",
        "cache",
        "chapter",
        "cookie",
        "epubIndex",
        "fromBookInfo",
        "gInt",
        "index",
        "infoMap",
        "java",
        "key",
        "lastVolumeTitle",
        "name",
        "nextChapterUrl",
        "page",
        "prevLength",
        "prevTitle",
        "result",
        "rssArticle",
        "source",
        "speakSpeed",
        "speakText",
        "src",
        "title"
    )

    /**
     * 内置函数名（`java.xxx()` 或直接 `xxx()` 调用）。
     * 同步自 `help/JsExtensions.kt` 与 `help/JsEncodeUtils.kt` 的全部 public 方法。
     */
    val javaMethods: List<String> = listOf(
        "HMacBase64",
        "HMacHex",
        "aesBase64DecodeToByteArray",
        "aesBase64DecodeToString",
        "aesDecodeArgsBase64Str",
        "aesDecodeToByteArray",
        "aesDecodeToString",
        "aesEncodeArgsBase64Str",
        "aesEncodeToBase64ByteArray",
        "aesEncodeToBase64String",
        "aesEncodeToByteArray",
        "aesEncodeToString",
        "ajax",
        "ajaxAll",
        "ajaxTestAll",
        "androidId",
        "base64Decode",
        "base64DecodeToByteArray",
        "base64Encode",
        "bytesToStr",
        "cacheFile",
        "connect",
        "createAsymmetricCrypto",
        "createSign",
        "createSymmetricCrypto",
        "deleteFile",
        "desBase64DecodeToString",
        "desDecodeToString",
        "desEncodeToBase64String",
        "desEncodeToString",
        "digestBase64Str",
        "digestHex",
        "downloadFile",
        "encodeURI",
        "get",
        "get7zByteArrayContent",
        "get7zStringContent",
        "getCookie",
        "getFile",
        "getRarByteArrayContent",
        "getRarStringContent",
        "getReadBookConfig",
        "getReadBookConfigMap",
        "getSource",
        "getTag",
        "getThemeConfig",
        "getThemeConfigMap",
        "getThemeMode",
        "getTxtInFolder",
        "getVerificationCode",
        "getWebViewUA",
        "getZipByteArrayContent",
        "getZipStringContent",
        "head",
        "hexDecodeToByteArray",
        "hexDecodeToString",
        "hexEncodeToString",
        "htmlFormat",
        "importScript",
        "log",
        "logType",
        "longToast",
        "md5Encode",
        "md5Encode16",
        "openUrl",
        "openVideoPlayer",
        "post",
        "queryBase64TTF",
        "queryTTF",
        "randomUUID",
        "readFile",
        "readTxtFile",
        "replaceFont",
        "s2t",
        "startBrowser",
        "startBrowserAwait",
        "strToBytes",
        "t2s",
        "timeFormat",
        "timeFormatUTC",
        "toNumChapter",
        "toURL",
        "toast",
        "tripleDESDecodeArgsBase64Str",
        "tripleDESDecodeStr",
        "tripleDESEncodeArgsBase64Str",
        "tripleDESEncodeBase64Str",
        "un7zFile",
        "unArchiveFile",
        "unrarFile",
        "unzipFile",
        "webView",
        "webViewGetOverrideUrl",
        "webViewGetSource"
    )

    /**
     * 自定义源对象方法名（`source.xxx` 调用）。
     * 同步自 `data/entities/BaseSource.kt` 声明的 public 方法。
     */
    val sourceMethods: List<String> = listOf(
        "get",
        "getKey",
        "getLoginHeader",
        "getLoginHeaderMap",
        "getLoginInfo",
        "getLoginInfoMap",
        "getLoginJs",
        "getVariable",
        "login",
        "put",
        "putConcurrent",
        "putLoginHeader",
        "putLoginInfo",
        "putVariable",
        "refreshExplore",
        "refreshJSLib",
        "removeLoginHeader",
        "removeLoginInfo",
        "setVariable"
    )
}