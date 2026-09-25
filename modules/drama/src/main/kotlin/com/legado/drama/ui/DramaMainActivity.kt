package com.legado.drama.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.legado.drama.DramaBridge
import com.legado.drama.ui.theme.DramaTheme

/**
 * AI 短剧工坊入口 Activity（集成进阅读器「我的」页，独立全屏界面）。
 * 启动时挂载 AppGraph（单例）供 Provider/Queue/Orchestrator 共享。
 */
class DramaMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DramaBridge.attach(applicationContext)
        setContent {
            DramaTheme {
                DramaApp()
            }
        }
    }
}