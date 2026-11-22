package io.github.usernamealreadytakensht.cameras

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CamerasTheme {
                MapScreen()
            }
        }
    }
}

@Composable
private fun CamerasTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // minSdk 29, dynamic colours need 31: older devices get the default Material palette.
    val colors = when {
        android.os.Build.VERSION.SDK_INT < 31 ->
            if (isSystemInDarkTheme()) androidx.compose.material3.darkColorScheme()
            else androidx.compose.material3.lightColorScheme()
        isSystemInDarkTheme() -> dynamicDarkColorScheme(context)
        else -> dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
