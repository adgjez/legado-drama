package com.legado.drama.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.legado.drama.AppGraph
import com.legado.drama.DramaBridge
import com.legado.drama.ui.theme.DramaTheme
import kotlinx.coroutines.launch

/**
 * AI 短剧工坊入口 Activity（集成进阅读器「我的」页，独立全屏界面）。
 * 启动时挂载 AppGraph（单例）供 Provider/Queue/Orchestrator 共享，
 * 并触发进程重启断点续传（架构文档 §7.3 recoverOnBoot：SUBMITTED 镜零重复付费重轮询）。
 */
class DramaMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DramaBridge.attach(applicationContext)
        val graph = AppGraph.get(this)
        graph.scope.launch { graph.pipelineOrchestrator.recoverOnBoot() }
        setContent {
            DramaTheme {
                DramaApp()
            }
        }
    }
}