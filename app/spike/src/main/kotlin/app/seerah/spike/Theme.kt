package app.seerah.spike

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Colours from the Penpot tokens (athar/day); UI type in IBM Plex Sans Arabic. */
object Tokens {
    val page = Color(0xFFFAF7F0)
    val base = Color(0xFFF4EFE6)
    val sunken = Color(0xFFF1EBDF)
    val selected = Color(0xFFF1E6C8)
    val ink = Color(0xFF203D32)
    val inkSecondary = Color(0xFF526253)
    val muted = Color(0xFF667368)
    val gold = Color(0xFFC9A646)
    val goldMark = Color(0xFFB7963A)
    val onGold = Color(0xFF17251F)
    val border = Color(0xFFDDCFB3)
}

@Composable
fun SeerahTheme(content: @Composable () -> Unit) {
    val assets = LocalContext.current.assets
    val plex = FontFamily(
        Font("fonts/IBMPlexSansArabic-Regular.ttf", assets, FontWeight.Normal),
        Font("fonts/IBMPlexSansArabic-SemiBold.ttf", assets, FontWeight.SemiBold),
    )
    val amiri = FontFamily(Font("fonts/Amiri-Bold.ttf", assets, FontWeight.Bold))
    val base = Typography()
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Tokens.gold,
            onPrimary = Tokens.onGold,
            primaryContainer = Tokens.selected,
            onPrimaryContainer = Tokens.ink,
            secondaryContainer = Tokens.selected,
            onSecondaryContainer = Tokens.ink,
            surface = Tokens.page,
            onSurface = Tokens.ink,
            surfaceVariant = Tokens.sunken,
            onSurfaceVariant = Tokens.inkSecondary,
            outline = Tokens.border,
            outlineVariant = Tokens.border,
            background = Tokens.page,
        ),
        typography = Typography(
            titleLarge = TextStyle(fontFamily = amiri, fontWeight = FontWeight.Bold, fontSize = 22.sp),
            titleSmall = base.titleSmall.copy(fontFamily = plex, fontWeight = FontWeight.SemiBold),
            bodyMedium = base.bodyMedium.copy(fontFamily = plex),
            labelLarge = base.labelLarge.copy(fontFamily = plex, fontWeight = FontWeight.SemiBold),
            labelMedium = base.labelMedium.copy(fontFamily = plex),
            labelSmall = base.labelSmall.copy(fontFamily = plex),
        ),
        content = content,
    )
}
