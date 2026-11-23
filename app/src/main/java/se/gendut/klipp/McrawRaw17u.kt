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
import kotlinx.coroutines.launch
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
    // 0 = Auto
    var ei by remember(i) { mutableStateOf(if (i.auto) 0 else i.ei) }
    var out by remember(i) { mutableStateOf(i.out) }
    var msg by remember { mutableStateOf<String?>(null) }
    val working = McrawStore17u.working
    // (a .CRAW goes through the same develop: Cinematic RAW Studio's)
    val craw = i.name.endsWith(".craw", ignoreCase = true)
    // (and a folder of DNG frames: PhotonCamera's raw video, CinemaDNG)
    val dng = !craw && !i.name.endsWith(".mcraw", ignoreCase = true)
    val title = if (craw) "Cinematic RAW" else if (dng) "DNG RAW" else "MotionCam RAW"
    Text(title, color = K.OnSurfaceVar, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.help17u(title, Help17uText.RAW + "\n\n" + Help17uText.DEVELOP).padding(start = 16.dp, top = 16.dp, bottom = 2.dp))
    Text("Developed from ${i.name} at EI ${i.ei}${if (i.auto) " (Auto)" else ""} into ${OpenDrt.Input.of(i.out).label}. A change here develops the ${if (craw) ".CRAW" else if (dng) "DNG frames" else ".mcraw"} " +
        "again in the background; the clip switches when it is done and keeps its grade.",
        color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    Text("Develop at EI", color = K.OnSurfaceVar, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.help17u("Develop at EI", Help17uText.EI).padding(start = 16.dp, top = 6.dp, bottom = 4.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Klipp Lab: an EI shows at once, as a live rating on the develop (as a cinema camera's EI on S-Log3); Develop
        // then puts it into the file, from the RAW
        for (v in McrawFolder17u.EIS) ToggleChip(if (v == 0) "Auto" + (if (i.auto) " (${i.ei})" else "") else "$v", ei == v, {
            ei = v
            val g = gs.of(current.id)
            gs.set(current.id, settle(g, i.ei, i.out, if (v == 0) 0 else v))
        }, Modifier.testTag("raw:ei$v"), help = "Develop at EI" to Help17uText.EI)
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
    // the white balance set live in the clip's input goes into the develop (on the raw, before the highlights are
    // clipped neutral), and the live one goes back to as shot
    val liveWb = gs.of(current.id).ei?.takeIf { it.hasWb }
    val changed = (if (ei == 0) !i.auto else ei != i.ei || i.auto) || out != i.out || liveWb != null
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(working ?: msg ?: if (liveWb != null) "Develop puts the white balance into the develop" else if (changed) "Not developed yet" else "",
            color = K.OnSurfaceVar, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
        FlatTextButton(onClick = {
            val old = current.uri ?: return@FlatTextButton
            val ids = clips.filter { it.uri == old }.map { it.id }.ifEmpty { listOf(current.id) }
            val newEi = ei; val newOut = out
            msg = null
            McrawStore17u.redevelop(ctx, old, i, newEi, newOut, liveWb?.let { it.input to it.wbGains() }) { uri, summary ->
                if (uri == null) { msg = "Not developed: ${summary.lineSequence().firstOrNull() ?: ""}"; return@redevelop }
                msg = "Developed at ${McrawFolder17u.eiLabel(newEi)} into ${OpenDrt.Input.of(newOut).label}"
                // the clip's input follows the develop: its curve, and the EI it was shot at (the rating stays)
                gs.setAll(ids.associateWith { id -> followDevelop(gs.of(id), newEi, newOut, bakedWb = liveWb != null) })
            }
        }, enabled = changed && working == null, modifier = Modifier.testTag("raw:develop")) { Text("Develop") }
    }
}

/**
 * Klipp Lab: a grade pasted from clip [from] onto [targets] (or given to all clips): the RAW clips among them that were
 * developed otherwise get [from]'s develop - its EI (Auto stays Auto: each clip its own) and output - one after another
 * in the background, each switching when done, its input following as with Develop.
 */
fun followRawDevelop17u(ctx: android.content.Context, gs: GradeState, clips: List<ColorClip>, from: Long, targets: Collection<Long>) {
    val src = clips.firstOrNull { it.id == from }?.uri ?: return
    val ts = clips.filter { it.id in targets && it.uri != null && it.uri != src }
    if (ts.isEmpty()) return
    val app = ctx.applicationContext
    kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
        val si = withContext(Dispatchers.IO) { McrawStore17u.infoOf(app, src) } ?: return@launch
        val infos = withContext(Dispatchers.IO) {
            ts.mapNotNull { it.uri }.distinct().mapNotNull { u -> McrawStore17u.infoOf(app, u)?.let { u to it } }
        }
        if (infos.isEmpty()) return@launch
        // the exposure the source clip shows: its rating, else the EI it was developed at; a source left at Auto (no
        // rating) leaves each clip its own Auto
        val rated = gs.of(from).ei?.rated ?: 0
        val srcAuto = si.auto && rated <= 0
        val want = if (srcAuto) 0 else if (rated > 0) rated else si.ei
        // at once: each clip keeps its own develop (its curve, the EI it was developed at as "shot") and takes the
        // source's EI as its rating - the light changes live, in the preview, now
        val byUri = infos.toMap()
        gs.setAll(ts.mapNotNull { c -> val t = byUri[c.uri ?: return@mapNotNull null] ?: return@mapNotNull null
            c.id to settle(gs.of(c.id), t.ei, t.out, if (srcAuto) 0 else want) }.toMap())
        // then, behind: each clip developed again from the RAW at that EI into the source's output (highlights and noise
        // made in the raw), switching when done - the rating then equals the develop's EI: no live change left
        val jobs = infos.filter { (_, t) -> (if (srcAuto) !t.auto else t.auto || t.ei != want) || t.out != si.out }
        if (jobs.isEmpty()) return@launch
        McrawStore17u.redevelopMany(app, jobs, want, si.out) { old, new ->
            if (new == null) return@redevelopMany
            val ids = clips.filter { it.uri == old }.map { it.id }
            kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                val ni = withContext(Dispatchers.IO) { McrawStore17u.infoOf(app, new) }
                val shot = ni?.ei ?: want
                gs.setAll(ids.associateWith { id -> settle(gs.of(id), shot, si.out, if (srcAuto) 0 else want) })
            }
        }
    }
}

/** A RAW clip's grade with its input on its develop ([shot] EI, [out] curve) and [rated] as its live rating (0 = none). */
private fun settle(g: se.gendut.klipp.engine.ClipGrade, shot: Int, out: String, rated: Int): se.gendut.klipp.engine.ClipGrade {
    val f = followDevelop(g, shot, out)
    val e = (f.ei ?: g.ei ?: se.gendut.klipp.engine.InputEi()).copy(input = out, shot = shot, rated = rated)
    return f.copy(ei = e.takeIf { it.shot > 0 || it.hasWb || it.input != OpenDrt.Input.SLOG3.key })
}

/** A clip's grade with its input following a develop at [ei] into [out]: the curve, and the shot EI (the rating stays). */
private fun followDevelop(g: se.gendut.klipp.engine.ClipGrade, ei: Int, out: String, bakedWb: Boolean = false): se.gendut.klipp.engine.ClipGrade {
    val e0 = g.ei ?: se.gendut.klipp.engine.InputEi()
    // a white balance put into the develop is no longer applied live
    val e = if (bakedWb) e0.copy(temp = 0, tint = 0) else e0
    return g.copy(
        ei = e.copy(input = out, shot = if (e.shot > 0 && ei > 0) ei else e.shot).takeIf { it.shot > 0 || it.hasWb || it.input != OpenDrt.Input.SLOG3.key },
        // OpenDRT nodes read the clip's curve and gamut from their input
        nodes = g.nodes.map { n -> n.drt?.let { d -> n.copy(drt = d.copy(input = out)) } ?: n },
    )
}

/**
 * Klipp Lab: HLG from MotionCam clips without setting up OpenDRT by hand. The grades the timeline's MotionCam clips
 * ([clips]: clip id → its file) need for an HLG export: each such clip whose grade doesn't end in OpenDRT gets it – on
 * its last node when that node does nothing yet, else as a node of its own after the others – with the develop's
 * curve and gamut as its input. A clip whose last OpenDRT node can't make HLG (a film, a window, a mix under 100 %) is
 * left to the user. Empty = nothing to add. Reads the develops' index: small, but off the main thread when it can be.
 */
fun mcrawHlgGrades17u(ctx: android.content.Context, clips: List<Pair<Long, android.net.Uri?>>, gs: GradeState): Map<Long, se.gendut.klipp.engine.ClipGrade> {
    val out = LinkedHashMap<Long, se.gendut.klipp.engine.ClipGrade>()
    for ((id, uri) in clips) {
        val info = McrawStore17u.infoOf(ctx, uri) ?: continue
        val g = gs.of(id)
        if (g.hlgReady) continue
        val last = g.chain.lastOrNull { !it.isNeutral }
        if (last?.drt != null) continue
        val drt = se.gendut.klipp.engine.Drt(input = info.out)
        val tail = g.nodes.lastOrNull()
        val nodes = if (tail != null && tail.isNeutral && tail.drt == null && tail.enabled && tail.mix >= 0.999f) g.nodes.dropLast(1) + tail.copy(drt = drt)
        else g.nodes + se.gendut.klipp.engine.GradeNode((g.nodes.maxOfOrNull { it.id } ?: 0) + 1, "OpenDRT", drt = drt)
        val e = g.ei ?: se.gendut.klipp.engine.InputEi()
        out[id] = g.copy(nodes = nodes, ei = if (e.input != info.out) e.copy(input = info.out) else g.ei)
    }
    return out
}
