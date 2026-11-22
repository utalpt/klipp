package se.gendut.klipp.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * Klipp Lab: MotionCam .mcraw files, first step – a test that opens a file, reads its metadata, decodes every frame
 * (timed, on one and on four threads) and draws a quick preview of the middle frame, to see how fast the phone reads
 * MCRAW before it is built into the import. The decoding is MotionCam's own decoder library (motioncam-decoder,
 * Apache-2.0), see src/main/cpp/mcraw_jni.cpp.
 */
object Mcraw {
    private val loadError: Throwable? = runCatching { System.loadLibrary("klipp_mcraw") }.exceptionOrNull()
    val available get() = loadError == null

    @JvmStatic private external fun nativeOpen(fd: Int): Long
    @JvmStatic private external fun nativeClose(h: Long)
    @JvmStatic private external fun nativeInfo(h: Long): String
    @JvmStatic private external fun nativeDecode(h: Long, index: Int): Long
    @JvmStatic private external fun nativeLastBytes(h: Long): Long
    @JvmStatic private external fun nativePreview(h: Long, out: IntArray, w: Int, h2: Int): Boolean
    @JvmStatic private external fun nativeDecodeTo(h: Long, index: Int, out: java.nio.ByteBuffer): String?
    @JvmStatic private external fun nativeLastLitRow(h: Long): Int
    @JvmStatic private external fun nativeEmptyBottomRows(h: Long, limit: Int): Int
    @JvmStatic private external fun nativeLoadAudio(h: Long): Long
    @JvmStatic private external fun nativeAudioStart(h: Long): Long
    @JvmStatic private external fun nativeAudioCopy(h: Long, out: ShortArray)

    /** All the file's sound, interleaved 16-bit, and the time (ns, the frames' clock) of its first sample; null when none. */
    internal fun audio(h: Long): Pair<ShortArray, Long>? {
        val n = nativeLoadAudio(h)
        if (n <= 0 || n > Int.MAX_VALUE) return null
        val out = ShortArray(n.toInt())
        nativeAudioCopy(h, out)
        return out to nativeAudioStart(h)
    }

    // for McrawConvert
    internal fun open(fd: Int): Long = nativeOpen(fd)
    internal fun close(h: Long) = nativeClose(h)
    internal fun info(h: Long): String = nativeInfo(h)
    internal fun decodeTo(h: Long, index: Int, out: java.nio.ByteBuffer): String? = nativeDecodeTo(h, index, out)
    /** Padding rows at the bottom of the frame last decoded with [decodeTo] (all at or under [limit]). */
    internal fun emptyBottomRows(h: Long, limit: Int): Int = nativeEmptyBottomRows(h, limit)

    class Probe(val summary: String, val log: String, val preview: Bitmap?, val logFile: File?)

    /** A picker card's preview (the middle frame, padding rows cut off) and what the file is. */
    class Thumb(val bitmap: Bitmap?, val width: Int, val height: Int, val fps: Double, val seconds: Double)

    fun thumb(ctx: Context, uri: Uri): Thumb? {
        if (!available) return null
        val pfd = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
        val h = nativeOpen(pfd.fd)
        if (h == 0L) { pfd.close(); return null }
        try {
            val info = JSONObject(nativeInfo(h))
            val frames = info.optJSONArray("frames") ?: return null
            val n = frames.length()
            if (n == 0) return null
            val ff = info.optJSONObject("firstFrame") ?: JSONObject()
            val w = ff.optInt("width"); var hh = ff.optInt("height")
            val dur = if (n > 1) (frames.getLong(n - 1) - frames.getLong(0)) / 1e9 else 0.0
            val fps = if (dur > 0) (n - 1) / dur else 0.0
            var bmp: Bitmap? = null
            if (nativeDecode(h, n / 2) >= 0 && w > 0 && hh > 0) {
                val black = info.optJSONObject("container")?.optJSONArray("blackLevel")
                val limit = (black?.let { a -> (0 until a.length()).maxOf { a.getDouble(it) } } ?: 0.0).toInt() + 1
                val empty = nativeEmptyBottomRows(h, limit)
                val ch = if (empty >= hh / 20) hh - empty else hh
                val pw = 320; val ph = (pw.toLong() * hh / w).toInt().coerceAtLeast(1)
                val px = IntArray(pw * ph)
                if (nativePreview(h, px, pw, ph)) {
                    val full = Bitmap.createBitmap(px, pw, ph, Bitmap.Config.ARGB_8888)
                    bmp = Bitmap.createBitmap(full, 0, 0, pw, (ph.toLong() * ch / hh).toInt().coerceIn(1, ph))
                }
                hh = ch
            }
            return Thumb(bmp, w, hh, fps, dur + if (fps > 0) 1 / fps else 0.0)
        } catch (_: Throwable) {
            return null
        } finally { nativeClose(h); pfd.close() }
    }

    /** Runs the test on [uri]; [progress] gets 0..1. Blocking: call off the main thread. */
    fun probe(ctx: Context, uri: Uri, progress: (Float) -> Unit): Probe {
        val log = StringBuilder()
        fun line(s: String) { log.append(s).append('\n') }
        if (!available) return Probe("MCRAW library not loaded: ${loadError?.message}", "", null, null)
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "?"
        val size = runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        line("file: $name  ${"%.1f".format(size / 1e6)} MB")
        line("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, ${Runtime.getRuntime().availableProcessors()} cores")

        fun open(): Pair<android.os.ParcelFileDescriptor, Long>? {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val h = nativeOpen(pfd.fd)
            if (h == 0L) { pfd.close(); return null }
            return pfd to h
        }

        val first = open() ?: return Probe("Could not open the file as MCRAW", log.toString(), null, writeLog(ctx, log))
        val (pfd, h) = first
        try {
            val info = JSONObject(nativeInfo(h))
            if (info.has("error")) return Probe("MCRAW error: ${info.getString("error")}", log.toString(), null, writeLog(ctx, log))
            val frames = info.getJSONArray("frames")
            val n = frames.length()
            val ff = info.optJSONObject("firstFrame") ?: JSONObject()
            val w = ff.optInt("width"); val hh = ff.optInt("height")
            val c = info.optJSONObject("container") ?: JSONObject()
            val durS = if (n > 1) (frames.getLong(n - 1) - frames.getLong(0)) / 1e9 else 0.0
            val fps = if (durS > 0) (n - 1) / durS else 0.0
            line("frames: $n  ${w}x$hh  ${"%.3f".format(fps)} fps  ${"%.2f".format(durS)} s")
            line("cfa: ${c.optString("sensorArrangment")}  black: ${c.opt("blackLevel")}  white: ${c.opt("whiteLevel")}")
            line("audio: ${info.optInt("audioRate")} Hz × ${info.optInt("audioChannels")}  gyro: ${info.optBoolean("gyro")}")
            if (durS > 0) line("data rate: ${"%.0f".format(size * 8 / durS / 1e6)} Mb/s")
            line("container metadata: ${c.toString().take(1500)}")
            line("first frame metadata: ${ff.toString().take(1500)}")

            // one thread, every frame
            val times = LongArray(n)
            var bytes = 0L
            val t0 = System.nanoTime()
            for (i in 0 until n) {
                times[i] = nativeDecode(h, i)
                if (i == 0) bytes = nativeLastBytes(h)
                if (i % 8 == 0) progress(i / n.toFloat() * 0.6f)
            }
            val wall1 = (System.nanoTime() - t0) / 1e9
            val ok = times.filter { it >= 0 }
            val failed = n - ok.size
            val avgMs = ok.average() / 1e6
            val sorted = ok.sorted()
            val p95 = if (sorted.isEmpty()) 0.0 else sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)] / 1e6
            val maxMs = (sorted.lastOrNull() ?: 0) / 1e6
            line("frame bytes: $bytes (expected ${w.toLong() * hh * 2})")
            line("1 thread: ${"%.1f".format(avgMs)} ms/frame avg, p95 ${"%.1f".format(p95)}, max ${"%.1f".format(maxMs)}  → ${"%.1f".format(n / wall1)} fps  (failed $failed)")

            // four threads, each with its own decoder, frames interleaved
            val threads = 4
            val t1 = System.nanoTime()
            val done = java.util.concurrent.atomic.AtomicInteger()
            val workers = (0 until threads).map { k ->
                Thread {
                    val o = open() ?: return@Thread
                    try {
                        var i = k
                        while (i < n) { nativeDecode(o.second, i); i += threads; if (done.incrementAndGet() % 8 == 0) progress(0.6f + done.get() / n.toFloat() * 0.35f) }
                    } finally { nativeClose(o.second); o.first.close() }
                }.apply { start() }
            }
            workers.forEach { it.join() }
            val wall4 = (System.nanoTime() - t1) / 1e9
            line("$threads threads: ${"%.1f".format(n / wall4)} fps")

            // preview of the middle frame
            val mid = n / 2
            var bmp: Bitmap? = null
            if (nativeDecode(h, mid) >= 0 && w > 0 && hh > 0) {
                line("middle frame: last row with picture ${nativeLastLitRow(h)} of ${hh - 1}")
                val pw = 960.coerceAtMost(w / 2); val ph = (pw.toLong() * hh / w).toInt().coerceAtLeast(1)
                val px = IntArray(pw * ph)
                if (nativePreview(h, px, pw, ph)) bmp = Bitmap.createBitmap(px, pw, ph, Bitmap.Config.ARGB_8888)
            }
            progress(1f)
            val summary = buildString {
                append("${w}×$hh, $n frames at ${"%.2f".format(fps)} fps\n")
                append("Decoding: ${"%.1f".format(n / wall1)} fps on 1 thread, ${"%.1f".format(n / wall4)} fps on $threads")
                if (fps > 0) append(" (needed: ${"%.0f".format(fps)})")
                if (failed > 0) append("\n$failed frames failed")
            }
            return Probe(summary, log.toString(), bmp, writeLog(ctx, log))
        } catch (e: Throwable) {
            line("error: $e")
            return Probe("MCRAW test failed: ${e.message}", log.toString(), null, writeLog(ctx, log))
        } finally {
            nativeClose(h); pfd.close()
        }
    }

    /** The log in the app's own folder: Android/data/<app>/files/mcraw_probe.txt (adb pull). */
    private fun writeLog(ctx: Context, log: StringBuilder): File? = runCatching {
        File(ctx.getExternalFilesDir(null), "mcraw_probe.txt").apply { writeText(log.toString()) }
    }.getOrNull()
}
