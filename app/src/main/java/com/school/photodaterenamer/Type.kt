package com.school.photodaterenamer

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val BarlowCondensedFontFamily = FontFamily(
    Font(
        resId = R.font.barlow_condensed_extrabold_italic,
        weight = FontWeight.ExtraBold,
        style = FontStyle.Italic
    )
)

val BeVietnamProFontFamily = FontFamily(
    Font(
        resId = R.font.be_vietnam_pro_regular,
        weight = FontWeight.Normal,
        style = FontStyle.Normal
    ),
    Font(
        resId = R.font.be_vietnam_pro_medium,
        weight = FontWeight.Medium,
        style = FontStyle.Normal
    ),
    Font(
        resId = R.font.be_vietnam_pro_medium,
        weight = FontWeight.SemiBold,
        style = FontStyle.Normal
    ),
    Font(
        resId = R.font.be_vietnam_pro_medium,
        weight = FontWeight.Bold,
        style = FontStyle.Normal
    )
)

val JetBrainsMonoFontFamily = FontFamily(
    Font(
        resId = R.font.jetbrains_mono_medium,
        weight = FontWeight.Medium,
        style = FontStyle.Normal
    ),
    Font(
        resId = R.font.jetbrains_mono_medium,
        weight = FontWeight.Bold,
        style = FontStyle.Normal
    )
)

val AppTypography = Typography(
    // Huge Jersey Watermark Numerals & Poster Splash Numbers
    displayLarge = TextStyle(
        fontFamily = BarlowCondensedFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontStyle = FontStyle.Italic,
        fontSize = 84.sp,
        lineHeight = 84.sp,
        letterSpacing = (-2.0).sp
    ),
    // App Title (32-40sp range, tight letter spacing, uppercase poster style)
    displayMedium = TextStyle(
        fontFamily = BarlowCondensedFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontStyle = FontStyle.Italic,
        fontSize = 36.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.8).sp
    ),
    displaySmall = TextStyle(
        fontFamily = BarlowCondensedFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontStyle = FontStyle.Italic,
        fontSize = 32.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.6).sp
    ),
    // Section & Card Headers
    headlineLarge = TextStyle(
        fontFamily = BarlowCondensedFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontStyle = FontStyle.Italic,
        fontSize = 28.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = BarlowCondensedFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontStyle = FontStyle.Italic,
        fontSize = 24.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.3).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = BarlowCondensedFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontStyle = FontStyle.Italic,
        fontSize = 20.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    // Titles & Button Labels
    titleLarge = TextStyle(
        fontFamily = BarlowCondensedFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontStyle = FontStyle.Italic,
        fontSize = 22.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.4.sp
    ),
    titleMedium = TextStyle(
        fontFamily = BeVietnamProFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontFamily = BeVietnamProFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    // Body Copy (Be Vietnam Pro 14-16sp)
    bodyLarge = TextStyle(
        fontFamily = BeVietnamProFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = BeVietnamProFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.15.sp
    ),
    bodySmall = TextStyle(
        fontFamily = BeVietnamProFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    // Dates, Filenames, Chips & Technical Counters (JetBrains Mono Medium)
    labelLarge = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp
    )
)
