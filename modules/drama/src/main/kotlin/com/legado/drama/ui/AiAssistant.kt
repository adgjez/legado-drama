package com.legado.drama.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.legado.drama.R
import com.legado.drama.ui.theme.DramaGradients
import com.legado.drama.ui.theme.DramaNeon

/** AI 面板内一条消息（role: "ai" / "user"） */
private data class AiMsg(val role: String, val text: String)

/**
 * 全局 AI 悬浮球（HANDOVER C2 / UI-BEAUTIFY §4）：
 * 56dp 圆形 GradAI 渐变（紫→品红）+ GlowShadow 外发光 + 1.5s 呼吸脉冲环（NeonMagenta 20% 扩散）。
 */
@Composable
fun AiAssistantFloating(onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "ai-pulse")
    val ringAlpha by pulse.animateFloat(
        initialValue = 0.20f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Restart),
        label = "ring-alpha",
    )
    val ringScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.45f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Restart),
        label = "ring-scale",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(72.dp),
    ) {
        // 呼吸脉冲环（扩散圈）
        Box(
            modifier = Modifier
                .size(56.dp * ringScale)
                .background(DramaNeon.NeonMagenta.copy(alpha = ringAlpha), CircleShape),
        )
        // 主球体：渐变 + 外发光阴影
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = CircleShape,
                    ambientColor = DramaNeon.GlowShadow,
                    spotColor = DramaNeon.GlowShadow,
                )
                .background(DramaGradients.Ai, CircleShape)
                .clickable(onClick = onClick),
        ) {
            Icon(
                imageVector = Icons.Filled.SmartToy,
                contentDescription = stringResource(R.string.ai_title),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/**
 * AI 聊天面板（HANDOVER C2 / UI-BEAUTIFY §4）：
 * 底部弹层：surfaceContainerHigh 底 + 顶部 GradAI 渐变头带（AI 助手 + 状态点）；
 * 用户气泡 GradHero 右对齐、AI 气泡 surfaceContainerHighest + GlassStroke 左对齐；
 * 输入框复用 OutlinedTextField，发送按钮 40dp 圆 GradAI。
 */
@Composable
fun AiAssistantPanel(onDismiss: () -> Unit) {
    val greeting = stringResource(R.string.ai_greeting)
    val intro = stringResource(R.string.ai_intro)
    val replyText = stringResource(R.string.ai_reply)
    var messages by remember {
        mutableStateOf(
            listOf(
                AiMsg("ai", greeting),
                AiMsg("ai", intro),
            ),
        )
    }
    var input by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        // 顶部 GradAI 头带
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(DramaGradients.Ai),
        )
        // 标题栏：状态点（NeonGreen 运行中）+ AI 助手 + 关闭
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(DramaNeon.NeonGreen, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.ai_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.ai_close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // 消息流
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            items(messages) { msg ->
                if (msg.role == "user") {
                    // 用户气泡：GradHero 渐变、右对齐
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 48.dp)
                            .background(DramaGradients.Hero, RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                            .padding(12.dp),
                    ) {
                        Text(msg.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimary)
                    }
                } else {
                    // AI 气泡：surfaceContainerHighest + GlassStroke 描边、左对齐
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 48.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                            .border(androidx.compose.foundation.BorderStroke(1.dp, DramaNeon.GlassStroke), RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                            .padding(12.dp),
                    ) {
                        Text(
                            text = msg.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        // 输入行
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.ai_placeholder), style = MaterialTheme.typography.bodyMedium) },
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .shadow(
                        elevation = 6.dp,
                        shape = CircleShape,
                        ambientColor = DramaNeon.GlowShadow,
                        spotColor = DramaNeon.GlowShadow,
                    )
                    .background(DramaGradients.Ai, CircleShape)
                    .clickable(enabled = input.isNotBlank()) {
                        if (input.isNotBlank()) {
                            messages = messages + AiMsg("user", input.trim())
                            input = ""
                            // 本地轻量回应（不引入实时 LLM 依赖；规格只约束交互形态）
                            messages = messages + AiMsg("ai", replyText)
                        }
                    },
            ) {
                Icon(
                    imageVector = Icons.Filled.Send,
                    contentDescription = stringResource(R.string.ai_send),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}