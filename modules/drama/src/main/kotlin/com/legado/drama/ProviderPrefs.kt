package com.legado.drama

import android.content.Context
import com.legado.drama.engine.queue.DefaultRateGate
import com.legado.drama.engine.router.DeepSeekDefaults
import com.legado.drama.provider.AgnesRegion

/**
 * drama 设置项（架构文档 §4.3 / T014 模式记忆）：
 * - 视频提交限速间隔（120s 默认，非法值兜底）
 * - Agnes 直连 base url（冒烟 S1 实测 apihub.agnes-ai.com）
 * - Agnes 站点分池（INTERNATIONAL / CHINA，对齐源工程 SettingsPage）
 * - 首页模式记忆（T014 Q7 全局开关：ai / manual）
 */
class ProviderPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("drama_settings", Context.MODE_PRIVATE)

    var videoIntervalMs: Long
        get() = DefaultRateGate.parseInterval(sp.getString(KEY_VIDEO_INTERVAL, null) ?: "")
        set(value) = sp.edit().putString(KEY_VIDEO_INTERVAL, value.toString()).apply()

    var agnesBaseUrl: String
        get() = sp.getString(KEY_AGNES_BASE, DEFAULT_AGNES_BASE) ?: DEFAULT_AGNES_BASE
        set(value) = sp.edit().putString(KEY_AGNES_BASE, value).apply()

    /** Agnes 站点分池（默认国际站；非法值兜底） */
    var agnesRegion: AgnesRegion
        get() = runCatching {
            AgnesRegion.valueOf(sp.getString(KEY_AGNES_REGION, null) ?: AgnesRegion.INTERNATIONAL.name)
        }.getOrDefault(AgnesRegion.INTERNATIONAL)
        set(value) = sp.edit().putString(KEY_AGNES_REGION, value.name).apply()

    /** T014 Q7：模式记忆全局开关 */
    var lastMode: String
        get() = sp.getString(KEY_LAST_MODE, "manual") ?: "manual"
        set(value) = sp.edit().putString(KEY_LAST_MODE, value).apply()

    /** T014 Q4：当前生效文本模型 id（默认 deepseek-chat，随时互切） */
    var activeTextModelId: String
        get() = sp.getString(KEY_ACTIVE_TEXT_MODEL, DeepSeekDefaults.MODEL) ?: DeepSeekDefaults.MODEL
        set(value) = sp.edit().putString(KEY_ACTIVE_TEXT_MODEL, value).apply()

    /** P0-③：当前激活的视频供应商 id（默认 agnes，随时互切；VideoProviderRouter 持久化挂载点） */
    var activeVideoProviderId: String
        get() = sp.getString(KEY_ACTIVE_VIDEO_PROVIDER, "agnes") ?: "agnes"
        set(value) = sp.edit().putString(KEY_ACTIVE_VIDEO_PROVIDER, value).apply()

    /** set_cross_era 动作：跨时代器物豁免列表（逗号分隔，如 "手机,眼镜,手表"）。持久化到设置，图像负向词据此剔除。 */
    var crossEraAllowed: String
        get() = sp.getString(KEY_CROSS_ERA_ALLOWED, "") ?: ""
        set(value) = sp.edit().putString(KEY_CROSS_ERA_ALLOWED, value).apply()

    /** 从负向词串中剔除已豁免的跨时代器物（set_cross_era 真实生效点：生成/审计负向 prompt 传入前调用） */
    fun crossEraNegative(base: String): String {
        val allowed = crossEraAllowed.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (allowed.isEmpty()) return base
        return base.split(",").map { it.trim() }.filter { it.isNotBlank() && it !in allowed }.joinToString(", ")
    }

    companion object {
        const val KEY_VIDEO_INTERVAL = "video_interval_ms"
        const val KEY_AGNES_BASE = "agnes_base_url"
        const val KEY_AGNES_REGION = "agnes_region"
        const val KEY_LAST_MODE = "last_mode"
        const val KEY_ACTIVE_TEXT_MODEL = "active_text_model"
        const val KEY_ACTIVE_VIDEO_PROVIDER = "active_video_provider"
        const val KEY_CROSS_ERA_ALLOWED = "cross_era_allowed"

        /** Agnes 直连基址（S1 实测通过；决策 Q1 解除代理层） */
        const val DEFAULT_AGNES_BASE = "https://apihub.agnes-ai.com"

        const val MODE_AI = "ai"
        const val MODE_MANUAL = "manual"
    }
}

/** 中文配音指令（决定 Q9：台词前置主导 + 显式中文指令，拼入 prompt 开头） */
object ChineseDubPrompt {
    const val HEADER =
        "中文普通话配音，语速自然，口齿清晰；镜头语言以角色表演和场景氛围为主"

    fun attach(prompt: String): String = "$HEADER。$prompt"
}