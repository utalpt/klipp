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
    if (names.none { it.endsWith(".mcraw", ignoreCase = true) } && gpuCraw.none { it }) { onEach?.invoke(picked); return picked }
    // proxies wait while the develops run (they share the reads, the GPU and the encoder)
    val store = se.gendut.klipp.engine.McrawStore17u
    store.importing = true
    se.gendut.klipp.engine.Proxies.hold(true)
    try { return importAll(ctx, picked, names, gpuCraw, craw, onEach, status) } finally {
        store.importing = false
        if (store.drafts == 0) se.gendut.klipp.engine.Proxies.hold(false)
    }
}

private suspend fun importAll(ctx: Context, picked: List<Uri>, names: List<String>, gpuCraw: List<Boolean>,
                              craw: se.gendut.klipp.engine.CrawSettings?, onEach: ((List<Uri>) -> Unit)?, status: (String) -> Unit): List<Uri> {
    val out = ArrayList<Uri>()
    // files ready but not handed to [onEach] yet (the ones that need no develop, until the next .mcraw)
    val pending = ArrayList<Uri>()
    // the first clip goes in at once, the next ones together every ~10 s: each import redraws the timeline and the
    // project, and one at a time kept the screen busy
    var flushed = false
    var lastFlush = System.nanoTime()
    fun flush() { if (pending.isNotEmpty()) { onEach?.invoke(pending.toList()); pending.clear(); flushed = true; lastFlush = System.nanoTime() } }
    for ((i, u) in picked.withIndex()) {
        val name = names[i]
        val isCraw = gpuCraw[i]
        if (!name.endsWith(".mcraw", ignoreCase = true) && !isCraw) { out += u; pending += u; continue }
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
    }
    flush()
    return out
}
