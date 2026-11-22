package se.gendut.klipp.engine

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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

    private val DEFAULT_OUT = OpenDrt.Input.SLOG3.key

    /**
     * The develop of [src] (named [name]) at [ei] into [out] (an [OpenDrt.Input] key): the same source at the same
     * settings is the same file.
     */
    fun fileFor(c: Context, src: Uri, name: String, ei: Int, out: String = DEFAULT_OUT, wb: String = ""): File {
        val base = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_.-]"), "_").take(60)
        val key = src.toString() + "|ei" + ei + (if (out != DEFAULT_OUT) "|$out" else "") + (if (wb.isNotEmpty()) "|wb$wb" else "")
        val h = key.hashCode().toUInt().toString(16)
        return File(dir(c), "${base}_ei${ei}${if (out != DEFAULT_OUT) "_$out" else ""}${if (wb.isNotEmpty()) "_wb" else ""}_$h.mp4")
    }

    /** The white balance settings as a key (empty = as shot). */
    private fun wbKey(camWb: FloatArray?, live: Pair<String, FloatArray>?): String {
        fun f(a: FloatArray?) = a?.takeIf { v -> v.any { kotlin.math.abs(it - 1f) > 1e-4f } }?.joinToString(",") { "%.4f".format(java.util.Locale.ROOT, it) }
        return listOfNotNull(f(camWb), live?.let { l -> f(l.second)?.let { "${l.first}:$it" } }).joinToString("|")
    }

    /** Develops [src] (or reuses its develop). Blocking: call off the main thread. */
    /** [ei0] ≤ 0: Auto ([McrawConvert.autoEi], 800 when the file can't be measured). */
    /** [camWb], [liveWb]: white balance (see McrawConvert.convert). */
    fun develop(c: Context, src: Uri, name: String, ei0: Int, out: String = DEFAULT_OUT,
                camWb: FloatArray? = null, liveWb: Pair<String, FloatArray>? = null, progress: (Float) -> Unit): McrawConvert.Result {
        val auto = ei0 <= 0
        val ei = if (auto) McrawConvert.autoEi(c, src) ?: 800 else ei0
        val f = fileFor(c, src, name, ei, out, wbKey(camWb, liveWb))
        if (f.exists() && f.length() > 0) {
            if (infoOfFile(c, f) == null) record(c, f, src, name, ei, out, auto, camWb)
            return McrawConvert.Result(Uri.fromFile(f), "Reused ${f.name}", "")
        }
        val r = McrawConvert.convert(c, src, outFile = f, ei = ei, out = out, camWb = camWb, liveWb = liveWb, progress = progress)
        if (r.uri != null) { record(c, f, src, name, ei, out, auto, r.camWb); revision++ }
        return r
    }

    @Synchronized private fun record(c: Context, f: File, src: Uri, name: String, ei: Int, out: String, auto: Boolean, camWb: FloatArray?) {
        val o = runCatching { JSONObject(index(c).readText()) }.getOrDefault(JSONObject())
        val e = JSONObject().put("source", src.toString()).put("name", name).put("ei", ei).put("auto", auto).put("out", out).put("made", System.currentTimeMillis())
        camWb?.takeIf { v -> v.any { kotlin.math.abs(it - 1f) > 1e-4f } }?.let { e.put("wb", org.json.JSONArray(it.map { x -> x.toDouble() })) }
        o.put(f.name, e)
        runCatching { index(c).writeText(o.toString()) }
    }

    /** What a develop was made from. */
    data class Info(val source: Uri, val name: String, val ei: Int, val out: String, val auto: Boolean = false,
                    /** Camera white balance over as shot baked into the develop (null = as shot). */
                    val camWb: List<Float>? = null)

    /** The develop behind [uri] (a clip's file), or null when it is not one. Reads a small file: off the main thread. */
    fun infoOf(c: Context, uri: Uri?): Info? {
        if (uri == null || uri.scheme != "file") return null
        val f = File(uri.path ?: return null)
        if (f.parentFile?.absolutePath != dir(c).absolutePath) return null
        return infoOfFile(c, f)
    }

    private fun infoOfFile(c: Context, f: File): Info? {
        val o = runCatching { JSONObject(index(c).readText()).optJSONObject(f.name) }.getOrNull() ?: return null
        val src = o.optString("source").takeIf { it.isNotEmpty() } ?: return null
        val wb = o.optJSONArray("wb")?.takeIf { it.length() == 3 }?.let { a -> List(3) { a.getDouble(it).toFloat() } }
        return Info(Uri.parse(src), o.optString("name", f.name), o.optInt("ei", 800), o.optString("out", DEFAULT_OUT), o.optBoolean("auto", false), wb)
    }

    /**
     * Links every media item of the open project that uses [old] to [new] (set by the open project). The develop
     * left behind is removed by [clean] once no project uses it.
     */
    @Volatile var relink: ((old: Uri, new: Uri) -> Unit)? = null

    /** The develop running in the background for the RAW settings: "name: 42 %", null = none. */
    var working by androidx.compose.runtime.mutableStateOf<String?>(null)
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /**
     * Develops [info]'s source again at [ei] into [out] in the background and, when done, puts it in the place of
     * [old] in the project; [done] (on the main thread) gets the new file, or null and the reason.
     */
    fun redevelop(c: Context, old: Uri, info: Info, ei: Int, out: String, liveWb: Pair<String, FloatArray>? = null, done: (Uri?, String) -> Unit) {
        if (working != null) return
        val app = c.applicationContext
        working = "${info.name}: 0 %"
        bg.launch {
            val r = runCatching { develop(app, info.source, info.name, ei, out, info.camWb?.toFloatArray(), liveWb) { p -> working = "${info.name}: ${(p * 100).toInt()} %" } }
                .getOrElse { McrawConvert.Result(null, it.message ?: it.toString(), "") }
            runCatching { File(app.getExternalFilesDir(null), "mcraw_convert.txt").writeText(r.summary + "\n" + r.log) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                working = null
                if (r.uri != null && r.uri != old) relink?.invoke(old, r.uri)
                done(r.uri, r.summary)
            }
        }
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
            "MCRAW ${gb(u.mcraw)}, other ${gb(u.other)}) – over your $lim GB. Settings → Storage to clear."
    }
}
