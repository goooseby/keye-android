package io.github.goooseby.keye.ui.theme


import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DayColors = lightColorScheme(
    primary=Color(0xFF00658A), onPrimary=Color.White,
    primaryContainer=Color(0xFFC3EAFF), onPrimaryContainer=Color(0xFF004B68),
    secondary=Color(0xFF526575), onSecondary=Color.White,
    secondaryContainer=Color(0xFFE5EDF5), onSecondaryContainer=Color(0xFF344653),
    tertiary=Color(0xFF6856A0), onTertiary=Color.White,
    tertiaryContainer=Color(0xFFEEE5FF), onTertiaryContainer=Color(0xFF483574),
    background=Color.White, onBackground=Color(0xFF202529),
    surface=Color.White, onSurface=Color(0xFF202529),
    surfaceVariant=Color(0xFFEAF0F7), onSurfaceVariant=Color(0xFF56616B),
    surfaceContainer=Color(0xFFF0F3F8), surfaceContainerLow=Color(0xFFF5F7FB),
    surfaceContainerHigh=Color(0xFFE6ECF3), surfaceContainerHighest=Color(0xFFDDE5EF),
    surfaceContainerLowest=Color.White, surfaceTint=Color(0xFF00658A),
    outline=Color(0xFF788590), outlineVariant=Color(0xFFDCE3EB),
    error=Color(0xFFB32638), onError=Color.White,
    errorContainer=Color(0xFFFFE8EC), onErrorContainer=Color(0xFF842338),
    inverseSurface=Color(0xFF29323A), inverseOnSurface=Color(0xFFF0F4F8), inversePrimary=Color(0xFF8ED8FF)
)
private val NightColors = darkColorScheme(
    primary=Color(0xFF8ED8FF), onPrimary=Color(0xFF00364B),
    primaryContainer=Color(0xFF124C65), onPrimaryContainer=Color(0xFFC3EAFF),
    secondary=Color(0xFFB6C9D9), onSecondary=Color(0xFF233744),
    secondaryContainer=Color(0xFF304551), onSecondaryContainer=Color(0xFFD4E6F4),
    tertiary=Color(0xFFD0BEFF), onTertiary=Color(0xFF39245F),
    tertiaryContainer=Color(0xFF4F3C75), onTertiaryContainer=Color(0xFFEADDFF),
    background=Color(0xFF11171D), onBackground=Color(0xFFE3EAF0),
    surface=Color(0xFF192129), onSurface=Color(0xFFE3EAF0),
    surfaceVariant=Color(0xFF2A343F), onSurfaceVariant=Color(0xFFAFBEC9),
    surfaceContainer=Color(0xFF202A34), surfaceContainerLow=Color(0xFF18212A),
    surfaceContainerHigh=Color(0xFF2C3743), surfaceContainerHighest=Color(0xFF35414E),
    surfaceContainerLowest=Color(0xFF0D1319), surfaceTint=Color(0xFF8ED8FF),
    outline=Color(0xFF8899A6), outlineVariant=Color(0xFF394853),
    error=Color(0xFFFFB1BE), onError=Color(0xFF660D25),
    errorContainer=Color(0xFF622334), onErrorContainer=Color(0xFFFFD9E0)
)

/** Color identifies a function family; large surfaces remain quiet and neutral. */
@Composable
fun featureColor(icon: String): Color {
    val dark=isSystemInDarkTheme()
    return when(icon) {
        "folder","library","move" -> if(dark) Color(0xFFD1BDFF) else Color(0xFF7251AC)
        "download","tasks","check","storage" -> if(dark) Color(0xFF89DDB6) else Color(0xFF19734F)
        "bookmark","clock","calendar" -> if(dark) Color(0xFFF5D18B) else Color(0xFF8A5B13)
        "image","share","export" -> if(dark) Color(0xFF8ED8FF) else Color(0xFF00759D)
        "trash","error","logout" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
}
private val KeyeTypography = Typography(
    headlineMedium = TextStyle(fontFamily=FontFamily.SansSerif, fontWeight=FontWeight.SemiBold, fontSize=26.sp, lineHeight=34.sp),
    headlineSmall = TextStyle(fontFamily=FontFamily.SansSerif, fontWeight=FontWeight.SemiBold, fontSize=23.sp, lineHeight=31.sp),
    titleLarge = TextStyle(fontFamily=FontFamily.SansSerif, fontWeight=FontWeight.SemiBold, fontSize=21.sp, lineHeight=29.sp),
    titleMedium = TextStyle(fontFamily=FontFamily.SansSerif, fontWeight=FontWeight.Medium, fontSize=16.sp, lineHeight=24.sp),
    titleSmall = TextStyle(fontFamily=FontFamily.SansSerif, fontWeight=FontWeight.Medium, fontSize=14.sp, lineHeight=21.sp),
    bodyLarge = TextStyle(fontFamily=FontFamily.SansSerif, fontSize=16.sp, lineHeight=25.sp),
    bodyMedium = TextStyle(fontFamily=FontFamily.SansSerif, fontSize=14.sp, lineHeight=22.sp),
    bodySmall = TextStyle(fontFamily=FontFamily.SansSerif, fontSize=12.sp, lineHeight=19.sp),
    labelLarge = TextStyle(fontFamily=FontFamily.SansSerif, fontWeight=FontWeight.Medium, fontSize=14.sp, lineHeight=20.sp),
    labelMedium = TextStyle(fontFamily=FontFamily.SansSerif, fontWeight=FontWeight.Medium, fontSize=12.sp, lineHeight=18.sp),
    labelSmall = TextStyle(fontFamily=FontFamily.SansSerif, fontWeight=FontWeight.Medium, fontSize=10.sp, lineHeight=15.sp)
)
@Composable
fun KeyeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) NightColors else DayColors,
        typography = KeyeTypography,
        shapes = Shapes(extraSmall=RoundedCornerShape(8.dp), small=RoundedCornerShape(12.dp),
            medium=RoundedCornerShape(18.dp), large=RoundedCornerShape(24.dp), extraLarge=RoundedCornerShape(30.dp)),
        content = content)
}
