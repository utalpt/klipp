package se.gendut.klipp.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue

/**
 * Klipp Lab: develops a MotionCam .mcraw into an S-Log3 / S-Gamut3 clip (10-bit HEVC, BT.2020 tags, full range) that
 * Klipp then edits like any log clip (input "Sony S-Log3 / S-Gamut3", EI and grade as usual).
 *
 * Per frame, on the GPU: the frame's own black level, the lens shading map (MotionCam doesn't bake it in), the as-shot
 * white balance, a bilinear demosaic, the camera's ForwardMatrix1 (ColorMatrix1 when there is none) to XYZ, Bradford
 * D50 → D65, S-Gamut3 and the S-Log3 curve. The sensor's clip lands [HEADROOM] stops over 18 % grey (as S-Log3 at
 * EI 800); clipped highlights stay neutral. The frames are decoded on a second thread while the GPU and the encoder
 * work. First version: picture only (no sound yet), the file's own size when the encoder takes it.
 */
object McrawConvert {
    /** Stops of the sensor's clip over 18 % grey (EI 800 in Klipp's EI terms). */
    const val HEADROOM = 5f

    class Result(val uri: Uri?, val summary: String, val log: String)

    fun convert(ctx: Context, src: Uri, progress: (Float) -> Unit): Result {
        val log = StringBuilder()
        fun line(s: String) { log.append(s).append('\n') }
        if (!Mcraw.available) return Result(null, "MCRAW library not loaded", "")
        val name = runCatching {
            ctx.contentResolver.query(src, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: "mcraw"
        val pfd = ctx.contentResolver.openFileDescriptor(src, "r") ?: return Result(null, "Could not open the file", "")
        val h = Mcraw.open(pfd.fd)
        if (h == 0L) { pfd.close(); return Result(null, "Not an MCRAW file", "") }
        var outUri: Uri? = null
        try {
            val info = JSONObject(Mcraw.info(h))
            val c = info.getJSONObject("container")
            val frames = info.getJSONArray("frames")
            val n = frames.length()
            val ff = info.getJSONObject("firstFrame")
            val w = ff.getInt("width"); val hh = ff.getInt("height")
            val t0ns = frames.getLong(0)
            val fps = if (n > 1) (n - 1) / ((frames.getLong(n - 1) - t0ns) / 1e9) else 30.0
            line("convert: $name  ${w}x$hh  $n frames  ${"%.3f".format(fps)} fps")

            // the encoder's size: the file's own, else the largest of the same shape it takes
            val (ow, oh, codecName) = pickSize(w, hh, fps) ?: return Result(null, "No HEVC 10-bit encoder for this size", log.toString())
            line("encoder: $codecName  ${ow}x$oh")
            val bitrate = (ow.toLong() * oh * fps * 0.25).toLong().coerceIn(20_000_000L, 400_000_000L).toInt()
            line("bitrate: ${bitrate / 1_000_000} Mb/s")

            val cfa = c.optString("sensorArrangment", "rggb")
            val camToOut = colorMatrix(c, ff)
            line("cfa $cfa  camera → S-Gamut3: ${camToOut.joinToString { "%.4f".format(it) }}")

            // output file in Movies/Klipp Lab
            val base = name.substringBeforeLast('.')
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "${base}_SLog3.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Klipp Lab")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            outUri = ctx.contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: return Result(null, "Could not create the output file", log.toString())
            val outPfd = ctx.contentResolver.openFileDescriptor(outUri, "rw") ?: return Result(null, "Could not open the output file", log.toString())

            val tStart = System.nanoTime()
            val bytes = w * hh * 2
            // decoded frames, in order, from a second thread (three buffers in flight)
            class Frame(val index: Int, val buf: ByteBuffer, val meta: JSONObject?)
            val free = ArrayBlockingQueue<ByteBuffer>(3).apply { repeat(3) { add(ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)) } }
            val ready = ArrayBlockingQueue<Frame>(3)
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val reader = Thread {
                val rp = ctx.contentResolver.openFileDescriptor(src, "r")
                val rh = rp?.let { Mcraw.open(it.fd) } ?: 0L
                try {
                    for (i in 0 until n) {
                        if (stop.get()) break
                        val b = free.take()
                        val m = if (rh != 0L) Mcraw.decodeTo(rh, i, b) else null
                        ready.put(Frame(i, b, m?.let { JSONObject(it) }))
                    }
                } catch (_: InterruptedException) {
                } catch (e: Throwable) {
                    android.util.Log.e("KlippMcraw", "reader", e)
                } finally { if (rh != 0L) Mcraw.close(rh); rp?.close() }
            }.apply { this.name = "mcraw-read"; start() }

            var encoded = 0
            var failed = 0
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, ow, oh).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    setFloat(MediaFormat.KEY_FRAME_RATE, fps.toFloat())
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    // log picture in BT.2020 primaries, full range; no transfer tag (S-Log3 has none in the standard set)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_FULL)
                }
                val codec = MediaCodec.createByCodecName(codecName)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = codec.createInputSurface()
                codec.start()
                val muxer = MediaMuxer(outPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var track = -1
                val info2 = MediaCodec.BufferInfo()
                fun drain(end: Boolean) {
                    while (true) {
                        val i = codec.dequeueOutputBuffer(info2, if (end) 10_000 else 0)
                        when {
                            i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                            i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                            i >= 0 -> {
                                val ob = codec.getOutputBuffer(i)!!
                                if (info2.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info2.size = 0
                                if (info2.size > 0 && track >= 0) { ob.position(info2.offset); ob.limit(info2.offset + info2.size); muxer.writeSampleData(track, ob, info2) }
                                codec.releaseOutputBuffer(i, false)
                                if (info2.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                            }
                        }
                    }
                }

                val gl = Gl(inputSurface, w, hh, ow, oh)
                try {
                    gl.setup(cfa, c, camToOut)
                    for (k in 0 until n) {
                        val f = ready.poll(20, java.util.concurrent.TimeUnit.SECONDS) ?: error("reading the frames stopped at $k")
                        if (f.meta == null) { failed++; free.put(f.buf); continue }
                        gl.drawFrame(f.buf, f.meta, c)
                        free.put(f.buf)
                        gl.present((frames.getLong(f.index) - t0ns))
                        encoded++
                        drain(false)
                        if (k % 4 == 0) progress(k / n.toFloat())
                    }
                } finally { gl.release() }
                codec.signalEndOfInputStream()
                drain(true)
                codec.stop(); codec.release()
                if (track >= 0) muxer.stop()
                muxer.release()
            } finally {
                stop.set(true)
                reader.interrupt(); reader.join(2000)
                outPfd.close()
            }
            val secs = (System.nanoTime() - tStart) / 1e9
            ctx.contentResolver.update(outUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            progress(1f)
            line("encoded $encoded frames (failed $failed) in ${"%.1f".format(secs)} s → ${"%.1f".format(encoded / secs)} fps (${"%.2f".format(encoded / secs / fps)}× real time)")
            val summary = "Converted $encoded frames in ${"%.1f".format(secs)} s (${"%.1f".format(encoded / secs)} fps, ${"%.2f".format(encoded / secs / fps)}× real time)\n" +
                "Movies/Klipp Lab/${base}_SLog3.mp4 – ${ow}×$oh 10-bit HEVC, no sound yet. In Klipp: input Sony S-Log3 / S-Gamut3, sensor clip at EI 800."
            return Result(outUri, summary, log.toString())
        } catch (e: Throwable) {
            line("error: $e")
            outUri?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
            return Result(null, "Conversion failed: ${e.message}", log.toString())
        } finally {
            Mcraw.close(h); pfd.close()
        }
    }

    /** The encoder and size: HEVC Main10 with surface input, the file's size or the largest of the same shape. */
    private fun pickSize(w: Int, h: Int, fps: Double): Triple<Int, Int, String>? {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { ci ->
            ci.isEncoder && ci.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } && !ci.isSoftwareOnly &&
                ci.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
        }
        for (ci in infos) {
            val vc = ci.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).videoCapabilities
            var tw = w
            while (tw >= 1280) {
                val th = ((tw.toLong() * h / w) / 16 * 16).toInt()
                val tw16 = tw / 16 * 16
                if (vc.isSizeSupported(tw16, th) && (vc.areSizeAndRateSupported(tw16, th, fps) || vc.isSizeSupported(tw16, th))) return Triple(tw16, th, ci.name)
                tw -= 256
            }
        }
        return null
    }

    // ---- colour ----

    private fun arr(o: JSONObject, key: String): FloatArray? = o.optJSONArray(key)?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }

    /** 3×3 row-major: white-balanced camera RGB → linear S-Gamut3 (D65). */
    internal fun colorMatrix(c: JSONObject, ff: JSONObject): FloatArray {
        val bradford = floatArrayOf( // D50 → D65
            0.9555766f, -0.0230393f, 0.0631636f,
            -0.0282895f, 1.0099416f, 0.0210077f,
            0.0122982f, -0.0204830f, 1.3299098f)
        val xyzToSg3 = inv(OpenDrt.M.SGAMUT3_TO_XYZ)
        val fm = arr(c, "forwardMatrix1")
        if (fm != null && fm.size == 9 && fm.any { it != 0f }) return mul(xyzToSg3, mul(bradford, fm))
        // no forward matrix: XYZ(D65) = inverse(ColorMatrix1) · neutral, scaled so the neutral maps to Y = 1
        val cm = arr(c, "colorMatrix1") ?: return mul(xyzToSg3, OpenDrt.M.REC2020_TO_XYZ)
        val neutral = arr(ff, "asShotNeutral") ?: floatArrayOf(1f, 1f, 1f)
        val camToXyz = inv(cm)
        val d = floatArrayOf(neutral[0], 0f, 0f, 0f, neutral[1], 0f, 0f, 0f, neutral[2])
        val m = mul(camToXyz, d)
        val y = m[3] + m[4] + m[5]
        return mul(xyzToSg3, FloatArray(9) { m[it] / y })
    }

    private fun mul(a: FloatArray, b: FloatArray) = FloatArray(9) { i -> val r = i / 3; val k = i % 3; a[r * 3] * b[k] + a[r * 3 + 1] * b[3 + k] + a[r * 3 + 2] * b[6 + k] }
    private fun inv(m: FloatArray): FloatArray {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]; val e = m[4]; val f = m[5]; val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return floatArrayOf((e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det)
    }

    // ---- GPU ----

    private class Gl(surface: android.view.Surface, val w: Int, val h: Int, val ow: Int, val oh: Int) {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val context: EGLContext
        val eglSurface: EGLSurface
        var program = 0
        var rawTex = 0
        var shadeTex = 0
        var shadeW = 0; var shadeH = 0
        // the CFA: colour (0 R, 1 G, 2 B) and shading-map channel (0 R, 1 G even, 2 G odd, 3 B) at each 2×2 position
        val colorAt = IntArray(4); val shadeAt = IntArray(4)

        init {
            val v = IntArray(2); EGL14.eglInitialize(display, v, 0, v, 1)
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 10, EGL14.EGL_GREEN_SIZE, 10, EGL14.EGL_BLUE_SIZE, 10, EGL14.EGL_ALPHA_SIZE, 2,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE)
            val cfgs = arrayOfNulls<EGLConfig>(1); val num = IntArray(1)
            check(EGL14.eglChooseConfig(display, attrs, 0, cfgs, 0, 1, num, 0) && num[0] > 0) { "no 10-bit EGL config" }
            context = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            eglSurface = EGL14.eglCreateWindowSurface(display, cfgs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
        }

        fun setup(cfa: String, c: JSONObject, camToOut: FloatArray) {
            val pattern = when (cfa.lowercase()) { "bggr" -> "BGGR"; "grbg" -> "GRBG"; "gbrg" -> "GBRG"; else -> "RGGB" }
            var greens = 0
            for (p in 0 until 4) when (pattern[p]) {
                'R' -> { colorAt[p] = 0; shadeAt[p] = 0 }
                'B' -> { colorAt[p] = 2; shadeAt[p] = 3 }
                else -> { colorAt[p] = 1; shadeAt[p] = 1 + greens; greens++ }
            }
            program = link(VS, FS)
            GLES30.glUseProgram(program)
            val t = IntArray(2); GLES30.glGenTextures(2, t, 0)
            rawTex = t[0]; shadeTex = t[1]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTex)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R16UI, w, h)
            nearest()
            GLES30.glUniform1i(loc("raw"), 0)
            GLES30.glUniform1i(loc("shade"), 1)
            GLES30.glUniform4i(loc("colorAt"), colorAt[0], colorAt[1], colorAt[2], colorAt[3])
            GLES30.glUniform4i(loc("shadeAt"), shadeAt[0], shadeAt[1], shadeAt[2], shadeAt[3])
            GLES30.glUniform2i(loc("size"), w, h)
            GLES30.glUniform2f(loc("scale"), w / ow.toFloat(), h / oh.toFloat())
            GLES30.glUniformMatrix3fv(loc("camToOut"), 1, true, camToOut, 0)
            GLES30.glUniform1f(loc("expo"), 0.18f * Math.pow(2.0, HEADROOM.toDouble()).toFloat())
            GLES30.glViewport(0, 0, ow, oh)
        }

        private fun nearest() {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }

        private fun loc(n: String) = GLES30.glGetUniformLocation(program, n)

        fun drawFrame(buf: ByteBuffer, meta: JSONObject, c: JSONObject) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTex)
            buf.position(0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, buf)

            // levels: the frame's own black and white when it has them
            val black = meta.optJSONArray("dynamicBlackLevel") ?: c.optJSONArray("blackLevel") ?: JSONArray(listOf(0, 0, 0, 0))
            val bl = FloatArray(4) { black.optDouble(it.coerceAtMost(black.length() - 1), 0.0).toFloat() }
            val white = meta.optDouble("dynamicWhiteLevel", c.optDouble("whiteLevel", 1023.0)).toFloat()
            // black levels are given in the CFA's own order (as in the DNG): position p
            GLES30.glUniform4f(loc("black"), bl[0], bl[1], bl[2], bl[3])
            GLES30.glUniform1f(loc("white"), white)
            // white balance per colour from the as-shot neutral (green = 1)
            val ns = meta.optJSONArray("asShotNeutral")
            val neutral = if (ns != null && ns.length() >= 3) FloatArray(3) { ns.getDouble(it).toFloat() } else floatArrayOf(1f, 1f, 1f)
            val gains = FloatArray(3) { neutral[1] / neutral[it].coerceAtLeast(1e-4f) }
            GLES30.glUniform3f(loc("wb"), gains[0], gains[1], gains[2])
            GLES30.glUniform1f(loc("clipAt"), gains.min())

            // lens shading map: four channels R, G even, G odd, B on a coarse grid
            val lsm = meta.optJSONArray("lensShadingMap")
            if (lsm != null && lsm.length() == 4) {
                val count = lsm.getJSONArray(0).length()
                val gw = meta.optInt("lensShadingMapWidth", 0)
                val gh = meta.optInt("lensShadingMapHeight", 0)
                val (sw, sh) = if (gw > 0 && gh > 0 && gw * gh == count) gw to gh else gridOf(count, w / h.toFloat())
                val data = ByteBuffer.allocateDirect(sw * sh * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                for (i in 0 until sw * sh) for (ch in 0 until 4) data.put(lsm.getJSONArray(ch).optDouble(i, 1.0).toFloat())
                data.position(0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadeTex)
                if (sw != shadeW || sh != shadeH) {
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, sw, sh, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, null)
                    nearest(); shadeW = sw; shadeH = sh
                }
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, sw, sh, GLES30.GL_RGBA, GLES30.GL_FLOAT, data)
                GLES30.glUniform1i(loc("useShade"), 1)
                GLES30.glUniform2i(loc("grid"), sw, sh)
            } else GLES30.glUniform1i(loc("useShade"), 0)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }

        /** Grid size of [count] points whose shape is closest to the picture's. */
        private fun gridOf(count: Int, aspect: Float): Pair<Int, Int> {
            var best = count to 1
            var err = Float.MAX_VALUE
            for (gh in 1..count) if (count % gh == 0) {
                val gw = count / gh
                val e = kotlin.math.abs(gw / gh.toFloat() - aspect)
                if (e < err) { err = e; best = gw to gh }
            }
            return best
        }

        fun present(ptsNs: Long) {
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }

        private fun link(vs: String, fs: String): Int {
            fun shader(type: Int, src: String): Int {
                val s = GLES30.glCreateShader(type)
                GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
                val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "shader: " + GLES30.glGetShaderInfoLog(s) }
                return s
            }
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
            GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
            GLES30.glLinkProgram(p)
            val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "link: " + GLES30.glGetProgramInfoLog(p) }
            return p
        }
    }

    private const val EGL_RECORDABLE_ANDROID = 0x3142

    // one triangle over the whole frame; y flipped so the first row of the RAW is the top of the picture
    private const val VS = """#version 300 es
out vec2 pos;
void main() {
    vec2 v = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    pos = vec2(v.x, 1.0 - v.y);
    gl_Position = vec4(v * 2.0 - 1.0, 0.0, 1.0);
}"""

    private const val FS = """#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;
uniform usampler2D raw;
uniform highp sampler2D shade;
uniform ivec4 colorAt;
uniform ivec4 shadeAt;
uniform ivec2 size;
uniform ivec2 grid;
uniform int useShade;
uniform vec2 scale;
uniform vec4 black;
uniform float white;
uniform vec3 wb;
uniform float clipAt;
uniform mat3 camToOut;
uniform float expo;
in vec2 pos;
out vec4 color;

int at(ivec4 v, int i) { return i == 0 ? v.x : i == 1 ? v.y : i == 2 ? v.z : v.w; }
float atf(vec4 v, int i) { return i == 0 ? v.x : i == 1 ? v.y : i == 2 ? v.z : v.w; }

// the lens shading gain of map channel ch at pixel p (bilinear on the grid)
float shading(int ch, ivec2 p) {
    if (useShade == 0) return 1.0;
    vec2 g = (vec2(p) + 0.5) / vec2(size) * vec2(grid - 1);
    ivec2 g0 = clamp(ivec2(floor(g)), ivec2(0), grid - 1);
    ivec2 g1 = min(g0 + 1, grid - 1);
    vec2 f = g - vec2(g0);
    float a = atf(texelFetch(shade, g0, 0), ch), b = atf(texelFetch(shade, ivec2(g1.x, g0.y), 0), ch);
    float c = atf(texelFetch(shade, ivec2(g0.x, g1.y), 0), ch), d = atf(texelFetch(shade, g1, 0), ch);
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// white-balanced, shading-corrected value at p (0..1 = black..clip before the white balance)
float value(ivec2 p, out int col) {
    p = clamp(p, ivec2(0), size - 1);
    int k = (p.y & 1) * 2 + (p.x & 1);
    col = at(colorAt, k);
    float bl = atf(black, k);
    float v = (float(texelFetch(raw, p, 0).r) - bl) / (white - bl);
    v = clamp(v, 0.0, 1.0) * shading(at(shadeAt, k), p);
    return v * (col == 0 ? wb.r : col == 1 ? wb.g : wb.b);
}

float slog3(float x) {
    return x >= 0.01125 ? (420.0 + log((x + 0.01) / 0.19) / log(10.0) * 261.5) / 1023.0
                        : (x * (171.2102946929 - 95.0) / 0.01125 + 95.0) / 1023.0;
}

void main() {
    ivec2 p = ivec2(pos * vec2(size));
    if (scale != vec2(1.0)) p = ivec2((floor(pos * vec2(size) / scale) + 0.5) * scale);
    p = clamp(p, ivec2(0), size - 1);
    // bilinear demosaic: each colour is the mean of that colour in the 3×3 around (the centre alone when it has it)
    vec3 sum = vec3(0.0);
    vec3 cnt = vec3(0.0);
    int cc;
    float cv = value(p, cc);
    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
        if (dx == 0 && dy == 0) continue;
        int c;
        float v = value(p + ivec2(dx, dy), c);
        if (c == cc) continue;
        if (c == 0) { sum.r += v; cnt.r += 1.0; } else if (c == 1) { sum.g += v; cnt.g += 1.0; } else { sum.b += v; cnt.b += 1.0; }
    }
    vec3 rgb = sum / max(cnt, vec3(1.0));
    if (cc == 0) rgb.r = cv; else if (cc == 1) rgb.g = cv; else rgb.b = cv;
    // clipped highlights stay neutral: no channel above the clip of the least-gained colour (with the lens shading
    // gain at this point, which lifts the clip towards the corners)
    rgb = min(rgb, vec3(clipAt * shading(1, p)));
    vec3 lin = max(camToOut * rgb, vec3(0.0)) * expo;
    color = vec4(slog3(lin.r), slog3(lin.g), slog3(lin.b), 1.0);
}"""
}
