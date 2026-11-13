package app.seerah

import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class RightToLeftTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "en")
    fun content_isLaidOutRightToLeft_onAnEnglishDevice() {
        var direction: LayoutDirection? = null

        composeRule.setContent {
            RightToLeft { direction = LocalLayoutDirection.current }
        }

        assertEquals(LayoutDirection.Rtl, direction)
    }
}
