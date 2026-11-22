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
        status("Developing $name to S-Log3…")
        val r = withContext(Dispatchers.Default) {
            // (the status is Compose state: setting it from the conversion's thread is fine)
            se.gendut.klipp.engine.McrawStore17u.develop(ctx, u, name, McrawFolder17u.ei(ctx)) { p -> status("Developing $name to S-Log3… ${(p * 100).toInt()} %") }
        }
        runCatching { java.io.File(ctx.getExternalFilesDir(null), "mcraw_convert.txt").writeText(r.summary + "\n" + r.log) }
        if (r.uri != null) out += r.uri
        else { status("$name: ${r.summary}"); kotlinx.coroutines.delay(2500) }
    }
    return out
}
