package se.gendut.klipp

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Klipp Lab: long-press help. A long press (held still) on a control marked with [help17u] shows what it does; a tap
 * or a drag works as before. The text shows in [Help17uHost] (one, at the app's root).
 */
object Help17u {
    var shown by mutableStateOf<Pair<String, String>?>(null)
}

fun Modifier.help17u(title: String, text: String): Modifier = this.pointerInput(title, text) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        // null = held still for the long-press time; false = lifted or moved before it (a tap or a drag: not ours)
        val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var gone = false
            while (!gone) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val ch = e.changes.firstOrNull { it.id == down.id }
                gone = ch == null || !ch.pressed || (ch.position - down.position).getDistance() > slop
            }
            false
        }
        if (early != null) return@awaitEachGesture
        Help17u.shown = title to text
        // the rest of this touch belongs to the help: no tap or drag underneath
        do {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            e.changes.forEach { it.consume() }
        } while (e.changes.any { it.pressed })
    }
}

@Composable
fun Help17uHost() {
    val h = Help17u.shown ?: return
    AlertDialog(
        onDismissRequest = { Help17u.shown = null },
        confirmButton = { FlatTextButton({ Help17u.shown = null }) { Text("OK") } },
        title = { Text(h.first) },
        text = { Text(h.second) },
    )
}

/** The help texts (English, as the MotionCam RAW screens for now). */
object Help17uText {
    const val EI = "Where the sensor's clip lands in the log file, as the EI of a cinema camera. It changes only how bright " +
        "the picture is and how much room the highlights get – nothing is lost either way, the RAW is not touched. " +
        "Higher EI = brighter picture. Auto measures the clip and picks the EI that puts its average at 18 % grey: " +
        "the one to use when you just want to import and export."
    const val OUT = "The log curve and gamut of the developed clip on the timeline (S-Log3 / S-Gamut3.Cine by default). " +
        "Pick the one your LUTs or your grading habits expect. It is not the export's format: Rec.709, HLG or PQ are " +
        "chosen when exporting, from any of these."
    const val DEVELOP = "Develops the .mcraw again with these settings, in the background (about 1.5–2× the clip's length). " +
        "The clip switches to the new develop when it is done and keeps its grade; its input and OpenDRT nodes follow " +
        "the new curve. The old develop is removed when no project uses it."
    const val RAW = "This clip is a develop of a MotionCam .mcraw. The EI and the output are baked into the develop and " +
        "can be changed here at any time (a new develop). White balance and the rated EI above are live, on top of it."
    const val TEMP = "White balance in the linear light, before the nodes, as in a raw developer. As shot = the clip as it " +
        "is. Lower K = the light was warmer = a cooler picture. Double tap: back to as shot."
    const val TINT = "Green (−) to magenta (+), in the linear light before the nodes. Double tap: back to 0."
}
