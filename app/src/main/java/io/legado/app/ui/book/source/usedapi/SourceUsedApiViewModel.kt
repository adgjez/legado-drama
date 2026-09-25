package io.legado.app.ui.book.source.usedapi

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.utils.GSON
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「源所用API」界面状态。
 */
sealed interface SourceUsedApiUiState {
    /** 加载中 */
    data object Loading : SourceUsedApiUiState

    /** 加载完成 */
    data class Ready(
        val sourceName: String,
        val categories: List<ApiCategory>
    ) : SourceUsedApiUiState

    /** 书源不存在或加载失败 */
    data object NotFound : SourceUsedApiUiState
}

/**
 * 加载指定书源并扫描其用到的内置 API。
 */
class SourceUsedApiViewModel(application: Application) : BaseViewModel(application) {

    private val _uiState = MutableStateFlow<SourceUsedApiUiState>(SourceUsedApiUiState.Loading)
    val uiState: StateFlow<SourceUsedApiUiState> = _uiState.asStateFlow()

    /** 读取完整书源并静态扫描其用到的内置 API */
    fun load(sourceUrl: String) {
        execute {
            appDb.bookSourceDao.getBookSource(sourceUrl)
        }.onSuccess { source ->
            if (source == null) {
                _uiState.value = SourceUsedApiUiState.NotFound
            } else {
                val categories = ApiUsageScanner.scan(GSON.toJson(source))
                _uiState.value = SourceUsedApiUiState.Ready(source.bookSourceName, categories)
            }
        }.onError {
            _uiState.value = SourceUsedApiUiState.NotFound
        }
    }

    /** 未携带 sourceUrl 参数时直接展示"源不存在" */
    fun onSourceUrlMissing() {
        _uiState.value = SourceUsedApiUiState.NotFound
    }
}