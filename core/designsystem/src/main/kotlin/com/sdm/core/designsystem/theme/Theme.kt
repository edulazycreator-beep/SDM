package com.sdm.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** SDM brand palette: the logo blue (#375C9E, navy #10264D) as primary, cyan secondary, semantic status colors. */
object SdmColors {
    val Blue = Color(0xFF375C9E)
    val Navy = Color(0xFF10264D)
    val BlueDark = Color(0xFFA9C2F4)
    val Cyan = Color(0xFF00A5C8)
    val CyanDark = Color(0xFF5FD4F0)
    val Success = Color(0xFF1E9E5A)
    val Warning = Color(0xFFE39A0B)
    val Error = Color(0xFFD93B3B)
    val Charcoal = Color(0xFF14171C)
    val CharcoalSurface = Color(0xFF1C2027)
    val Neutral = Color(0xFFF7F8FA)
    val accents = listOf(0xFF375C9E, 0xFF1F5EFF, 0xFF00A5C8, 0xFF6B4EFF, 0xFF1E9E5A, 0xFFE0532F, 0xFFD6336C)
}

@Immutable
data class StatusColors(val success: Color, val warning: Color, val error: Color, val info: Color, val neutral: Color)

@Immutable
data class Spacing(val xs: Dp, val s: Dp, val m: Dp, val l: Dp, val xl: Dp, val listItemVertical: Dp)

val LocalStatusColors = staticCompositionLocalOf { StatusColors(SdmColors.Success, SdmColors.Warning, SdmColors.Error, SdmColors.Blue, Color.Gray) }
val LocalSpacing = staticCompositionLocalOf { comfortable }
val LocalAnimationsEnabled = staticCompositionLocalOf { true }

private val comfortable = Spacing(4.dp, 8.dp, 12.dp, 16.dp, 24.dp, 12.dp)
private val compact = Spacing(2.dp, 6.dp, 8.dp, 12.dp, 18.dp, 6.dp)

enum class SdmThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

private fun light(accent: Color) = lightColorScheme(
    primary = accent, onPrimary = Color.White, primaryContainer = accent.copy(alpha = 0.14f).compositeOverWhite(), onPrimaryContainer = SdmColors.Navy,
    secondary = SdmColors.Cyan, onSecondary = Color.White, secondaryContainer = Color(0xFFCDF3FB), onSecondaryContainer = Color(0xFF00313D),
    tertiary = SdmColors.Success, background = SdmColors.Neutral, surface = Color.White, surfaceVariant = Color(0xFFEDEFF3),
    surfaceContainer = Color(0xFFF1F3F7), surfaceContainerHigh = Color(0xFFEBEEF3), surfaceContainerLow = Color(0xFFF7F8FA),
    error = SdmColors.Error, outline = Color(0xFFC3C8D2), outlineVariant = Color(0xFFE1E4EA),
)

private fun dark(accent: Color, amoled: Boolean): ColorScheme {
    val bg = if (amoled) Color.Black else SdmColors.Charcoal
    val surface = if (amoled) Color.Black else SdmColors.CharcoalSurface
    return darkColorScheme(
        primary = if (accent == SdmColors.Blue) SdmColors.BlueDark else accent.lighten(), onPrimary = SdmColors.Navy, primaryContainer = accent.copy(alpha = 0.35f), onPrimaryContainer = Color.White,
        secondary = SdmColors.CyanDark, onSecondary = Color(0xFF00363F), tertiary = Color(0xFF5BD08F), background = bg, surface = surface,
        surfaceVariant = if (amoled) Color(0xFF111111) else Color(0xFF262B33), surfaceContainer = if (amoled) Color(0xFF0B0B0B) else Color(0xFF20242B),
        surfaceContainerHigh = if (amoled) Color(0xFF141414) else Color(0xFF282D35), surfaceContainerLow = if (amoled) Color.Black else Color(0xFF1A1D23),
        error = Color(0xFFFF7A7A), outline = Color(0xFF5A606B), outlineVariant = Color(0xFF353A43),
    )
}

private fun Color.compositeOverWhite(): Color = Color(
    red = red * alpha + (1 - alpha), green = green * alpha + (1 - alpha), blue = blue * alpha + (1 - alpha), alpha = 1f,
)

private fun Color.lighten(f: Float = 0.45f) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)

/** Poppins (SIL Open Font License, bundled so it works offline). */
val Poppins = FontFamily(
    androidx.compose.ui.text.font.Font(com.sdm.core.designsystem.R.font.poppins_regular, FontWeight.Normal),
    androidx.compose.ui.text.font.Font(com.sdm.core.designsystem.R.font.poppins_medium, FontWeight.Medium),
    androidx.compose.ui.text.font.Font(com.sdm.core.designsystem.R.font.poppins_semibold, FontWeight.SemiBold),
    androidx.compose.ui.text.font.Font(com.sdm.core.designsystem.R.font.poppins_bold, FontWeight.Bold),
)

// Poppins is wide with a tall x-height, so body sizes sit slightly below Material defaults.
private val SdmTypography = Typography().let { t ->
    val f = Poppins
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
        displayMedium = t.displayMedium.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
        displaySmall = t.displaySmall.copy(fontFamily = f, fontWeight = FontWeight.SemiBold),
        headlineLarge = t.headlineLarge.copy(fontFamily = f, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        headlineMedium = t.headlineMedium.copy(fontFamily = f, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        headlineSmall = t.headlineSmall.copy(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
        titleLarge = t.titleLarge.copy(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
        titleMedium = t.titleMedium.copy(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
        titleSmall = t.titleSmall.copy(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        bodyLarge = t.bodyLarge.copy(fontFamily = f, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
        bodyMedium = t.bodyMedium.copy(fontFamily = f, fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        bodySmall = t.bodySmall.copy(fontFamily = f, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.sp),
        labelLarge = t.labelLarge.copy(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.sp),
        labelMedium = t.labelMedium.copy(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.sp),
        labelSmall = t.labelSmall.copy(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.sp),
    )
}

val MonoNumbers = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

private val SdmShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SdmTheme(
    mode: SdmThemeMode = SdmThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    accent: Color = SdmColors.Blue,
    compactLayout: Boolean = false,
    animations: Boolean = true,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (mode) { SdmThemeMode.SYSTEM -> systemDark; SdmThemeMode.LIGHT -> false; else -> true }
    val amoled = mode == SdmThemeMode.AMOLED
    val context = LocalContext.current
    var scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> dark(accent, amoled)
        else -> light(accent)
    }
    if (amoled) scheme = scheme.copy(background = Color.Black, surface = Color.Black, surfaceContainerLow = Color.Black, surfaceContainer = Color(0xFF0B0B0B))
    val status = if (darkTheme) StatusColors(Color(0xFF5BD08F), Color(0xFFFFC24D), Color(0xFFFF7A7A), scheme.primary, Color(0xFF9AA1AD))
    else StatusColors(SdmColors.Success, SdmColors.Warning, SdmColors.Error, scheme.primary, Color(0xFF6B7280))
    CompositionLocalProvider(
        LocalStatusColors provides status,
        LocalSpacing provides if (compactLayout) compact else comfortable,
        LocalAnimationsEnabled provides animations,
    ) {
        MaterialTheme(colorScheme = scheme, typography = SdmTypography, shapes = SdmShapes, content = content)
    }
}
