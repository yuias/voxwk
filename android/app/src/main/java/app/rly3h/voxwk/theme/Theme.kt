package app.rly3h.voxwk.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = BrandDarkPrimary,
    onPrimary = BrandDarkOnPrimary,
    primaryContainer = BrandDarkPrimaryContainer,
    onPrimaryContainer = BrandDarkOnPrimaryContainer,
    secondary = BrandDarkSecondary,
    onSecondary = BrandDarkOnSecondary,
    secondaryContainer = BrandDarkSecondaryContainer,
    onSecondaryContainer = BrandDarkOnSecondaryContainer,
    tertiary = BrandDarkTertiary,
    onTertiary = BrandDarkOnTertiary,
    background = BrandDarkBackground,
    onBackground = BrandDarkOnBackground,
  )

private val LightColorScheme =
  lightColorScheme(
    primary = BrandLightPrimary,
    onPrimary = BrandLightOnPrimary,
    primaryContainer = BrandLightPrimaryContainer,
    onPrimaryContainer = BrandLightOnPrimaryContainer,
    secondary = BrandLightSecondary,
    onSecondary = BrandLightOnSecondary,
    secondaryContainer = BrandLightSecondaryContainer,
    onSecondaryContainer = BrandLightOnSecondaryContainer,
    tertiary = BrandLightTertiary,
    onTertiary = BrandLightOnTertiary,
    background = BrandLightBackground,
    onBackground = BrandLightOnBackground,
  )

@Composable
fun VoxwkTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic colour is always available: minSdk is 31 (Android 12).
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, content = content)
}
