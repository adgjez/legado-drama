package io.legado.app.ui.book.source.usedapi

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.legado.app.R
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.theme.pageAccentColor
import io.legado.app.ui.theme.pageSecondaryTextColor
import io.legado.app.ui.widget.components.AppPageTopBar
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.navigationBarBottomInset
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi

/**
 * 「源所用API」主界面。
 *
 * 展示一个书源用到的内置 API：
 * - 顶部 Tab 切换「已使用 / 未使用」，Tab 标签带数量统计
 * - 列表按分类分组，保留分类标题；命中的条目带对勾与强调色
 * - 顶栏开启复制模式后，点击条目复制 API 名称
 *
 * @param uiState 界面状态
 * @param onBackClick 返回按钮回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceUsedApiScreen(
    uiState: SourceUsedApiUiState,
    sourceUrl: String,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val accentColor = pageAccentColor()
    val secondaryTextColor = pageSecondaryTextColor()
    var searchKey by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    // 复制模式：开启后点击列表条目才复制名称，图标高亮提示
    var copyMode by rememberSaveable { mutableStateOf(false) }
    val onCopyName: (String) -> Unit = { name ->
        context.sendToClip(name)
        context.toastOnUi(context.getString(R.string.api_copied, name))
    }

    AppScaffold(
        topBar = {
            AppPageTopBar(
                title = stringResource(R.string.source_used_api),
                subtitle = (uiState as? SourceUsedApiUiState.Ready)?.sourceName,
                onBackClick = onBackClick,
                actions = {
                    IconButton(onClick = {
                        copyMode = !copyMode
                        context.toastOnUi(
                            if (copyMode) {
                                R.string.api_copy_mode_enabled
                            } else {
                                R.string.api_copy_mode_disabled
                            }
                        )
                    }) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.copy),
                            tint = if (copyMode) {
                                accentColor
                            } else {
                                LocalContentColor.current
                            }
                        )
                    }
                    IconButton(onClick = {
                        if (searchVisible || searchKey.isNotEmpty()) {
                            searchKey = ""
                            searchVisible = false
                        } else {
                            searchVisible = true
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(R.string.action_search)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        when (val state = uiState) {
            is SourceUsedApiUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is SourceUsedApiUiState.NotFound -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.api_source_not_found),
                        color = secondaryTextColor
                    )
                }
            }

            is SourceUsedApiUiState.Ready -> {
                ApiCatalogContent(
                    categories = state.categories,
                    sourceUrl = sourceUrl,
                    accentColor = accentColor,
                    secondaryTextColor = secondaryTextColor,
                    onCopyName = onCopyName,
                    searchKey = searchKey,
                    onSearchKeyChange = { searchKey = it },
                    showSearchField = searchVisible || searchKey.isNotEmpty(),
                    copyModeEnabled = copyMode,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                )
            }
        }
    }
}

/**
 * Tab 内容区：已使用/未使用切换，下方按分类展示（分类头可折叠）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiCatalogContent(
    categories: List<ApiCategory>,
    sourceUrl: String,
    accentColor: Color,
    secondaryTextColor: Color,
    onCopyName: (String) -> Unit,
    searchKey: String,
    onSearchKeyChange: (String) -> Unit,
    showSearchField: Boolean,
    copyModeEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showingUsed by rememberSaveable { mutableStateOf(true) }
    // 正在查看使用位置的条目（非复制模式点击后弹出）
    var usageItem by remember { mutableStateOf<ApiItem?>(null) }
    // 折叠的分类（两个 Tab 共用，切换 Tab 不会重置）
    var collapsedTypes by remember { mutableStateOf(emptySet<ApiType>()) }
    val totalCount = categories.sumOf { it.items.size }
    val usedCount = categories.sumOf { it.usedCount }
    val searchKeyTrim = searchKey.trim()

    // 当前 Tab 与搜索词下的分类（保留分类头，空分类跳过）
    val visibleCategories = categories.mapNotNull { category ->
        category.items.filter {
            it.used == showingUsed &&
                    (searchKeyTrim.isEmpty() || it.name.contains(searchKeyTrim, ignoreCase = true))
        }
            .takeIf { it.isNotEmpty() }
            ?.let { category.copy(items = it) }
    }

    Column(modifier = modifier) {
        SecondaryTabRow(selectedTabIndex = if (showingUsed) 0 else 1) {
            Tab(
                selected = showingUsed,
                onClick = { showingUsed = true },
                text = {
                    Text("${stringResource(R.string.api_used)} ($usedCount)")
                }
            )
            Tab(
                selected = !showingUsed,
                onClick = { showingUsed = false },
                text = {
                    Text("${stringResource(R.string.api_not_used)} (${totalCount - usedCount})")
                }
            )
        }

        // 搜索框固定在 Tab 下方，不随列表滚动，点击搜索图标即可见
        if (showSearchField) {
            SourceUsedApiSearchField(
                query = searchKey,
                onQueryChange = onSearchKeyChange,
                accentColor = accentColor
            )
            Spacer(Modifier.height(4.dp))
        }

        val listState = rememberLazyListState()
        // Tab 切换后回到列表顶部，避免在残留在旧列表深处的滚动位置
        LaunchedEffect(showingUsed) {
            listState.scrollToItem(0)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            contentPadding = PaddingValues(bottom = navigationBarBottomInset)
        ) {
            if (visibleCategories.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.api_empty_list),
                            color = secondaryTextColor
                        )
                    }
                }
            } else {
                items(visibleCategories, key = { it.type.name }) { category ->
                    val collapsed = category.type in collapsedTypes
                    CategoryHeader(
                        category = category,
                        collapsed = collapsed,
                        accentColor = accentColor,
                        secondaryTextColor = secondaryTextColor,
                        onToggle = {
                            collapsedTypes = if (collapsed) {
                                collapsedTypes - category.type
                            } else {
                                collapsedTypes + category.type
                            }
                        }
                    )
                    if (!collapsed) {
                        category.items.forEach { item ->
                            ApiItemRow(
                                item = item,
                                accentColor = accentColor,
                                secondaryTextColor = secondaryTextColor,
                                onClick = {
                                    if (copyModeEnabled) {
                                        onCopyName(item.name)
                                    } else if (item.locations.isNotEmpty()) {
                                        usageItem = item
                                    } else {
                                        context.toastOnUi(R.string.api_no_usage)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        // 非复制模式：展示使用位置弹窗，点击位置跳编辑器对应字段
        usageItem?.let { item ->
            ApiUsageLocationsDialog(
                item = item,
                accentColor = accentColor,
                secondaryTextColor = secondaryTextColor,
                onDismiss = { usageItem = null },
                onLocate = { location ->
                    usageItem = null
                    context.startActivity(
                        Intent(context, BookSourceEditActivity::class.java).apply {
                            putExtra("sourceUrl", sourceUrl)
                            putExtra("tabKey", location.tabKey)
                            putExtra("fieldKey", location.fieldKey)
                        }
                    )
                }
            )
        }
    }
}

/**
 * 「使用位置」弹窗：列出该 API 在书源中命中的全部字段及其代码片段。
 * 点击某一行跳转到书源编辑器对应字段。
 */
@Composable
private fun ApiUsageLocationsDialog(
    item: ApiItem,
    accentColor: Color,
    secondaryTextColor: Color,
    onDismiss: () -> Unit,
    onLocate: (ApiUsageLocation) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    text = stringResource(R.string.api_usage_locations, item.name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = accentColor,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    items(item.locations, key = { it.tabKey + it.fieldKey + it.snippet.hashCode() }) { location ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = { onLocate(location) })
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "${location.tabKey}.${location.fieldKey}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = location.snippet,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = secondaryTextColor,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 分类标题行（可点击折叠 / 展开）：左侧分类名、右侧条目数与展开箭头。
 */
@Composable
private fun CategoryHeader(
    category: ApiCategory,
    collapsed: Boolean,
    accentColor: Color,
    secondaryTextColor: Color,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(
                when (category.type) {
                    ApiType.VARIABLE -> R.string.api_category_variable
                    ApiType.FUNCTION -> R.string.api_category_function
                    ApiType.SOURCE_METHOD -> R.string.api_category_source_method
                }
            ),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = accentColor
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "${category.items.size}",
            style = MaterialTheme.typography.bodySmall,
            color = secondaryTextColor
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = if (collapsed) {
                Icons.Filled.KeyboardArrowDown
            } else {
                Icons.Filled.KeyboardArrowUp
            },
            contentDescription = null,
            tint = secondaryTextColor,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * 单个 API 条目：命中项对勾 + 高亮，未命中置灰，点击复制名称。
 */
@Composable
private fun ApiItemRow(
    item: ApiItem,
    accentColor: Color,
    secondaryTextColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.used) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Spacer(Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (item.used) {
                MaterialTheme.colorScheme.onSurface
            } else {
                secondaryTextColor
            }
        )
        Spacer(Modifier.weight(1f))
        if (item.used) {
            Text(
                text = stringResource(R.string.api_used),
                style = MaterialTheme.typography.labelSmall,
                color = accentColor
            )
        }
    }
}

/**
 * API 名称搜索框，样式与书源检测页保持一致。
 */
@Composable
private fun SourceUsedApiSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    accentColor: Color
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search))
        },
        placeholder = {
            Text(stringResource(R.string.action_search))
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = accentColor,
            focusedLeadingIconColor = accentColor,
            cursorColor = accentColor
        )
    )
}