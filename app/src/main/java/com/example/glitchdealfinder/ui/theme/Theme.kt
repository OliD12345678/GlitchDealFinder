package com.example.glitchdealfinder.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun GlitchDealFinderTheme(
    content: @Composable () -> Unit,
) {
    // We force a dark theme for a cinematic TV experience
    val colorScheme = darkColorScheme(
        primary = Color(0xFF00FFCC), // Glitch Cyan
        secondary = Color(0xFFFFCC00), // Gold
        background = Color(0xFF121212), // Very Dark Gray
        surface = Color(0xFF1E1E1E), // Slightly Lighter Gray for cards
        onBackground = Color.White,
        onSurface = Color.White
    )
    
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
