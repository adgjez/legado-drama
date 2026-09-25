package com.legado.drama

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 供非 Compose 侧（ForegroundService / 阅读器入口）调用的桥接：
 * 转发到 AppGraph 单例，避免跨组件强依赖。
 */
object DramaBridge {

    /** 渲染开始请求（Service onStartCommand → 队列入队） */
    fun onRenderStartRequested(episodeId: String) {
        val ctx = activeContext ?: return
        AppGraph.get(ctx).let { graph ->
            graph.scope.launch { graph.renderQueue.enqueueEpisode(episodeId) }
        }
    }

    /** 渲染暂停/恢复 */
    fun pauseRender(reason: String = "user") {
        val ctx = activeContext ?: return
        AppGraph.get(ctx).let { graph ->
            graph.scope.launch { graph.renderQueue.pause(reason) }
        }
    }

    fun resumeRender() {
        val ctx = activeContext ?: return
        AppGraph.get(ctx).let { graph ->
            graph.scope.launch { graph.renderQueue.resume(confirmedByUser = true) }
        }
    }

    @Volatile
    private var activeContext: Context? = null

    fun attach(context: Context) {
        activeContext = context.applicationContext
    }
}