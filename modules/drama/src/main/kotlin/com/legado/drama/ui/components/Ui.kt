package com.legado.drama.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.legado.drama.ui.theme.DramaGradients
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 间距体系（8pt 栅格，4dp 半档）——对齐源工程 Spacing 规格（all 契约：页面/卡片统一取值） */
object DramaSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val screen = 48.dp
}

/**
 * 统一 DramaCard（对齐源工程 components/Card.kt DramaCard 规格：
 * 圆角 DramaShapes.large(18dp) / surfaceContainerLow / tonalElevation 1dp / contentPadding 16dp）。
 * onClick 非空时启用点击态（M3 clickable Surface）。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DramaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(DramaSpacing.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    val color = containerColor ?: MaterialTheme.colorScheme.surfaceContainerLow
    val shape = MaterialTheme.shapes.large
    val column: @Composable () -> Unit = {
        Column(Modifier.padding(contentPadding), content = content)
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            tonalElevation = 1.dp,
            shape = shape,
            color = color,
            border = border,
            modifier = modifier,
            content = column,
        )
    } else {
        Surface(
            tonalElevation = 1.dp,
            shape = shape,
            color = color,
            border = border,
            modifier = modifier,
            content = column,
        )
    }
}

/** 统一页面头部：大标题 + 可选副标题 + 右侧 actions（对齐源工程 components/PageHeader.kt） */
@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(bottom = DramaSpacing.lg - 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(DramaSpacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DramaSpacing.xs),
            content = actions,
        )
    }
}

/** 统一空状态（对齐源工程 components/EmptyState.kt：图标圆底容器 + 标题 + 副文案 + 行动区） */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    subtitle: String? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = DramaSpacing.xl, vertical = DramaSpacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(96.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.extraLarge,
                    ),
                contentAlignment = Alignment.Center,
                content = { icon() },
            )
            Spacer(Modifier.height(DramaSpacing.lg))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** 渐变主 CTA（对齐源工程 components/Buttons.kt HeroButton：hero 渐变填充，禁用态 surfaceVariant） */
@Composable
fun HeroButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (enabled) {
                        Modifier.background(DramaGradients.Hero)
                    } else {
                        Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                    },
                )
                .padding(horizontal = DramaSpacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 标准主按钮（primary 纯色填充，次级但明确的动作，对齐源工程 PrimaryButton） */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

/** 图标 + 文字入口（对齐源工程 IconActionButton） */
@Composable
fun IconActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 统一选中态筛选芯片（对齐源工程 components/FilterChip.kt：primaryContainer 底 / primary 边框） */
@Composable
fun DramaFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = scheme.primaryContainer,
            selectedLabelColor = scheme.onPrimaryContainer,
            selectedLeadingIconColor = scheme.primary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = if (selected) scheme.primary else scheme.outlineVariant,
        ),
    )
}

/** 行内加载态：小号 spinner + 进行中文案（对齐源工程 LoadingRow） */
@Composable
fun LoadingRow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = DramaSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 状态级别（对齐源工程 StatusMessage） */
enum class StatusLevel { INFO, SUCCESS, ERROR }

data class StatusMessage(val text: String, val level: StatusLevel)

fun statusInfo(text: String) = StatusMessage(text, StatusLevel.INFO)
fun statusOk(text: String) = StatusMessage(text, StatusLevel.SUCCESS)
fun statusErr(text: String) = StatusMessage(text, StatusLevel.ERROR)

/**
 * 统一状态条（对齐源工程 StatusCard）：语义色取自 colorScheme
 * （errorContainer / primaryContainer / surfaceVariant），图标+图标语义一致。
 */
@Composable
fun StatusCard(
    msg: StatusMessage,
    modifier: Modifier = Modifier,
    dense: Boolean = true,
) {
    val (container, content, icon) = when (msg.level) {
        StatusLevel.SUCCESS -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Default.CheckCircle as ImageVector,
        )
        StatusLevel.ERROR -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Default.Warning as ImageVector,
        )
        StatusLevel.INFO -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Default.Info as ImageVector,
        )
    }
    Surface(
        tonalElevation = if (dense) 1.dp else 2.dp,
        shape = MaterialTheme.shapes.medium,
        color = container,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = DramaSpacing.md, vertical = if (dense) DramaSpacing.sm else DramaSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DramaSpacing.sm),
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Text(
                msg.text,
                modifier = Modifier.weight(1f),
                style = if (dense) MaterialTheme.typography.bodySmall
                else MaterialTheme.typography.bodyMedium,
                color = content,
            )
        }
    }
}

/**
 * 统一瞬时反馈通道（对齐源工程 components/Snackbar.kt）：
 * [LocalDramaSnackbar] 全局注入 controller，页面任意位置 show(...) 即可触发底部 Snackbar。
 */
class DramaSnackbarController(
    private val hostState: SnackbarHostState,
    private val scope: CoroutineScope,
) {
    fun show(
        message: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        withDismissAction: Boolean = false,
        duration: SnackbarDuration = SnackbarDuration.Short,
    ) {
        scope.launch {
            val result = hostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                withDismissAction = withDismissAction,
                duration = duration,
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

/** 页面内通过 `LocalDramaSnackbar.current.show("已保存")` 触发统一 Snackbar */
val LocalDramaSnackbar = compositionLocalOf<DramaSnackbarController> {
    error("LocalDramaSnackbar 未提供：请用 CompositionLocalProvider 在 DramaApp 注入 controller。")
}

/** 挂在 Scaffold 上的唯一 Snackbar 宿主，视觉规格统一走 DramaTheme */
@Composable
fun DramaSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            shape = MaterialTheme.shapes.medium,
            containerColor = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
            actionColor = scheme.primary,
            dismissActionContentColor = scheme.onSurfaceVariant,
        )
    }
}