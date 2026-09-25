package com.legado.drama

import android.content.Context
import com.legado.drama.engine.queue.DefaultRateGate

/**
 * drama 设置项（架构文档 §4.3 / T014 模式记忆）：
 * - 视频提交限速间隔（120s 默认，非法值兜底）
 * - Agnes 直连 base url（冒烟 S1 实测 apihub.agnes-ai.com）
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

    /** T014 Q7：模式记忆全局开关 */
    var lastMode: String
        get() = sp.getString(KEY_LAST_MODE, "manual") ?: "manual"
        set(value) = sp.edit().putString(KEY_LAST_MODE, value).apply()

    companion object {
        const val KEY_VIDEO_INTERVAL = "video_interval_ms"
        const val KEY_AGNES_BASE = "agnes_base_url"
        const val KEY_LAST_MODE = "last_mode"

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