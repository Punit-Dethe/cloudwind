package dev.cloudwind.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Ceramic Light & Space Black OLED design tokens (Cloudwind v4).
 * Strict visual restraint: zero purple/violet/indigo/lavender anywhere.
 */
@Immutable
data class CwColors(
    val isDark: Boolean,
    val bgApp: Color,
    val bgAppFrosted: Color,
    val bgCard: Color,
    val bgElevated: Color,
    val cardBorder: Color,
    val fillSecondary: Color,
    val hairline: Color,
    val ink1: Color,
    val ink2: Color,
    val ink3: Color,
    val blue: Color,
    val blueTint: Color,
    val green: Color,
    val greenSolid: Color,
    val greenTint: Color,
    val amber: Color,
    val amberSolid: Color,
    val amberTint: Color,
    val red: Color,
    val redSolid: Color,
    val redTint: Color,
    val ringPc: Color,
    val ringCloud: Color,
    val ringWait: Color,
    val trackBar: Color,
    val tabbarBg: Color,
    val tabbarBorder: Color,
    val tabbarThumb: Color,
    val segBg: Color,
    val segThumb: Color,
    val switchOff: Color,
    val switchOn: Color,
    val sliderTrack: Color,
    val sheetBg: Color,
    val sheetSurface: Color,
    val menuBg: Color,
    val toastBg: Color,
    val toastInk: Color,
    val slateTile: Color,
)

val LightCwColors = CwColors(
    isDark = false,
    bgApp = Color(0xFFF2F2F7),
    bgAppFrosted = Color(0xD9F2F2F7),
    bgCard = Color(0xFFFFFFFF),
    bgElevated = Color(0xFFF2F2F7),
    cardBorder = Color(0x0A000000),
    fillSecondary = Color(0x1F787880),
    hairline = Color(0x213C3C43),
    ink1 = Color(0xFF000000),
    ink2 = Color(0x993C3C43),
    ink3 = Color(0x4D3C3C43),
    blue = Color(0xFF007AFF),
    blueTint = Color(0x1A007AFF),
    green = Color(0xFF248A3D),
    greenSolid = Color(0xFF34C759),
    greenTint = Color(0x1F34C759),
    amber = Color(0xFFB25000),
    amberSolid = Color(0xFFFF9500),
    amberTint = Color(0x1FFF9500),
    red = Color(0xFFFF3B30),
    redSolid = Color(0xFFFF3B30),
    redTint = Color(0x16FF3B30),
    ringPc = Color(0xFF34C759),
    ringCloud = Color(0xFF007AFF),
    ringWait = Color(0xFFFF9500),
    trackBar = Color(0x24787880),
    tabbarBg = Color(0xEBFFFFFF),
    tabbarBorder = Color(0x1A3C3C43),
    tabbarThumb = Color(0x0D000000),
    segBg = Color(0x1E787880),
    segThumb = Color(0xFFFFFFFF),
    switchOff = Color(0x29787880),
    switchOn = Color(0xFF34C759),
    sliderTrack = Color(0x24787880),
    sheetBg = Color(0xFFF2F2F7),
    sheetSurface = Color(0xFFFFFFFF),
    menuBg = Color(0xFAFAFAFC),
    toastBg = Color(0xF01C1C1E),
    toastInk = Color(0xFFFFFFFF),
    slateTile = Color(0xFF8E8E93),
)

val DarkCwColors = CwColors(
    isDark = true,
    bgApp = Color(0xFF000000),
    bgAppFrosted = Color(0xCC000000),
    bgCard = Color(0xFF1C1C1E),
    bgElevated = Color(0xFF2C2C2E),
    cardBorder = Color(0x14FFFFFF),
    fillSecondary = Color(0x3D787880),
    hairline = Color(0x80545458),
    ink1 = Color(0xFFFFFFFF),
    ink2 = Color(0x99EBEBF5),
    ink3 = Color(0x47EBEBF5),
    blue = Color(0xFF0A84FF),
    blueTint = Color(0x2E0A84FF),
    green = Color(0xFF30D158),
    greenSolid = Color(0xFF30D158),
    greenTint = Color(0x2630D158),
    amber = Color(0xFFFF9F0A),
    amberSolid = Color(0xFFFF9F0A),
    amberTint = Color(0x29FF9F0A),
    red = Color(0xFFFF453A),
    redSolid = Color(0xFFFF453A),
    redTint = Color(0x21FF453A),
    ringPc = Color(0xFF30D158),
    ringCloud = Color(0xFF0A84FF),
    ringWait = Color(0xFFFF9F0A),
    trackBar = Color(0x42787880),
    tabbarBg = Color(0xE01E1E20),
    tabbarBorder = Color(0x52545458),
    tabbarThumb = Color(0x1AFFFFFF),
    segBg = Color(0x38787880),
    segThumb = Color(0xFF3A3A3C),
    switchOff = Color(0x52787880),
    switchOn = Color(0xFF30D158),
    sliderTrack = Color(0x3D787880),
    sheetBg = Color(0xFF1C1C1E),
    sheetSurface = Color(0xFF2C2C2E),
    menuBg = Color(0xF528282A),
    toastBg = Color(0xF52C2C2E),
    toastInk = Color(0xFFFFFFFF),
    slateTile = Color(0xFF636366),
)

val LocalCwColors = staticCompositionLocalOf { LightCwColors }

object CwTheme {
    val colors: CwColors
        @Composable get() = LocalCwColors.current
}

@Composable
fun CloudwindDesignTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val c = if (dark) DarkCwColors else LightCwColors
    val m3Scheme = if (dark) {
        darkColorScheme(
            primary = c.blue,
            onPrimary = Color.White,
            background = c.bgApp,
            onBackground = c.ink1,
            surface = c.bgCard,
            onSurface = c.ink1,
            surfaceVariant = c.bgElevated,
            onSurfaceVariant = c.ink2,
            error = c.red,
            onError = Color.White,
            outline = c.hairline,
        )
    } else {
        lightColorScheme(
            primary = c.blue,
            onPrimary = Color.White,
            background = c.bgApp,
            onBackground = c.ink1,
            surface = c.bgCard,
            onSurface = c.ink1,
            surfaceVariant = c.bgElevated,
            onSurfaceVariant = c.ink2,
            error = c.red,
            onError = Color.White,
            outline = c.hairline,
        )
    }
    CompositionLocalProvider(LocalCwColors provides c) {
        MaterialTheme(colorScheme = m3Scheme, content = content)
    }
}
