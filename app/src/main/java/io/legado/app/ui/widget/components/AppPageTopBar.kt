package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.R
import io.legado.app.ui.theme.pageTopBarBackground
import io.legado.app.ui.theme.pageTopBarColors

/**
 * 页面统一顶栏（theme-styles.md §7.7.1 顶栏规范）
 *
 * 收敛各页面 TopAppBar 的重复配色样板：
 * - 背景/阴影/圆角/壁纸统一由 [pageTopBarBackground] 承载，配色源 [pageTopBarColors]（同 TopBarConfig 公式）
 * - 导航/标题/操作图标统一用 [io.legado.app.ui.theme.PageTopBarColors.contentColor]
 * - 标题用 typography.titleLarge + Medium 字重，副标题用 bodySmall
 *
 * @param title 标题文案（必须已资源化）
 * @param onBackClick 返回键点击
 * @param modifier Modifier
 * @param subtitle 副标题（可选，单行省略）
 * @param backIcon 返回图标（默认 ArrowBack，选择态等场景可换 Close）
 * @param backContentDescription 返回键无障碍描述（默认"返回"）
 * @param containerColor 容器色（默认透明；背景统一由 [pageTopBarBackground] 承载，仅连体场景可覆写）
 * @param showBackground true（默认）时由组件自身承载完整背景（阴影/圆角/壁纸）；false 时交由外层容器承载（连体场景）
 * @param scrollBehavior 滚动收起行为（默认 null；需要上滑收起/隐藏顶栏时传入 enterAlwaysScrollBehavior() 等）
 * @param actions 右侧操作区
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPageTopBar(
    title: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    backContentDescription: String? = null,
    containerColor: Color = Color.Transparent,
    showBackground: Boolean = true,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val colors = pageTopBarColors()
    val barModifier = if (showBackground) {
        modifier.pageTopBarBackground(colors)
    } else {
        modifier
    }
    TopAppBar(
        modifier = barModifier,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            scrolledContainerColor = containerColor,
            navigationIconContentColor = colors.contentColor,
            titleContentColor = colors.contentColor,
            actionIconContentColor = colors.contentColor
        ),
        scrollBehavior = scrollBehavior,
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    backIcon,
                    contentDescription = backContentDescription
                        ?: stringResource(R.string.back)
                )
            }
        },
        actions = actions
    )
}

// ── 预览（§10.1 强制）────────────────────────────────────────

@Preview(showBackground = true)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPageTopBarPreview() {
    MaterialTheme {
        AppPageTopBar(
            title = "存储管理",
            subtitle = "共 5 项缓存 · 128.5 MB",
            onBackClick = {}
        )
    }
}

@Preview(name = "With actions", showBackground = true)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPageTopBarWithActionsPreview() {
    MaterialTheme {
        AppPageTopBar(
            title = "存储管理",
            onBackClick = {},
            actions = {
                IconButton(onClick = {}) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = null
                    )
                }
            }
        )
    }
}