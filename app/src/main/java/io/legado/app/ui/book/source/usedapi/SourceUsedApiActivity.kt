package io.legado.app.ui.book.source.usedapi

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.base.BaseComposeActivity

/**
 * 「源所用API」界面：展示一个书源用到的全部 App 内置 API。
 *
 * 通过 [SourceUsedApiActivity] 的 `sourceUrl` 参数加载指定书源，
 * 扫描结果在 [SourceUsedApiScreen] 中按分类分组展示。
 */
class SourceUsedApiActivity : BaseComposeActivity() {

    @Composable
    override fun ComposeContent() {
        val viewModel: SourceUsedApiViewModel = viewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) {
            val sourceUrl = intent.getStringExtra("sourceUrl")
            if (sourceUrl == null) {
                viewModel.onSourceUrlMissing()
            } else {
                viewModel.load(sourceUrl)
            }
        }
        SourceUsedApiScreen(
            uiState = uiState,
            sourceUrl = intent.getStringExtra("sourceUrl").orEmpty(),
            onBackClick = { finish() }
        )
    }
}