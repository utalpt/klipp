package se.gendut.klipp.engine

import android.content.Context
import android.net.Uri
import java.nio.ByteBuffer

/**
 * Klipp Lab: what the RAW develop (McrawConvert, McrawPlayer17u) needs from a RAW video file – its metadata as JSON
 * (MotionCam's keys), each frame's CFA samples into a buffer, the sound. Two kinds: MotionCam's .mcraw ([Mcraw]) and
 * Cinematic RAW Studio's .CRAW ([Craw17u]).
 */
internal interface RawLib {
    val available: Boolean
    fun open(fd: Int): Long
    fun close(h: Long)
    fun info(h: Long): String
    fun decodeTo(h: Long, index: Int, out: ByteBuffer): String?
    /** Padding rows at the bottom of the frame last decoded (all at or under [limit]). */
    fun emptyBottomRows(h: Long, limit: Int): Int
    /** All the sound, interleaved 16-bit, and the time (ns, the frames' clock) of its first sample; null when none. */
    fun audio(h: Long): Pair<ShortArray, Long>?

    companion object {
        /** A folder is a DNG sequence; by the file's name: .craw is Cinematic RAW, anything else MotionCam. */
        fun of(ctx: Context, uri: Uri): RawLib = when {
            DngSeq17u.isSeq(ctx, uri) -> DngSeq17u
            isCraw(ctx, uri) -> Craw17u
            else -> McrawLib
        }

        fun isCraw(ctx: Context, uri: Uri): Boolean {
            val name = runCatching {
                ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment ?: ""
            return name.endsWith(".craw", ignoreCase = true)
        }
    }
}

/** An open RAW source: its handle, closed with the file it came from. */
internal class RawSource(val lib: RawLib, val h: Long, private val pfd: android.os.ParcelFileDescriptor?) : AutoCloseable {
    override fun close() { runCatching { lib.close(h) }; runCatching { pfd?.close() } }
}

/** Opens [uri] with this library (a file by its descriptor, a DNG folder by its frames); null when it can't be read. */
internal fun RawLib.openSource(ctx: Context, uri: Uri): RawSource? {
    if (this === DngSeq17u) return DngSeq17u.openSeq(ctx, uri).takeIf { it != 0L }?.let { RawSource(this, it, null) }
    val pfd = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
    val h = open(pfd.fd)
    if (h == 0L) { pfd.close(); return null }
    return RawSource(this, h, pfd)
}

internal object McrawLib : RawLib {
    override val available get() = Mcraw.available
    override fun open(fd: Int) = Mcraw.open(fd)
    override fun close(h: Long) = Mcraw.close(h)
    override fun info(h: Long) = Mcraw.info(h)
    override fun decodeTo(h: Long, index: Int, out: ByteBuffer) = Mcraw.decodeTo(h, index, out)
    override fun emptyBottomRows(h: Long, limit: Int) = Mcraw.emptyBottomRows(h, limit)
    override fun audio(h: Long) = Mcraw.audio(h)
}

/**
 * Klipp Lab: .CRAW frames for the GPU develop (the same as MotionCam RAW's: levels, white balance, lens shading,
 * demosaic, colour and log curve in one shader), instead of CrawImport's develop on the CPU (about 1.3 frames a second
 * at 4K). The decoding is matthew777777's Cinematic RAW Studio decoder (third_party/craw, Apache-2.0), see
 * src/main/cpp/craw17u_jni.cpp. RGB-cell takes are not CFA: [isRgb] files stay on CrawImport.
 */
internal object Craw17u : RawLib {
    private val loadError: Throwable? = runCatching { System.loadLibrary("klipp_craw") }.exceptionOrNull()
    override val available get() = loadError == null

    @JvmStatic private external fun nativeOpen(fd: Int): Long
    @JvmStatic private external fun nativeClose(h: Long)
    @JvmStatic private external fun nativeInfo(h: Long): String?
    @JvmStatic private external fun nativeDecodeTo(h: Long, index: Int, out: ByteBuffer): String?
    @JvmStatic private external fun nativeAudio(h: Long): ShortArray?

    override fun open(fd: Int): Long = runCatching { nativeOpen(fd) }.getOrDefault(0L)
    override fun close(h: Long) = nativeClose(h)
    override fun info(h: Long): String = nativeInfo(h) ?: error("CRAW metadata could not be read")
    override fun decodeTo(h: Long, index: Int, out: ByteBuffer): String? = runCatching { nativeDecodeTo(h, index, out) }.getOrNull()
    override fun emptyBottomRows(h: Long, limit: Int) = 0
    override fun audio(h: Long): Pair<ShortArray, Long>? {
        val pcm = runCatching { nativeAudio(h) }.getOrNull() ?: return null
        val t0 = runCatching { org.json.JSONObject(info(h)).getJSONArray("frames").getLong(0) }.getOrDefault(0L)
        return pcm to t0
    }

    /** RGB-cell take (or unreadable): developed by CrawImport instead. */
    fun isRgb(ctx: Context, uri: Uri): Boolean {
        if (!available) return true
        val pfd = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return true
        try {
            val h = open(pfd.fd)
            if (h == 0L) return true
            try { return org.json.JSONObject(info(h)).optBoolean("rgb", true) } catch (_: Throwable) { return true } finally { close(h) }
        } finally { pfd.close() }
    }
}
