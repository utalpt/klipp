package se.gendut.klipp

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Klipp Lab: the picked files with every MotionCam .mcraw developed into an S-Log3 / S-Gamut3.Cine clip in its place, in
 * the same order – into the app's own storage (McrawStore17u), at the picker's EI; the .mcraw stays the source. [status] shows the progress. A file that fails to convert
 * is left out, with the reason shown for a moment.
 */
suspend fun importMcraw17u(ctx: Context, picked: List<Uri>,
                           /** Klipp Lab: each file as soon as it is ready (the others still developing), in order. */
                           onEach: ((List<Uri>) -> Unit)? = null,
                           /** Klipp Lab: Cinematic RAW (.CRAW) goes through the same GPU develop, with these settings (the dialog's) */
                           craw: se.gendut.klipp.engine.CrawSettings? = null,
                           status: (String) -> Unit): List<Uri> {
    val names = withContext(Dispatchers.IO) {
        picked.map { u ->
            runCatching {
                ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull().orEmpty()
        }
    }
    // .CRAW: CFA takes are developed here (GPU); RGB-cell ones stay with CrawImport (CPU), as they came
    val gpuCraw = withContext(Dispatchers.IO) {
        picked.mapIndexed { i, u -> craw != null && names[i].endsWith(".craw", ignoreCase = true) && !se.gendut.klipp.engine.Craw17u.isRgb(ctx, u) }
    }
    // a DNG folder (PhotonCamera's raw video, CinemaDNG) is developed like a .mcraw
    val dngSeq = withContext(Dispatchers.IO) { picked.map { se.gendut.klipp.engine.DngSeq17u.isSeq(ctx, it) } }
    if (names.none { it.endsWith(".mcraw", ignoreCase = true) } && gpuCraw.none { it } && dngSeq.none { it }) { onEach?.invoke(picked); return picked }
    // proxies wait while the develops run (they share the reads, the GPU and the encoder)
    val store = se.gendut.klipp.engine.McrawStore17u
    store.importing = true
    se.gendut.klipp.engine.Proxies.hold(true)
    try { return importAll(ctx, picked, names, gpuCraw, dngSeq, craw, onEach, status) } finally {
        store.importing = false
        if (store.drafts == 0) se.gendut.klipp.engine.Proxies.hold(false)
        // (a placeholder left by a develop that failed: tried again now)
        withContext(Dispatchers.Main) { store.kick?.invoke() }
    }
}

private suspend fun importAll(ctx: Context, picked: List<Uri>, names: List<String>, gpuCraw: List<Boolean>, dngSeq: List<Boolean>,
                              craw: se.gendut.klipp.engine.CrawSettings?, onEach: ((List<Uri>) -> Unit)?, status: (String) -> Unit): List<Uri> {
    val out = ArrayList<Uri>()
    // files ready but not handed to [onEach] yet (the ones that need no develop, until the next .mcraw)
    val pending = ArrayList<Uri>()
    // the first clip goes in at once, the next ones together every ~10 s: each import redraws the timeline and the
    // project, and one at a time kept the screen busy
    var flushed = false
    var lastFlush = System.nanoTime()
    fun flush() { if (pending.isNotEmpty()) { onEach?.invoke(pending.toList()); pending.clear(); flushed = true; lastFlush = System.nanoTime() } }
    // Klipp Lab: every RAW clip goes on the timeline at once as a placeholder (its middle frame, its length, silent
    // sound), and each develop takes its placeholder's place when ready - the editor is usable from the first second,
    // as in Unmcrawesome. (When a placeholder can't be made - a .CRAW, an odd file - the import goes as before.)
    val raw = picked.indices.filter { names[it].endsWith(".mcraw", ignoreCase = true) || dngSeq[it] }
    if (onEach != null && raw.isNotEmpty() && gpuCraw.none { it }) {
        status("Preparing ${raw.size} RAW clip${if (raw.size == 1) "" else "s"}…")
        val ph = withContext(Dispatchers.IO) {
            raw.associateWith { i ->
                se.gendut.klipp.engine.Placeholder17u.make(ctx, picked[i], names[i], McrawFolder17u.quick(ctx))
                    ?.also { se.gendut.klipp.engine.McrawStore17u.notePlaceholder(ctx, it, picked[i], names[i], McrawFolder17u.ei(ctx), McrawFolder17u.out(ctx)) }
            }
        }
        if (ph.values.all { it != null }) {
            val first = picked.mapIndexed { i, u -> ph[i] ?: u }
            onEach(first)
            val store = se.gendut.klipp.engine.McrawStore17u
            for ((k, i) in raw.withIndex()) {
                val u = picked[i]; val name = names[i]; val p = ph[i]!!
                val left = raw.size - k
                val outKey = McrawFolder17u.out(ctx)
                val ei = McrawFolder17u.ei(ctx)
                val quick = McrawFolder17u.quick(ctx)
                val outName = se.gendut.klipp.engine.OpenDrt.Input.of(outKey).label
                status("Developing $name to $outName… ($left left)")
                val r = withContext(Dispatchers.Default) {
                    store.develop(ctx, u, name, ei, outKey, draft = quick) { pr -> status("Developing $name to $outName${if (quick) " (quick)" else ""}… ${(pr * 100).toInt()} % ($left left)") }
                }
                runCatching { java.io.File(ctx.getExternalFilesDir(null), "mcraw_convert.txt").writeText(r.summary + "\n" + r.log) }
                // the placeholder must be in the project before it can be replaced (the import of it runs on its own)
                var waited = 0
                while (waited < 20_000 && withContext(Dispatchers.Main) { store.has?.invoke(p) } == false) { kotlinx.coroutines.delay(100); waited += 100 }
                // (not developed: the placeholder stays – it is noted, and developed again once the import is done; Klipp 0.22)
                if (r.uri != null) withContext(Dispatchers.Main) { store.relink?.invoke(p, r.uri) }
                if (r.uri == null) { status("$name: ${r.summary}"); kotlinx.coroutines.delay(2500) }
                if (dngSeq[i] && r.uri != null) {
                    val c = withContext(Dispatchers.IO) { se.gendut.klipp.engine.DngSeq17u.contents(ctx, u) }
                    if (c != null && c.missing * 20 > c.frames.size) {
                        status("$name: ${c.missing} of ${c.frames.size} frames missing in the recording (the camera couldn't keep up) - repeated, so it plays in jerks")
                        kotlinx.coroutines.delay(4500)
                    }
                }
            }
            return first
        }
        // (some couldn't be made: the ones that were go, and the import runs as before)
        withContext(Dispatchers.IO) { ph.values.forEach { it?.path?.let { f -> java.io.File(f).delete() } } }
    }
    for ((i, u) in picked.withIndex()) {
        val name = names[i]
        val isCraw = gpuCraw[i]
        if (!name.endsWith(".mcraw", ignoreCase = true) && !isCraw && !dngSeq[i]) { out += u; pending += u; continue }
        val left = picked.size - i
        val outKey = if (isCraw && craw != null) craw.log.key else McrawFolder17u.out(ctx)
        val ei = if (isCraw && craw != null) craw.ei else McrawFolder17u.ei(ctx)
        val quick = McrawFolder17u.quick(ctx)
        val outName = se.gendut.klipp.engine.OpenDrt.Input.of(outKey).label
        status("Developing $name to $outName… ($left left)")
        val r = withContext(Dispatchers.Default) {
            // (the status is Compose state: setting it from the conversion's thread is fine)
            se.gendut.klipp.engine.McrawStore17u.develop(ctx, u, name, ei, outKey, draft = quick) { p -> status("Developing $name to $outName${if (quick) " (quick)" else ""}… ${(p * 100).toInt()} % ($left left)") }
        }
        runCatching { java.io.File(ctx.getExternalFilesDir(null), "mcraw_convert.txt").writeText(r.summary + "\n" + r.log) }
        // its proxy right away (playing the 4K 60 fps develop itself freezes the picture)
        // (a quick develop is half size: light enough to play itself; its full develop gets the proxy)
        if (r.uri != null) {
            out += r.uri; pending += r.uri
            if (!flushed || System.nanoTime() - lastFlush > 10_000_000_000L) flush()
            if (!quick) se.gendut.klipp.engine.Proxies.request(ctx, r.uri)
        }
        else { status("$name: ${r.summary}"); kotlinx.coroutines.delay(2500) }
        // Klipp Lab: a DNG folder with frames the camera dropped (they are repeated, so the clip keeps its length and
        // sound) says so - it plays in jerks, and that comes from the recording, not the develop
        if (dngSeq[i] && r.uri != null) {
            val c = withContext(Dispatchers.IO) { se.gendut.klipp.engine.DngSeq17u.contents(ctx, u) }
            if (c != null && c.missing * 20 > c.frames.size) {
                status("$name: ${c.missing} of ${c.frames.size} frames missing in the recording (the camera couldn't keep up) - repeated, so it plays in jerks")
                kotlinx.coroutines.delay(4500)
            }
        }
    }
    flush()
    return out
}
