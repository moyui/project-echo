package com.echo.android.capture

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 悬浮球 + 内嵌 Speed Dial 菜单（同一 overlay 窗口，Compose 只负责视觉）。
 * 手势在 View 层用 rawX/rawY 处理（服务端 setOnTouchListener）——
 * Compose 的 positionChange 是窗口本地坐标，窗口本身随拖动移动时位移被抵消（拖不跟手）；
 * 长按也用 Handler 定时（Compose 事件流在手指静止时无事件可等）。
 * pressed/longFired 状态由服务端传入驱动按压缩放动画。
 */
@Composable
fun FloatBall(
    text: String,
    dimmed: Boolean,
    hasContent: Boolean,
    menuOpen: Boolean,
    menuAtLeft: Boolean,
    pressed: Boolean,
    longFired: Boolean,
    onClear: () -> Unit,
    onResults: () -> Unit,
    onCloseService: () -> Unit,
) {
    // 长按时缩到 0.9：放大超窗口边界会被裁出缺角（窗口=球大小），菜单弹出已是主反馈
    val scale by animateFloatAsState(
        targetValue = when {
            longFired -> 0.9f
            pressed -> 0.86f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "ballScale",
    )

    // 菜单贴球展开（间隙恒 12dp=64dp 偏移的语义）：
    // 球在左 → 菜单左缘 = 球右缘 + 12dp（TopStart 锚 + 右移 64dp）
    // 球在右 → 菜单右缘 = 球左缘 - 12dp（TopEnd 锚 + 左移 64dp，右缘=窗口右缘=球右缘）
    // 菜单是 wrap-content 宽度，不能按窗口左缘固定 offset，否则右侧展开时悬空不贴球
    val menuAlign: Alignment = if (menuAtLeft) Alignment.TopEnd else Alignment.TopStart
    val menuOffsetX = if (menuAtLeft) (-64).dp else 64.dp
    val ballAlign: Alignment = if (menuAtLeft) Alignment.TopEnd else Alignment.TopStart

    Box(modifier = Modifier.fillMaxSize()) {
        if (menuOpen) {
            BallMenu(
                modifier = Modifier
                    .align(menuAlign)
                    .offset(x = menuOffsetX, y = 4.dp),
                mirrored = menuAtLeft,
                onClear = onClear,
                onResults = onResults,
                onCloseService = onCloseService,
            )
        }
        // 球本体（手势由 View 层处理，这里只渲染）
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(ballAlign)
                .size(52.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = if (dimmed) 0.6f else 1f
                }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        ) {
            Text(text = text, color = Color.White, fontSize = 20.sp)
            if (hasContent) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(10.dp)
                        .graphicsLayer { translationX = 1f; translationY = -1f }
                        .clip(CircleShape)
                        .background(Color(0xFFFF5A5A)),
                )
            }
        }
    }
}

/** Speed Dial 菜单：竖排小圆钮+标签，错峰弹出；mirrored=true 时行内顺序镜像（圆钮贴球侧） */
@Composable
fun BallMenu(
    modifier: Modifier = Modifier,
    mirrored: Boolean = false,
    onClear: () -> Unit,
    onResults: () -> Unit,
    onCloseService: () -> Unit,
) {
    val items = listOf(
        Triple("清", "清除译文", onClear),
        Triple("果", "识别结果", onResults),
        Triple("关", "关闭悬浮球", onCloseService),
    )
    // 菜单项按贴球侧对齐：球在左（badge 在左）→ 各项左缘对齐；
    // 球在右（badge 在右，菜单在球左）→ 各项右缘对齐，整体贴向球侧。
    // 固定 Column 宽度为最宽菜单项的固有宽（wrap-content 会随各项不同宽而逐项左对齐）
    Column(modifier = modifier.width(IntrinsicSize.Max).padding(4.dp)) {
        items.forEachIndexed { index, (badge, label, action) ->
            MenuDialItem(
                index = index,
                badge = badge,
                label = label,
                mirrored = mirrored,
                onClick = action,
            )
            if (index != items.lastIndex) Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun MenuDialItem(
    index: Int,
    badge: String,
    label: String,
    mirrored: Boolean,
    onClick: () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 60L)
        shown = true
    }
    val appear: Float by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "dialAppear",
    )
    val badgeButton = @Composable {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(42.dp)
                .graphicsLayer {
                    val s = 0.3f + 0.7f * appear
                    scaleX = s
                    scaleY = s
                    alpha = appear
                }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        ) {
            Text(badge, color = Color.White, fontSize = 16.sp)
        }
    }
    val labelChip = @Composable {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .graphicsLayer {
                    val s = 0.6f + 0.4f * appear
                    scaleX = s
                    scaleY = s
                    alpha = appear
                }
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xEE2A2A32))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(label, color = Color.White, fontSize = 13.sp)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (mirrored) Arrangement.End else Arrangement.Start,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        if (mirrored) {
            labelChip()
            Spacer(Modifier.width(8.dp))
            badgeButton()
        } else {
            badgeButton()
            Spacer(Modifier.width(8.dp))
            labelChip()
        }
    }
}
