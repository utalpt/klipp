package se.gendut.klipp

import android.app.Activity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Klipp 17U: interface size and full screen (settings → Appearance).
 * - [scale] shrinks the whole interface (dp and text) by one factor, set in [KlippTheme]: 1 = Klipp's size.
 * - [immersive] hides the status and navigation bars in the whole app; a swipe from the edge shows them for a moment.
 */
object Ui17u {
    val SCALES = listOf(1f, 0.9f, 0.8f)
    var scale by mutableStateOf(0.9f)
    var immersive by mutableStateOf(true)

    /** Hides or shows the system bars as [immersive] says (on start, on focus, after the full-screen viewer). */
    fun applyBars(activity: Activity?) {
        val window = activity?.window ?: return
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (immersive) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else ctl.show(WindowInsetsCompat.Type.systemBars())
    }
}

/**
 * Klipp 17U: the screen's safe area. In full screen the top keeps clear of the camera hole only (its own outline,
 * from the display cutout's bounding rects), not of the whole band the status bar used: on a punch-hole phone that
 * band is mostly empty and the top bar moves up into it. Otherwise (bars shown, or no outline known) the usual
 * safeDrawing padding.
 */
@androidx.compose.runtime.Composable
fun Modifier.safeArea17u(): Modifier {
    val safe = WindowInsets.safeDrawing
    if (!Ui17u.immersive) return this.windowInsetsPadding(safe)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val view = androidx.compose.ui.platform.LocalView.current
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    // read so the outline is looked up again once the insets arrive (and after a rotation)
    val cutTop = WindowInsets.displayCutout.getTop(density)
    val holeBottom = androidx.compose.runtime.remember(cutTop, config.orientation, view) {
        if (cutTop == 0) 0
        else if (android.os.Build.VERSION.SDK_INT >= 28)
            view.rootWindowInsets?.displayCutout?.boundingRects?.filter { it.top < cutTop }?.maxOfOrNull { it.bottom } ?: cutTop
        else cutTop
    }
    val sides = WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
    // (a few dp under the hole so nothing touches it)
    val top = if (holeBottom > 0) with(density) { holeBottom.toDp() } + 4.dp else 0.dp
    return this.windowInsetsPadding(safe.only(sides)).padding(top = top)
}
