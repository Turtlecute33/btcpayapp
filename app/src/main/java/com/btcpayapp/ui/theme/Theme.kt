package com.btcpayapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.btcpayapp.data.model.ThemeMode

private val LocalStatusColors: ProvidableCompositionLocal<StatusColors> =
    staticCompositionLocalOf { LightStatusColors }

/**
 * Shapes lean rounder than the Material default. Expressive design uses corner
 * radius as a size cue, and a payment terminal benefits from large, obviously
 * tappable surfaces.
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun BtcPayTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = true,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    val context = LocalContext.current
    val scheme = remember(dark, dynamicColor, pureBlack, context) {
        val base = when {
            // Material You: the palette is derived from the user's wallpaper by
            // the system, so honouring it is both the platform convention and
            // one less thing for this app to get wrong on an unusual display.
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            dark -> DarkScheme
            else -> LightScheme
        }
        if (dark && pureBlack) base.toPureBlack() else base
    }

    val statusColors = if (dark) DarkStatusColors else LightStatusColors

    CompositionLocalProvider(
        LocalStatusColors provides statusColors,
        LocalReducedMotion provides systemPrefersReducedMotion(),
    ) {
        // Material 3 1.4.0 seals off its expressive motion completely:
        // `MaterialExpressiveTheme`, the `MotionScheme` interface, the
        // `MotionScheme.expressive()` factory and the `MaterialTheme` overload
        // that takes one are all `internal` in the Kotlin metadata, even
        // though several of them are public in the compiled class file. There
        // is no supported way to hand Material a motion scheme from here.
        //
        // So the expressive springs are applied by [Motion], which restates
        // the same token values and drives everything this app animates
        // itself. What is left on Material's own standard springs is the
        // internal motion of its components — a Switch thumb travelling, a
        // navigation-bar indicator sliding. Those are stiffer than the rest of
        // the app by roughly a factor of two and there is nothing to be done
        // about it short of reimplementing the components; revisit when the
        // expressive entry point ships as public API.
        MaterialTheme(
            colorScheme = scheme,
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}

/**
 * True black for OLED panels. Only the background and lowest surface roles are
 * flattened; the elevated container roles keep their tonal steps so cards do
 * not disappear into the void.
 */
private fun ColorScheme.toPureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1B1B1B),
    surfaceContainerHighest = Color(0xFF242424),
)

/** Payment-state colours, reachable the same way as `MaterialTheme.colorScheme`. */
object AppTheme {
    val statusColors: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current

    val colorScheme: ColorScheme
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme

    val typography: Typography
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography
}
