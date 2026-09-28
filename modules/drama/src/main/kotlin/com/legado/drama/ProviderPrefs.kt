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

    companion object {
        const val KEY_VIDEO_INTERVAL = "video_interval_ms"
        const val KEY_AGNES_BASE = "agnes_base_url"
        const val KEY_AGNES_REGION = "agnes_region"
        const val KEY_LAST_MODE = "last_mode"
        const val KEY_ACTIVE_TEXT_MODEL = "active_text_model"

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