package se.gendut.klipp.engine

import java.io.File
import java.io.RandomAccessFile

/**
 * Klipp Lab: sets an MP4's creation and modification times (mvhd, and each track's tkhd and mdhd) to now. Media3 copies
 * the first clip's recording time into the export, and Android's media scanner takes the file's date (what galleries sort
 * by) from it - the export then sits at the original footage's date, not at the top of the gallery. Only those time
 * fields are rewritten, in place; nothing moves.
 */
internal object Mp4Date {
    /** Seconds from 1904-01-01 (the MP4 epoch) to 1970-01-01. */
    private const val EPOCH_1904 = 2_082_844_800L

    fun setNow(file: File, nowMs: Long = System.currentTimeMillis()) {
        val t = nowMs / 1000 + EPOCH_1904
        RandomAccessFile(file, "rw").use { f -> walk(f, 0, f.length(), t, 0) }
    }

    /** The boxes in [start, end): the time fields of mvhd / tkhd / mdhd, and down into moov, trak and mdia. */
    private fun walk(f: RandomAccessFile, start: Long, end: Long, t: Long, depth: Int) {
        var pos = start
        while (pos + 8 <= end) {
            f.seek(pos)
            var size = f.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also { f.readFully(it) }.toString(Charsets.US_ASCII)
            var header = 8L
            if (size == 1L) { size = f.readLong(); header = 16 }
            else if (size == 0L) size = end - pos
            if (size < header || pos + size > end) return
            when (type) {
                "moov", "trak", "mdia" -> if (depth < 4) walk(f, pos + header, pos + size, t, depth + 1)
                "mvhd", "tkhd", "mdhd" -> {
                    f.seek(pos + header)
                    val version = f.readUnsignedByte()
                    f.skipBytes(3)
                    if (version == 1) { f.writeLong(t); f.writeLong(t) } else { f.writeInt(t.toInt()); f.writeInt(t.toInt()) }
                }
            }
            pos += size
        }
    }
}
