package se.gendut.klipp.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Klipp Lab: a folder of DNGs as a RAW video – PhotonCamera's raw video ("DNG (folder)": the frames RAW_00001.dng … in
 * a hidden .dng folder, the sound beside it as RAW_MIC.flac) and CinemaDNG folders. Each frame is read by the app's own
 * DNG reader (src/main/cpp/dng17u_jni.cpp) and developed on the GPU like MotionCam RAW (McrawConvert): levels, white
 * balance, the file's lens shading maps, demosaic, colour, log curve. The frames are taken in the order of the number
 * in their names; a number that is missing (a frame the camera dropped) repeats the frame before it, so the clip keeps
 * its length and its sound stays in sync. The rate is the files' FrameRate tag (30 when they have none).
 */
internal object DngSeq17u : RawLib {
    private val loadError: Throwable? = runCatching { System.loadLibrary("klipp_dng") }.exceptionOrNull()
    override val available get() = loadError == null

    @JvmStatic private external fun nativeInfo(fd: Int): String?
    @JvmStatic private external fun nativeDecodeTo(fd: Int, out: ByteBuffer): String?

    private class Seq(val ctx: Context, val frames: List<Uri>, val sound: Uri?, val info: String)
    private val open = ConcurrentHashMap<Long, Seq>()
    private val ids = AtomicLong(1)

    /** A folder (a document of a picked tree) that holds DNGs itself or in a ".dng" folder inside it. */
    fun isSeq(ctx: Context, uri: Uri): Boolean =
        runCatching { ctx.contentResolver.getType(uri) == DocumentsContract.Document.MIME_TYPE_DIR }.getOrDefault(false)

    private class Child(val uri: Uri, val name: String, val dir: Boolean, val size: Long)

    private fun children(ctx: Context, dir: Uri): List<Child> {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(dir, DocumentsContract.getDocumentId(dir))
        val out = ArrayList<Child>()
        runCatching {
            ctx.contentResolver.query(kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    out += Child(DocumentsContract.buildDocumentUriUsingTree(dir, c.getString(0)), name,
                        c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, c.getLong(3))
                }
            }
        }
        return out
    }

    /** What a folder holds: its DNG frames (in their names' number order, gaps filled), and its sound file. */
    class Contents(val frames: List<Uri>, val sound: Uri?, val count: Int, val missing: Int, val bytes: Long)

    fun contents(ctx: Context, dir: Uri): Contents? {
        val top = children(ctx, dir)
        val dngDir = top.firstOrNull { it.dir && it.name.equals(".dng", true) }
        val files = (if (dngDir != null) children(ctx, dngDir.uri) else top).filter { !it.dir && it.name.endsWith(".dng", true) }
        if (files.isEmpty()) return null
        val sound = top.firstOrNull { !it.dir && it.name.substringAfterLast('.').lowercase() in setOf("flac", "wav", "m4a", "aac", "mp3", "ogg") }?.uri
        fun num(n: String) = Regex("(\\d+)(?!.*\\d)").find(n.substringBeforeLast('.'))?.value?.toLongOrNull()
        val numbered = files.mapNotNull { f -> num(f.name)?.let { it to f } }
        val frames = ArrayList<Uri>()
        var missing = 0
        if (numbered.size == files.size) {
            val sorted = numbered.sortedBy { it.first }
            var prev: Long? = null
            for ((n, f) in sorted) {
                // a dropped frame repeats the one before it (at most a second's worth: a big gap is a different take)
                if (prev != null && n > prev + 1 && n - prev <= 60) repeat((n - prev - 1).toInt()) { frames += frames.last(); missing++ }
                frames += f.uri; prev = n
            }
        } else files.sortedBy { it.name }.forEach { frames += it.uri }
        return Contents(frames, sound, files.size, missing, files.sumOf { it.size })
    }

    /** Opens the folder [dir] as a clip; 0 when it can't be read (no DNGs, or a DNG this reader can't develop). */
    fun openSeq(ctx: Context, dir: Uri): Long {
        if (!available) return 0
        val c = contents(ctx, dir) ?: return 0
        val first = runCatching { ctx.contentResolver.openFileDescriptor(c.frames.first(), "r")?.use { nativeInfo(it.fd) } }.getOrNull() ?: return 0
        val fi = JSONObject(first)
        val fps = fi.optDouble("frameRate", 30.0).takeIf { it in 1.0..1000.0 } ?: 30.0
        val frames = JSONArray()
        for (i in c.frames.indices) frames.put((i * 1e9 / fps).toLong())
        val (rate, channels) = c.sound?.let { soundFormat(ctx, it) } ?: (0 to 0)
        val container = JSONObject()
        for (k in listOf("blackLevel", "whiteLevel", "sensorArrangment", "colorMatrix1", "colorMatrix2", "forwardMatrix1", "forwardMatrix2",
                "calibrationMatrix1", "calibrationMatrix2")) fi.opt(k)?.let { container.put(k, it) }
        val info = JSONObject().put("container", container).put("frames", frames)
            .put("firstFrame", JSONObject().put("width", fi.getInt("width")).put("height", fi.getInt("height"))
                .put("asShotNeutral", fi.optJSONArray("asShotNeutral") ?: JSONArray(listOf(1, 1, 1))))
            .put("audioRate", rate).put("audioChannels", channels).put("dng", JSONObject().put("bits", fi.optInt("bits"))
                .put("missing", c.missing).put("frameRate", fps))
        val h = ids.getAndIncrement()
        open[h] = Seq(ctx.applicationContext, c.frames, c.sound, info.toString())
        return h
    }

    override fun open(fd: Int): Long = 0  // a folder is opened with [openSeq]
    override fun close(h: Long) { open.remove(h) }
    override fun info(h: Long): String = open[h]?.info ?: error("DNG folder not open")
    override fun decodeTo(h: Long, index: Int, out: ByteBuffer): String? {
        val s = open[h] ?: return null
        val u = s.frames.getOrNull(index) ?: return null
        return runCatching { s.ctx.contentResolver.openFileDescriptor(u, "r")?.use { nativeDecodeTo(it.fd, out) } }.getOrNull()
    }
    override fun emptyBottomRows(h: Long, limit: Int) = 0

    /** The sound file's rate and channels; (0, 0) when it has no sound track. */
    private fun soundFormat(ctx: Context, uri: Uri): Pair<Int, Int> = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            val i = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return@runCatching 0 to 0
            val f = ex.getTrackFormat(i)
            f.getInteger(MediaFormat.KEY_SAMPLE_RATE) to f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } finally { ex.release() }
    }.getOrDefault(0 to 0)

    /** All the sound, decoded to interleaved 16-bit (FLAC, WAV, AAC… through the phone's decoders); it starts with the first frame. */
    override fun audio(h: Long): Pair<ShortArray, Long>? {
        val s = open[h] ?: return null
        val uri = s.sound ?: return null
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(s.ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            val pcm = java.io.ByteArrayOutputStream()
            if (mime == MediaFormat.MIMETYPE_AUDIO_RAW) {
                // WAV: the samples as they are (16-bit; other depths are left out)
                val buf = ByteBuffer.allocate(1 shl 16)
                while (true) { val n = ex.readSampleData(buf, 0); if (n < 0) break; pcm.write(buf.array(), 0, n); buf.clear(); ex.advance() }
            } else {
                val c = MediaCodec.createDecoderByType(mime); codec = c
                fmt.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                c.configure(fmt, null, null, 0); c.start()
                val info = MediaCodec.BufferInfo()
                var inDone = false
                while (true) {
                    if (!inDone) {
                        val ii = c.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val ib = c.getInputBuffer(ii)!!
                            val n = ex.readSampleData(ib, 0)
                            if (n < 0) { c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val oi = c.dequeueOutputBuffer(info, 10_000)
                    if (oi >= 0) {
                        val ob = c.getOutputBuffer(oi)!!
                        val bytes = ByteArray(info.size); ob.position(info.offset); ob.get(bytes)
                        pcm.write(bytes)
                        c.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            val b = ByteBuffer.wrap(pcm.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val out = ShortArray(b.remaining()); b.get(out)
            return if (out.isEmpty()) null else out to 0L
        } catch (e: Throwable) {
            android.util.Log.e("KlippDng", "sound", e)
            return null
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            ex.release()
        }
    }

    /** A picker card's preview (the middle frame, each 2×2 block one pixel, white balance and a plain gamma) and what the clip is. */
    fun thumb(ctx: Context, dir: Uri): Mcraw.Thumb? {
        val h = openSeq(ctx, dir)
        if (h == 0L) return null
        try {
            val info = JSONObject(info(h))
            val n = info.getJSONArray("frames").length()
            val ff = info.getJSONObject("firstFrame")
            val w = ff.getInt("width"); val hh = ff.getInt("height")
            val fps = info.getJSONObject("dng").optDouble("frameRate", 30.0)
            val c = info.getJSONObject("container")
            val buf = ByteBuffer.allocateDirect(w * hh * 2).order(ByteOrder.nativeOrder())
            val meta = decodeTo(h, n / 2, buf)?.let { JSONObject(it) }
            var bmp: Bitmap? = null
            if (meta != null) {
                val sb = buf.asShortBuffer()
                val black = c.optJSONArray("blackLevel")?.optDouble(0, 0.0) ?: 0.0
                val white = c.optDouble("whiteLevel", 65535.0)
                val ns = meta.optJSONArray("asShotNeutral")
                val neutral = DoubleArray(3) { ns?.optDouble(it, 1.0) ?: 1.0 }
                val pat = c.optString("sensorArrangment", "rggb")
                val pw = 320; val ph = (pw.toLong() * hh / w).toInt().coerceAtLeast(1)
                val px = IntArray(pw * ph)
                val rgb = DoubleArray(3)
                for (y in 0 until ph) for (x in 0 until pw) {
                    val bx = (x * (w / 2) / pw) * 2; val by = (y * (hh / 2) / ph) * 2
                    rgb.fill(0.0)
                    for (k in 0 until 4) {
                        val v = ((sb.get((by + k / 2) * w + bx + k % 2).toInt() and 0xFFFF) - black) / (white - black)
                        when (pat[k]) { 'r' -> rgb[0] += v; 'b' -> rgb[2] += v; else -> rgb[1] += v / 2 }
                    }
                    var col = 0xFF shl 24
                    for (i in 0..2) {
                        val l = (rgb[i] / neutral[i] * 4).coerceIn(0.0, 1.0)
                        col = col or ((Math.pow(l, 1 / 2.2) * 255).toInt() shl (16 - 8 * i))
                    }
                    px[y * pw + x] = col
                }
                bmp = Bitmap.createBitmap(px, pw, ph, Bitmap.Config.ARGB_8888)
            }
            return Mcraw.Thumb(bmp, w, hh, fps, n / fps)
        } catch (_: Throwable) {
            return null
        } finally { close(h) }
    }
}
