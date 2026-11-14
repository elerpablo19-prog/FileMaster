package com.filemaster.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = RustOrange,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = SteelBlue,
    tertiary = DeepGreen,
)

private val DarkColors = darkColorScheme(
    primary = RustOrangeNight,
    onPrimary = OnPrimaryNight,
    primaryContainer = PrimaryContainerNight,
    onPrimaryContainer = OnPrimaryContainerNight,
    secondary = SteelBlueNight,
    tertiary = DeepGreenNight,
)

/**
 * Tema do app. Prioridade:
 *  1. Material You dynamic color (Android 12+) — cores extraídas do wallpaper;
 *  2. Paleta customizada (identidade "Rust Orange" do core);
 *  3. Segue modo escuro do sistema automaticamente (isSystemInDarkTheme).
 */
@Composable
fun FileMasterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color ON por padrão em API 31+; desligável para identidade fixa.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FileMasterTypography,
        content = content,
    )
}
