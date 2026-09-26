package com.btcpayapp.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The fallback palette, used when Material You dynamic colour is off or the
 * device predates it.
 *
 * Built around bitcoin orange as the primary, with a deliberately desaturated
 * neutral family: a merchant stares at this screen all day, and a saturated
 * background makes amounts harder to read. Tonal values follow the Material 3
 * roles rather than being hand-picked per component, so dynamic colour and this
 * palette produce the same visual hierarchy.
 */
internal object Palette {
    val Orange10 = Color(0xFF2B1600)
    val Orange20 = Color(0xFF452800)
    val Orange30 = Color(0xFF633B00)
    val Orange40 = Color(0xFF854F00)
    val Orange60 = Color(0xFFC77B12)
    val Orange80 = Color(0xFFFFB95C)
    val Orange90 = Color(0xFFFFDDB6)
    val Orange100 = Color(0xFFFFFFFF)

    val Slate10 = Color(0xFF15181D)
    val Slate20 = Color(0xFF2A2E35)
    val Slate30 = Color(0xFF41454D)
    val Slate40 = Color(0xFF585D65)
    val Slate80 = Color(0xFFC1C6CF)
    val Slate90 = Color(0xFFDDE2EB)
    val Slate95 = Color(0xFFEBF0F9)

    val Teal40 = Color(0xFF00696E)
    val Teal80 = Color(0xFF4EDADF)
    val Teal90 = Color(0xFF9CF1F5)

    val Red40 = Color(0xFFBA1A1A)
    val Red80 = Color(0xFFFFB4AB)
    val Red90 = Color(0xFFFFDAD6)
    val Red10 = Color(0xFF410002)
}

internal val LightScheme = lightColorScheme(
    primary = Palette.Orange40,
    onPrimary = Palette.Orange100,
    primaryContainer = Palette.Orange90,
    onPrimaryContainer = Palette.Orange10,
    secondary = Palette.Slate40,
    onSecondary = Color.White,
    secondaryContainer = Palette.Slate90,
    onSecondaryContainer = Palette.Slate10,
    tertiary = Palette.Teal40,
    onTertiary = Color.White,
    tertiaryContainer = Palette.Teal90,
    onTertiaryContainer = Color(0xFF002021),
    error = Palette.Red40,
    onError = Color.White,
    errorContainer = Palette.Red90,
    onErrorContainer = Palette.Red10,
    background = Color(0xFFFCFCFF),
    onBackground = Palette.Slate10,
    surface = Color(0xFFFCFCFF),
    onSurface = Palette.Slate10,
    surfaceVariant = Palette.Slate95,
    onSurfaceVariant = Palette.Slate30,
    outline = Color(0xFF75777F),
    outlineVariant = Color(0xFFC5C6D0),
)

internal val DarkScheme = darkColorScheme(
    primary = Palette.Orange80,
    onPrimary = Palette.Orange20,
    primaryContainer = Palette.Orange30,
    onPrimaryContainer = Palette.Orange90,
    secondary = Palette.Slate80,
    onSecondary = Palette.Slate20,
    secondaryContainer = Palette.Slate30,
    onSecondaryContainer = Palette.Slate90,
    tertiary = Palette.Teal80,
    onTertiary = Color(0xFF00363A),
    tertiaryContainer = Color(0xFF004F52),
    onTertiaryContainer = Palette.Teal90,
    error = Palette.Red80,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Palette.Red90,
    background = Color(0xFF0F1115),
    onBackground = Palette.Slate90,
    surface = Color(0xFF0F1115),
    onSurface = Palette.Slate90,
    surfaceVariant = Palette.Slate20,
    onSurfaceVariant = Palette.Slate80,
    outline = Color(0xFF8E9099),
    outlineVariant = Palette.Slate30,
)

/**
 * Semantic colours that are not Material roles: payment states need to read the
 * same way in both themes and must never collide with the error role, which is
 * reserved for "you did something wrong" rather than "this invoice expired".
 */
@androidx.compose.runtime.Immutable
data class StatusColors(
    val settled: Color,
    val onSettled: Color,
    val pending: Color,
    val onPending: Color,
    val expired: Color,
    val onExpired: Color,
    val invalid: Color,
    val onInvalid: Color,
    val incoming: Color,
    val outgoing: Color,
)

internal val LightStatusColors = StatusColors(
    settled = Color(0xFFCCEFD4),
    onSettled = Color(0xFF07401A),
    pending = Color(0xFFFFE2B8),
    onPending = Color(0xFF3F2A00),
    expired = Color(0xFFE2E3E9),
    onExpired = Color(0xFF41454D),
    invalid = Palette.Red90,
    onInvalid = Palette.Red10,
    incoming = Color(0xFF1E6B36),
    outgoing = Color(0xFF8A4A00),
)

internal val DarkStatusColors = StatusColors(
    settled = Color(0xFF12351F),
    onSettled = Color(0xFF8FE3A6),
    pending = Color(0xFF3B2B0C),
    onPending = Color(0xFFFFD08A),
    expired = Color(0xFF24272E),
    onExpired = Color(0xFFA8ADB8),
    invalid = Color(0xFF4A1512),
    onInvalid = Palette.Red80,
    incoming = Color(0xFF7BD79A),
    outgoing = Color(0xFFFFB95C),
)
