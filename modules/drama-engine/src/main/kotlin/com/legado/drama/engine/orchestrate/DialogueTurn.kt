package com.legado.drama.engine.orchestrate

/** 一轮对话（AI 回复 / 用户发言） */
data class DialogueTurn(
    val side: Side,
    val content: String,
    val at: Long = 0L,
) {
    enum class Side { AI, USER }
}