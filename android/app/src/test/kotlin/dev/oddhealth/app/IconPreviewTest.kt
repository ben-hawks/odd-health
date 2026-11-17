package dev.oddhealth.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the launcher icon as a circle, a rounded square, and a themed (monochrome) icon. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w480dp-h200dp-xxhdpi")
class IconPreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun icon() {
        compose.setContent {
            Row(Modifier.background(Color(0xFF303030)).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                for (shape in listOf(CircleShape, RoundedCornerShape(28.dp))) {
                    Box(Modifier.size(108.dp).clip(shape).background(colorResource(R.color.ic_launcher_background))) {
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(108.dp))
                    }
                }
                // Themed icon: launcher tints the monochrome layer.
                Box(Modifier.size(108.dp).clip(CircleShape).background(Color(0xFF1F3533))) {
                    Image(painterResource(R.drawable.ic_launcher_monochrome), null, Modifier.size(108.dp),
                        colorFilter = ColorFilter.tint(Color(0xFFA6EFE6)))
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/0-icon.png")
    }
}
