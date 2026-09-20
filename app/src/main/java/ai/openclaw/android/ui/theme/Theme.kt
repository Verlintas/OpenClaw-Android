package ai.openclaw.android.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Sci-Fi 暗色主题色板 — 深海蓝 + 青色霓虹
 */
private val SciFiDarkColorScheme = darkColorScheme(
    // 主色 —— 深墨蓝字压在亮青底上，对比度 10.2:1
    primary = SciFiPrimary,
    onPrimary = SciFiOnPrimary,
    primaryContainer = SciFiPrimaryContainer,
    onPrimaryContainer = SciFiOnPrimaryContainer,
    // 次色
    secondary = SciFiSecondary,
    onSecondary = SciFiOnPrimary,
    secondaryContainer = SciFiSecondaryContainer,
    onSecondaryContainer = SciFiOnSecondaryContainer,
    // 三级色（仅装饰，紫底配浅色字 5.2:1）
    tertiary = SciFiTertiary,
    onTertiary = SciFiOnBackground,
    tertiaryContainer = SciFiTertiaryContainer,
    onTertiaryContainer = SciFiOnTertiaryContainer,
    // 错误色（红底配深墨蓝字 5.1:1）
    error = SciFiError,
    onError = SciFiOnPrimary,
    errorContainer = SciFiErrorContainer,
    onErrorContainer = SciFiOnErrorContainer,
    // 基底
    background = SciFiBackground,
    onBackground = SciFiOnBackground,
    surface = SciFiSurface,
    onSurface = SciFiOnSurface,
    surfaceVariant = SciFiSurfaceVariant,
    onSurfaceVariant = SciFiOnSurfaceVariant,
    // Surface 层级 —— 补齐后不再回落 Material3 默认紫灰
    surfaceDim = SciFiSurfaceDim,
    surfaceBright = SciFiSurfaceBright,
    surfaceContainerLowest = SciFiSurfaceContainerLowest,
    surfaceContainerLow = SciFiSurfaceContainerLow,
    surfaceContainer = SciFiSurfaceContainer,
    surfaceContainerHigh = SciFiSurfaceContainerHigh,
    surfaceContainerHighest = SciFiSurfaceContainerHighest,
    // 反转色
    inverseSurface = SciFiInverseSurface,
    inverseOnSurface = SciFiInverseOnSurface,
    inversePrimary = SciFiInversePrimary,
    // 描边与遮罩
    outline = SciFiOutline,
    outlineVariant = SciFiOutlineVariant,
    scrim = SciFiScrim,
    surfaceTint = SciFiPrimary
)

/**
 * 亮色模式色板（可选，默认关闭）
 */
private val SciFiLightColorScheme = lightColorScheme(
    primary = SciFiLightPrimary,
    onPrimary = SciFiLightOnPrimary,
    secondary = SciFiSecondary,
    tertiary = SciFiTertiary,
    error = SciFiError,
    background = SciFiLightBackground,
    surface = SciFiLightSurface,
    surfaceVariant = SciFiSurfaceVariant,
    outline = SciFiOutline,
    outlineVariant = SciFiOutlineVariant,
    onBackground = SciFiLightOnSurface,
    onSurface = SciFiLightOnSurface,
    onSurfaceVariant = SciFiOnSurfaceVariant
)

@Composable
fun OpenClawTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = SciFiDarkColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = SciFiBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
