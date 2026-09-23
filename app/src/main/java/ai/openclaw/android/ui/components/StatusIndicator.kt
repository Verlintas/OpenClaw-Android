package ai.openclaw.android.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.openclaw.android.ui.theme.SciFiError
import ai.openclaw.android.ui.theme.SciFiPrimary
import ai.openclaw.android.ui.theme.SciFiSecondary
import kotlinx.coroutines.delay

enum class ConnectionState { ONLINE, THINKING, OFFLINE }

/**
 * 呼吸刷新率。
 *
 * 一个完整呼吸周期是 3 秒（1500ms 明 → 1500ms 暗），60fps 相当于 180 帧画同一个
 * alpha 渐变 —— 完全是浪费。20fps 时每个周期仍有 60 个采样点，肉眼完全顺滑。
 *
 * 为什么必须限帧：这个指示点常驻聊天页标题栏，实测 60fps 驱动时
 * 主线程 ~30% + RenderThread ~8%，光一个 8dp 圆点就常驻吃掉约 1/3 个核心。
 */
private const val PULSE_FPS = 20

private const val PULSE_MIN_ALPHA = 0.4f
private const val PULSE_MAX_ALPHA = 1f

@Composable
fun StatusIndicator(
    state: ConnectionState,
    size: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    val color = when (state) {
        ConnectionState.ONLINE -> SciFiPrimary
        ConnectionState.THINKING -> SciFiSecondary
        ConnectionState.OFFLINE -> SciFiError
    }

    // 呼吸动画不用 Compose 的 rememberInfiniteTransition：它以 vsync 频率驱动整棵子树
    // 重组/重绘，代价与「一个 8dp 圆点」严重不匹配。这里自己按固定低帧率推进 alpha，
    // 并把读取放在 graphicsLayer 的 lambda 里（状态读取落在「绘制阶段」，
    // 只让这一层重绘，不会带动父级重组）。视觉效果不变。
    var pulse by remember { mutableFloatStateOf(PULSE_MIN_ALPHA) }
    LaunchedEffect(state) {
        val halfCycleMs = if (state == ConnectionState.OFFLINE) 400L else 1500L
        val frameMs = (1000L / PULSE_FPS).coerceAtLeast(1L)
        val span = (PULSE_MAX_ALPHA - PULSE_MIN_ALPHA).toDouble()
        var elapsed = 0L
        while (true) {
            delay(frameMs)
            elapsed = (elapsed + frameMs) % (halfCycleMs * 2)
            // 三角波：0 → 1 → 0，线性、无需缓动即可读作「呼吸」
            val ratio = if (elapsed < halfCycleMs) {
                elapsed.toDouble() / halfCycleMs
            } else {
                2.0 - elapsed.toDouble() / halfCycleMs
            }
            pulse = (PULSE_MIN_ALPHA + span * ratio).toFloat()
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .background(color, CircleShape)
            .alpha(pulse)
            .then(
                if (state == ConnectionState.THINKING && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Modifier.shadow(
                        elevation = 4.dp,
                        shape = CircleShape,
                        ambientColor = color.copy(alpha = 0.5f),
                        spotColor = color.copy(alpha = 0.5f)
                    )
                } else Modifier
            )
    )
}
