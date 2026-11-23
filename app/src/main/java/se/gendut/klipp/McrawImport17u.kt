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
suspend fun importMcraw17u(ctx: Context, picked: List<Uri>, status: (String) -> Unit): List<Uri> {
    val names = withContext(Dispatchers.IO) {
        picked.map { u ->
            runCatching {
                ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull().orEmpty()
        }
    }
    if (names.none { it.endsWith(".mcraw", ignoreCase = true) }) return picked
    val out = ArrayList<Uri>()
    for ((i, u) in picked.withIndex()) {
        val name = names[i]
        if (!name.endsWith(".mcraw", ignoreCase = true)) { out += u; continue }
        val outKey = McrawFolder17u.out(ctx)
        val quick = McrawFolder17u.quick(ctx)
        val outName = se.gendut.klipp.engine.OpenDrt.Input.of(outKey).label
        status("Developing $name to $outName…")
        val r = withContext(Dispatchers.Default) {
            // (the status is Compose state: setting it from the conversion's thread is fine)
            se.gendut.klipp.engine.McrawStore17u.develop(ctx, u, name, McrawFolder17u.ei(ctx), outKey, draft = quick) { p -> status("Developing $name to $outName${if (quick) " (quick)" else ""}… ${(p * 100).toInt()} %") }
        }
        runCatching { java.io.File(ctx.getExternalFilesDir(null), "mcraw_convert.txt").writeText(r.summary + "\n" + r.log) }
        // its proxy right away (playing the 4K 60 fps develop itself freezes the picture)
        // (a quick develop is half size: light enough to play itself; its full develop gets the proxy)
        if (r.uri != null) { out += r.uri; if (!quick) se.gendut.klipp.engine.Proxies.request(ctx, r.uri) }
        else { status("$name: ${r.summary}"); kotlinx.coroutines.delay(2500) }
    }
    return out
}
