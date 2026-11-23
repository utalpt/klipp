package se.gendut.klipp.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.opengl.GLES30
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Klipp Lab: MotionCam RAW played in real time, straight from the .mcraw – phase 1 of RAW in the timeline (a test
 * screen, Settings → MCRAW test → Play). Each frame is decoded (three threads, each with its own handle on the file)
 * and developed on the GPU at the screen's size, the quick way (each 2×2 CFA block one pixel, as the quick develop):
 * no file is made. The sound plays from the file too and is the clock: a frame late for it is dropped, one early waits.
 * EI and white balance are live (uniforms of the develop); the output curve (S-Log3, or Rec.709 to look at) restarts
 * the picture. [stats] says what the phone keeps up with: frames shown and dropped per second, decode and draw times.
 */
class McrawPlayer17u(private val ctx: Context, private val src: Uri) {
    private val lib = RawLib.of(ctx, src)
    // ---- set by the screen, read by the player's thread
    @Volatile var playing = false
    @Volatile var ei = 800
    @Volatile var temp = 0
    @Volatile var tint = 0
    /** Seek here (0..1 of the clip), -1 = none. */
    @Volatile var seekTo = -1f

    // ---- read by the screen
    @Volatile var position = 0f
        private set
    @Volatile var stats = "Opening…"
        private set
    @Volatile var durationSec = 0.0
        private set

    private var thread: Thread? = null
    /** Each start is a generation: a thread of an older one stops (only one may draw on the surface). */
    @Volatile private var gen = 0
    private val lock = Any()

    /** Starts on [surface] ([sw] × [sh] pixels) developing into [outKey] (an [OpenDrt.Input] key). */
    fun start(surface: android.view.Surface, sw: Int, sh: Int, outKey: String) = synchronized(lock) {
        stop0()
        val my = ++gen
        thread = Thread({
            runCatching { run(surface, sw, sh, outKey, my) }.onFailure {
                if (gen == my) stats = "Stopped: ${it.message ?: it}"
                android.util.Log.e("KlippMcraw", "player", it)
            }
        }, "mcraw-player-$my").apply { start() }
    }

    fun stop() = synchronized(lock) { stop0() }

    private fun stop0() {
        gen++
        thread?.let { it.interrupt(); it.join(3000) }
        thread = null
    }

    private class Frame(val index: Int, val buf: ByteBuffer, val meta: JSONObject?)

    /** Decoding threads from frame [from] on: thread t decodes from + t, from + t + R, … into its own queue. */
    private inner class Readers(from: Int, n: Int, bytes: Int, val free: ArrayBlockingQueue<ByteBuffer>) {
        val r = 3
        val start = from
        val queues = List(r) { ArrayBlockingQueue<Frame>(1) }
        @Volatile var stop = false
        @Volatile var decodeNs = 0L
        val threads = List(r) { t ->
            Thread({
                val rs = lib.openSource(ctx, src)
                val h = rs?.h ?: 0L
                var held: ByteBuffer? = null
                try {
                    var i = from + t
                    while (i < n && !stop) {
                        val b = free.take(); held = b
                        val t0 = System.nanoTime()
                        val m = if (h != 0L) lib.decodeTo(h, i, b) else null
                        decodeNs += System.nanoTime() - t0
                        queues[t].put(Frame(i, b, m?.let { JSONObject(it) })); held = null
                        i += r
                    }
                } catch (_: InterruptedException) {
                } finally {
                    held?.let { free.offer(it) } // stopped mid-frame: its buffer goes back
                    rs?.close()
                }
            }, "mcraw-play-read-$t").apply { start() }
        }

        /** The next frame in order ([k] counts from [start]); null when none came in time. */
        fun take(k: Int): Frame? = queues[(k - start) % r].poll(200, TimeUnit.MILLISECONDS)

        fun close() {
            stop = true
            threads.forEach { it.interrupt() }
            threads.forEach { it.join(2000) }
            // the buffers still queued go back
            queues.forEach { q -> q.forEach { free.offer(it.buf) }; q.clear() }
        }
    }

    private fun run(surface: android.view.Surface, sw: Int, sh: Int, outKey: String, my: Int) {
        fun running() = gen == my
        check(lib.available) { "RAW library not loaded" }
        val source = lib.openSource(ctx, src) ?: error("could not open it as RAW video")
        val h = source.h
        var track: AudioTrack? = null
        var gl: McrawConvert.Gl? = null
        var readers: Readers? = null
        try {
            val info = JSONObject(lib.info(h))
            val c = info.getJSONObject("container")
            val frames = info.getJSONArray("frames")
            val n = frames.length()
            val ff = info.getJSONObject("firstFrame")
            val w = ff.getInt("width"); val hh = ff.getInt("height")
            val t0 = frames.getLong(0)
            val times = DoubleArray(n) { (frames.getLong(it) - t0) / 1e9 }
            val fps = if (n > 1) (n - 1) / times[n - 1] else 30.0
            durationSec = times[n - 1] + 1 / fps

            // the picture's rows (MotionCam pads the frame below it), as in the develop
            val blackMax = (c.optJSONArray("blackLevel")?.let { a -> (0 until a.length()).maxOf { a.getDouble(it) } } ?: 0.0).toInt() + 1
            val bytes = w * hh * 2
            val probe = ByteBuffer.allocateDirect(bytes)
            val empty = if (lib.decodeTo(h, n / 2, probe) != null) lib.emptyBottomRows(h, blackMax) else 0
            val ch = if (empty >= hh / 20) ((hh - empty) / 16 * 16).coerceAtLeast(16) else hh

            // the picture fitted into the view
            val aspect = w / ch.toFloat()
            val (vw, vh) = if (sw / sh.toFloat() > aspect) (sh * aspect).toInt() to sh else sw to (sw / aspect).toInt()
            val vx = (sw - vw) / 2; val vy = (sh - vh) / 2

            // the sound: the whole clip in memory (a test), played from a static track that is also the clock
            var audioStart = 0.0
            var rate = 0
            val snd = runCatching { lib.audio(h) }.getOrNull()
            val aRate = info.optInt("audioRate"); val aCh = info.optInt("audioChannels")
            if (snd != null && aRate > 0 && aCh in 1..2) {
                val (pcm0, startNs) = snd
                var off = (startNs - t0) / 1e9
                var first = 0
                if (kotlin.math.abs(off) > 2) off = 0.0
                if (off < 0) { first = (-off * aRate).toInt() * aCh; off = 0.0 }
                val pcm = if (first > 0 && first < pcm0.size) pcm0.copyOfRange(first, pcm0.size) else pcm0
                if (pcm.isNotEmpty()) {
                    track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(aRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(if (aCh == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
                    track.write(pcm, 0, pcm.size)
                    audioStart = off; rate = aRate
                }
            }

            val out = OpenDrt.Input.of(outKey)
            // 8-bit: what the view shows (a TextureView); the develop itself computes in float either way
            val g = McrawConvert.Gl(surface, w, hh, ch, vw, vh, bits = 8, recordable = false)
            gl = g
            var shownEi = ei
            g.setup(c.optString("sensorArrangment", "rggb"), c, McrawConvert.colorMatrix(c, ff, out.gamut), McrawConvert.headroom(shownEi), true, out.oetf)
            var shownWb = temp to tint
            fun wb() = McrawConvert.cameraWb(c, ff, null, shownWb.let { (t, ti) -> outKey to InputEi(outKey, temp = t, tint = ti).wbGains() })
            g.wbCam = wb()

            val free = ArrayBlockingQueue<ByteBuffer>(7).apply { repeat(7) { add(ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)) } }
            var k = 0
            readers = Readers(0, n, bytes, free)

            // the clock: the sound's when there is sound, else the wall's
            var wallBase = 0.0; var wallAt = System.nanoTime()
            fun clock(): Double {
                val t = track
                if (t != null && playing) return audioStart + t.playbackHeadPosition / rate.toDouble()
                return if (playing) wallBase + (System.nanoTime() - wallAt) / 1e9 else wallBase
            }
            fun seekAudio(sec: Double) {
                val t = track ?: return
                runCatching { t.pause(); t.setPlaybackHeadPosition(((sec - audioStart).coerceAtLeast(0.0) * rate).toInt()) }
            }
            var wasPlaying = false
            var needDraw = true

            // stats over half a second
            var shown = 0; var dropped = 0; var drawNs = 0L; var lastStats = System.nanoTime()
            var decodeMark = 0L

            while (running()) {
                // seek: the readers start again at the frame, the sound at its time
                val s = seekTo
                if (s >= 0f) {
                    seekTo = -1f
                    val target = (s * (n - 1)).toInt().coerceIn(0, n - 1)
                    readers?.close()
                    readers = Readers(target, n, bytes, free); k = target
                    wallBase = times[target]; wallAt = System.nanoTime()
                    seekAudio(times[target]); if (playing) track?.play()
                    needDraw = true
                }
                if (playing != wasPlaying) {
                    wasPlaying = playing
                    if (playing) {
                        if (k >= n) { seekTo = 0f; continue }
                        wallBase = times[k.coerceAtMost(n - 1)]; wallAt = System.nanoTime()
                        seekAudio(wallBase); track?.play()
                    } else { track?.pause(); wallBase = times[(k - 1).coerceIn(0, n - 1)] }
                }
                // live settings
                if (ei != shownEi) {
                    shownEi = ei; g.setExpo(McrawConvert.headroom(shownEi)); needDraw = true
                    android.util.Log.i("KlippMcraw", "player: EI $shownEi")
                }
                if ((temp to tint) != shownWb) { shownWb = temp to tint; g.wbCam = wb(); needDraw = true }

                if (!playing && !needDraw) { Thread.sleep(15); continue }
                if (k >= n) { playing = false; Thread.sleep(15); continue }
                val rd = readers ?: break
                val f = rd.take(k) ?: continue
                k++
                if (playing) {
                    val now = clock()
                    // late by more than a frame: dropped (the next one is nearer)
                    if (f.index < n - 1 && times[f.index + 1] < now) { dropped++; free.offer(f.buf); continue }
                    // early: wait for its time
                    val wait = times[f.index] - now
                    if (wait > 0.002) Thread.sleep((wait * 1000).toLong().coerceAtMost(100))
                }
                val td = System.nanoTime()
                GLES30.glViewport(0, 0, sw, sh)
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                GLES30.glViewport(vx, vy, vw, vh)
                if (f.meta != null) g.drawFrame(f.buf, f.meta, c)
                g.swap()
                drawNs += System.nanoTime() - td
                free.offer(f.buf)
                shown++
                position = f.index / (n - 1).coerceAtLeast(1).toFloat()
                if (!playing) {
                    // paused: the frame shown is the one at the playhead; the readers start again from there next time
                    needDraw = false
                    readers?.close(); readers = Readers(k - 1, n, bytes, free); k -= 1
                    wallBase = times[k]
                }

                val now = System.nanoTime()
                if (now - lastStats > 500_000_000L) {
                    val secs = (now - lastStats) / 1e9
                    val dec = (rd.decodeNs - decodeMark).coerceAtLeast(0L)
                    val decoded = shown + dropped
                    stats = "%.0f fps shown · %.0f dropped/s · decode %.1f ms (each, 3 threads) · draw %.1f ms · %d×%d %s · EI %d %s · #%d".format(
                        shown / secs, dropped / secs, if (decoded > 0) dec / 1e6 / decoded else 0.0,
                        if (shown > 0) drawNs / 1e6 / shown else 0.0, vw, vh, if (track != null) "· sound clock" else "· no sound",
                        shownEi, out.name, my)
                    shown = 0; dropped = 0; drawNs = 0; lastStats = now
                    decodeMark = rd.decodeNs
                }
            }
        } finally {
            readers?.close()
            runCatching { track?.stop() }; track?.release()
            runCatching { gl?.release() }
            source.close()
        }
    }
}
