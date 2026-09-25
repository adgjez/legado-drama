package com.legado.drama.engine.security

/**
 * Key 安全存储（架构文档 §3 + §6）：
 * Android Keystore + EncryptedSharedPreferences（AES256-GCM，StrongBox 可用则用）。
 * UI 永不回显明文；日志脱敏（前3后3掩码）。
 */
interface KeyVault {
    suspend fun save(configId: String, providerId: String, plainKey: String)
    suspend fun load(configId: String): String            // 仅 Provider 层可见，永不回显 UI
    fun masked(configId: String): String                  // sk-***abc 展示
    suspend fun delete(configId: String)
}

/** 掩码纯逻辑：sk-1234567890abc → sk-123***abc（前3后3），短 Key 全掩 */
object KeyMasker {
    fun mask(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        if (raw.length <= 6) return "*".repeat(raw.length)
        return raw.take(3) + "***" + raw.takeLast(3)
    }
}