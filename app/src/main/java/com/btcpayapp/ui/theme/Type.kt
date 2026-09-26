package com.btcpayapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Type scale.
 *
 * No font files are bundled. The system font is already tuned for the device's
 * display and respects the user's font-size and bold-text accessibility
 * settings; shipping a custom family would add a megabyte to the APK, break
 * those settings, and look worse on e-ink and low-DPI screens.
 *
 * The one deviation from the Material defaults is tighter tracking on the
 * display sizes, where large amounts are rendered, and a tabular monospace
 * style for anything the user might compare character by character.
 */
private val Default = Typography()

internal val AppTypography = Typography(
    displayLarge = Default.displayLarge.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = (-1).sp,
    ),
    displayMedium = Default.displayMedium.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.5).sp,
    ),
    displaySmall = Default.displaySmall.copy(fontWeight = FontWeight.Medium),
    headlineLarge = Default.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = Default.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = Default.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Default.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/**
 * For addresses, transaction ids, BOLT11 invoices and derivation schemes.
 * Monospace so a user can compare two strings by eye, which is the whole point
 * of showing them at all.
 */
val MonospaceStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
    letterSpacing = 0.sp,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

/** The big number on the terminal and invoice screens. */
val AmountStyle = TextStyle(
    fontSize = 44.sp,
    lineHeight = 50.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = (-1).sp,
)
