package com.legado.drama.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush.Companion.verticalGradient
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.legado.drama.AppGraph
import com.legado.drama.DramaBridge
import com.legado.drama.ui.theme.DramaNeon
import com.legado.drama.ui.theme.DramaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * AI 短剧工坊入口 Activity（集成进阅读器「我的」页，独立全屏界面）。
 * 启动时挂载 AppGraph（单例）供 Provider/Queue/Orchestrator 共享，
 * 并触发进程重启断点续传（架构文档 §7.3 recoverOnBoot：SUBMITTED 镜零重复付费重轮询）。
 * 开屏动画（HANDOVER C3）：2.6s 渐变 0xFF1A1030 → 0xFF0D0A1A + 三行文案（UI-BEAUTIFY §0）。
 */
class DramaMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DramaBridge.attach(applicationContext)
        val graph = AppGraph.get(this)
        graph.scope.launch { graph.pipelineOrchestrator.recoverOnBoot() }
        setContent {
            DramaTheme {
                SplashGate {
                    DramaApp()
                }
            }
        }
    }
}

/** 开屏动画门：2.6s 暗紫渐变 + 渐入文案后淡出进入主界面 */
@Composable
private fun SplashGate(content: @Composable () -> Unit) {
    var splashDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(2600)
        splashDone = true
    }
    Crossfade(targetState = splashDone, animationSpec = tween(600), label = "splash") { done ->
        if (done) {
            content()
        } else {
            SplashContent()
        }
    }
}

/** 开屏内容：0xFF1A1030 → 0xFF0D0A1A 垂直渐变 + 三行文案（UI-BEAUTIFY §0） */
@Composable
private fun SplashContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                verticalGradient(listOf(DramaNeon.SplashTop, DramaNeon.SplashBottom)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text(
                text = "AI 短剧工厂",
                style = MaterialTheme.typography.displaySmall,
                color = DramaNeon.SplashText,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "开源你的梦境",
                style = MaterialTheme.typography.headlineSmall,
                color = DramaNeon.SplashText.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
            Text(
                text = "剧本 · 资产 · 分镜 · 渲染 · 成片",
                style = MaterialTheme.typography.bodyMedium,
                color = DramaNeon.SplashText.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
    }
}