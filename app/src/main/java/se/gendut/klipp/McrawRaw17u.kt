package se.gendut.klipp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.gendut.klipp.engine.McrawStore17u
import se.gendut.klipp.engine.OpenDrt

/**
 * Klipp Lab: the RAW settings of a MotionCam clip, in Color → the clip's input (the EI chip). The clip on the timeline
 * is a develop of its .mcraw (see McrawStore17u); the EI it was developed at and its output (log curve and gamut) can
 * be changed at any time: the .mcraw is developed again in the background and the clip switches to the new develop
 * when it is done (same length, the grade stays), with the clip's input set to the new curve. White balance and the
 * rated EI above stay live, on top of the develop.
 */
@Composable
fun McrawRawSection17u(gs: GradeState, clips: List<ColorClip>, current: ColorClip) {
    val ctx = LocalContext.current
    val info by produceState<McrawStore17u.Info?>(null, current.uri, McrawStore17u.revision) {
        value = withContext(Dispatchers.IO) { McrawStore17u.infoOf(ctx, current.uri) }
    }
    val i = info ?: return
    var ei by remember(i) { mutableStateOf(i.ei) }
    var out by remember(i) { mutableStateOf(i.out) }
    var msg by remember { mutableStateOf<String?>(null) }
    val working = McrawStore17u.working
    Text("MotionCam RAW", color = K.OnSurfaceVar, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 2.dp))
    Text("Developed from ${i.name} at EI ${i.ei} into ${OpenDrt.Input.of(i.out).label}. A change here develops the .mcraw " +
        "again in the background; the clip switches when it is done and keeps its grade.",
        color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    Text("Develop at EI", color = K.OnSurfaceVar, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (v in McrawFolder17u.EIS) ToggleChip("$v", ei == v, { ei = v }, Modifier.testTag("raw:ei$v"))
    }
    val outs = OpenDrt.Input.entries.filter { it != OpenDrt.Input.REC709 }
    Box(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
        DropField("Output (log curve / gamut)", outs.map { Opt(it.key, it.label, key = it.key) }, out, "raw:out") { out = it }
    }
    // a clip developed into another output at import: its input follows the develop (once, no undo step)
    androidx.compose.runtime.LaunchedEffect(i, current.id) {
        val g = gs.of(current.id)
        if ((g.ei?.input ?: OpenDrt.Input.SLOG3.key) != i.out) gs.set(current.id, followDevelop(g, i.ei, i.out), record = false)
    }
    val changed = ei != i.ei || out != i.out
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(working ?: msg ?: if (changed) "Not developed yet" else "",
            color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
        FlatTextButton(onClick = {
            val old = current.uri ?: return@FlatTextButton
            val ids = clips.filter { it.uri == old }.map { it.id }.ifEmpty { listOf(current.id) }
            val newEi = ei; val newOut = out
            msg = null
            McrawStore17u.redevelop(ctx, old, i, newEi, newOut) { uri, summary ->
                if (uri == null) { msg = "Not developed: ${summary.lineSequence().firstOrNull() ?: ""}"; return@redevelop }
                msg = "Developed at EI $newEi into ${OpenDrt.Input.of(newOut).label}"
                // the clip's input follows the develop: its curve, and the EI it was shot at (the rating stays)
                gs.setAll(ids.associateWith { id -> followDevelop(gs.of(id), newEi, newOut) })
            }
        }, enabled = changed && working == null, modifier = Modifier.testTag("raw:develop")) { Text("Develop") }
    }
}

/** A clip's grade with its input following a develop at [ei] into [out]: the curve, and the shot EI (the rating stays). */
private fun followDevelop(g: se.gendut.klipp.engine.ClipGrade, ei: Int, out: String): se.gendut.klipp.engine.ClipGrade {
    val e = g.ei ?: se.gendut.klipp.engine.InputEi()
    return g.copy(
        ei = e.copy(input = out, shot = if (e.shot > 0) ei else 0).takeIf { it.shot > 0 || it.hasWb || it.input != OpenDrt.Input.SLOG3.key },
        // OpenDRT nodes read the clip's curve and gamut from their input
        nodes = g.nodes.map { n -> n.drt?.let { d -> n.copy(drt = d.copy(input = out)) } ?: n },
    )
}
