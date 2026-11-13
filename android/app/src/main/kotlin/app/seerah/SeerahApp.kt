package app.seerah

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection

/**
 * Root of the UI. For now it shows the app name; the app shell (the map with panels over it,
 * ADR 0003) replaces it as the features land.
 */
@Composable
fun SeerahApp(modifier: Modifier = Modifier) {
    RightToLeft {
        MaterialTheme {
            Surface(modifier = modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.safeDrawingPadding(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
        }
    }
}

/**
 * Lays out [content] right to left whatever the device language, because the app is
 * Arabic only (ADR 0003).
 */
@Composable
fun RightToLeft(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
}

@Preview(locale = "ar")
@Composable
private fun SeerahAppPreview() {
    SeerahApp()
}
