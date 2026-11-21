package se.gendut.klipp

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
