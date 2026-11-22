package se.gendut.klipp.engine

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File

/**
 * Klipp Lab: the developed MotionCam RAW clips. A .mcraw is developed once, at import, into the app's own storage
 * (filesDir/mcraw, never the gallery) and the timeline uses that file; the .mcraw stays the source. The same file at the
 * same settings reuses its develop. index.json keeps what each develop was made from (source and EI), for the RAW
 * settings to come. A develop no project uses any more (the project or the clip deleted) is removed by [clean].
 */
object McrawStore17u {
    fun dir(c: Context) = File(c.filesDir, "mcraw").apply { mkdirs() }

    /** Bumped when develops are made or removed, so the storage figures in Settings follow. */
    var revision by androidx.compose.runtime.mutableIntStateOf(0)
    private fun index(c: Context) = File(dir(c), "index.json")

    /** The develop of [src] (named [name]) at [ei]: the same source at the same settings is the same file. */
    fun fileFor(c: Context, src: Uri, name: String, ei: Int): File {
        val base = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_.-]"), "_").take(60)
        val h = (src.toString() + "|ei" + ei).hashCode().toUInt().toString(16)
        return File(dir(c), "${base}_ei${ei}_$h.mp4")
    }

    /** Develops [src] (or reuses its develop). Blocking: call off the main thread. */
    fun develop(c: Context, src: Uri, name: String, ei: Int, progress: (Float) -> Unit): McrawConvert.Result {
        val f = fileFor(c, src, name, ei)
        if (f.exists() && f.length() > 0) return McrawConvert.Result(Uri.fromFile(f), "Reused ${f.name}", "")
        val r = McrawConvert.convert(c, src, outFile = f, ei = ei, progress = progress)
        if (r.uri != null) { record(c, f, src, name, ei); revision++ }
        return r
    }

    @Synchronized private fun record(c: Context, f: File, src: Uri, name: String, ei: Int) {
        val o = runCatching { JSONObject(index(c).readText()) }.getOrDefault(JSONObject())
        o.put(f.name, JSONObject().put("source", src.toString()).put("name", name).put("ei", ei).put("made", System.currentTimeMillis()))
        runCatching { index(c).writeText(o.toString()) }
    }

    /** Deletes every develop (Settings → Clear). */
    fun clearAll(c: Context) { dir(c).listFiles()?.forEach { it.delete() }; revision++ }

    fun bytes(c: Context): Long = dir(c).listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") }?.sumOf { it.length() } ?: 0L

    /**
     * Removes the develops that no project (nor a project's saved versions) mentions any more, and leftovers of
     * develops that were stopped. [projects]: the projects' folder.
     */
    @Synchronized fun clean(c: Context, projects: File) {
        val files = dir(c).listFiles()?.filter { it.isFile } ?: return
        if (files.isEmpty()) return
        val texts = projects.walkTopDown().filter { it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".fcproj") || it.name.endsWith(".bak")) }
            .mapNotNull { runCatching { it.readText() }.getOrNull() }.toList()
        val o = runCatching { JSONObject(index(c).readText()) }.getOrDefault(JSONObject())
        var changed = false
        for (f in files) {
            if (f.name == "index.json") continue
            val stale = f.name.endsWith(".part") && System.currentTimeMillis() - f.lastModified() > 60 * 60 * 1000L
            val unused = f.name.endsWith(".mp4") && texts.none { it.contains(f.name) }
            if (stale || unused) {
                f.delete()
                if (o.has(f.name)) { o.remove(f.name); changed = true }
            }
        }
        if (changed) { runCatching { index(c).writeText(o.toString()) }; revision++ }
    }
}

/**
 * Klipp Lab: a warning when the app's own files (proxies, node cache, renders, freeze frames and reversed clips, MCRAW
 * develops) pass a size the user sets in Settings (20 GB by default; 0 = off). Shown when a project is opened.
 */
object StorageWarn17u {
    val LIMITS_GB = listOf(0, 5, 10, 20, 50)
    private fun p(c: Context) = c.getSharedPreferences("klipp17u_storage", Context.MODE_PRIVATE)
    fun limitGb(c: Context): Int = p(c).getInt("warnGb", 20)
    fun setLimitGb(c: Context, gb: Int) { p(c).edit().putInt("warnGb", gb).apply() }

    class Use(val proxies: Long, val nodes: Long, val renders: Long, val other: Long, val mcraw: Long) {
        val total get() = proxies + nodes + renders + other + mcraw
    }

    fun use(c: Context): Use {
        fun dirSize(name: String) = File(c.filesDir, name).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        return Use(Proxies.size(c), NodeCache.size(c), RenderCache.bytes(), dirSize("holds") + dirSize("reversed"), McrawStore17u.bytes(c))
    }

    /** The warning's text when the app uses more than the limit, else null. Blocking (reads the folders). */
    fun check(c: Context): String? {
        val lim = limitGb(c)
        if (lim <= 0) return null
        val u = use(c)
        if (u.total < lim * 1_000_000_000L) return null
        fun gb(b: Long) = "%.1f".format(b / 1e9)
        return "Klipp Lab is using ${gb(u.total)} GB (proxies ${gb(u.proxies)}, cache ${gb(u.nodes)}, renders ${gb(u.renders)}, " +
            "MCRAW ${gb(u.mcraw)}, other ${gb(u.other)}) – over your $lim GB. Settings → Playback to clear."
    }
}
