package me.desidubanime.tv.player

import me.desidubanime.tv.data.api.Api
import me.desidubanime.tv.data.api.Network
import okhttp3.Request

/**
 * Reads chapter markers ("Intro", "OP", "Part A", "ED", "PV") straight out of
 * a remote MKV over two tiny ranged GETs — the same markers VLC/MX Player
 * show on their seekbar. ADH's encodes carry them consistently (verified
 * live), which gives EXACT per-episode skip windows; ExoPlayer itself never
 * surfaces Matroska chapters.
 *
 *   1. head (64 KB): EBML SeekHead maps element id 0x1043A770 (Chapters) to a
 *      byte offset inside the segment.
 *   2. ranged GET around that offset: scan ChapterTimeStart / ChapterTimeEnd /
 *      ChapString atoms.
 */
object MkvChapters {

    data class Chapter(val name: String, val startMs: Long, val endMs: Long)

    /** The two windows the player cares about. */
    data class SkipWindows(val intro: Chapter?, val outro: Chapter?)

    fun fetch(url: String, headers: Map<String, String>): SkipWindows? {
        return try {
            val head = ranged(url, headers, 0, 65_535) ?: return null
            // Segment data offset: position values are relative to the first
            // byte after the Segment element's size field. NO loose fallback
            // scans — random bytes look like chapter atoms and produce garbage
            // windows at 0:00; only a SeekHead-verified Chapters element counts.
            val segData = segmentDataOffset(head) ?: return null
            val chaptersPos = seekPositionFor(head, CHAPTERS_ID) ?: return null
            val abs = segData + chaptersPos
            val body = if (abs + 16_384 <= head.size) {
                head.copyOfRange(abs.toInt(), head.size)
            } else {
                ranged(url, headers, abs, abs + 16_383) ?: return null
            }
            val chapters = fillEnds(scanChapters(body))
            if (chapters.isEmpty()) return null
            windowsOf(chapters)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Many MKVs carry only ChapterTimeStart (no ends). Derive each end from
     * the NEXT chapter's start — the last one gets +95s (enough for an ED).
     */
    private fun fillEnds(chapters: List<Chapter>): List<Chapter> {
        val sorted = chapters.sortedBy { it.startMs }
        return sorted.mapIndexed { i, c ->
            if (c.endMs > c.startMs) c
            else c.copy(endMs = sorted.getOrNull(i + 1)?.startMs ?: (c.startMs + 95_000))
        }
    }

    /**
     * Map raw chapters to skip windows. The ~90s opening SONG is the "OP"
     * chapter — "Intro" alone is usually the cold-open/recap (real content,
     * not skipped). Same for "ED"; skipping seeks past its END so a PV/
     * after-credits scene still plays. Names vary by encoder ("OP", "Opening
     * 1", "OP Start"…), so match by word; when nothing matches, fall back to
     * shape: a 60–130s chapter starting in the first 6 minutes is the OP.
     */
    private fun windowsOf(chapters: List<Chapter>): SkipWindows {
        fun findByName(rx: Regex): Chapter? = chapters.firstOrNull { rx.containsMatchIn(it.name) }
        val opRx = Regex("""(?i)\b(op|opening)\b""")
        val edRx = Regex("""(?i)\b(ed|ending|outro)\b""")
        fun songShaped(c: Chapter): Boolean = (c.endMs - c.startMs) in 60_000..130_000

        val intro = findByName(opRx)
            ?: chapters.firstOrNull { it.startMs < 6 * 60_000 && songShaped(it) && !it.name.contains("part", true) }
        val outro = findByName(edRx)
            ?: chapters.lastOrNull { it !== intro && songShaped(it) && it.startMs > 10 * 60_000 }
        return SkipWindows(intro = intro, outro = outro)
    }

    // ---- EBML plumbing ----

    private const val CHAPTERS_ID = 0x1043A770L

    private fun ranged(url: String, headers: Map<String, String>, from: Long, to: Long): ByteArray? {
        val req = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("User-Agent", headers["User-Agent"] ?: Api.USER_AGENT)
            .header("Range", "bytes=$from-$to")
            .build()
        Network.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.bytes()
        }
    }

    /** Offset of the Segment's DATA (after its id + size vint). */
    private fun segmentDataOffset(d: ByteArray): Long? {
        // Segment id = 0x18538067
        var i = indexOf(d, byteArrayOf(0x18, 0x53.toByte(), 0x80.toByte(), 0x67)) ?: return null
        i += 4
        val len = vintLength(d, i) ?: return null
        return (i + len).toLong()
    }

    /** SeekHead: find the SeekPosition for [targetId]. */
    private fun seekPositionFor(d: ByteArray, targetId: Long): Long? {
        val idBytes = byteArrayOf(
            (targetId shr 24).toByte(), (targetId shr 16).toByte(),
            (targetId shr 8).toByte(), targetId.toByte(),
        )
        // Seek entry: 0x53AB (SeekID) <len> <id bytes> ... 0x53AC (SeekPosition) <len> <pos>
        var i = 0
        while (i < d.size - 8) {
            if (d[i] == 0x53.toByte() && d[i + 1] == 0xAB.toByte()) {
                val len = (d[i + 2].toInt() and 0x7F)
                if (len == idBytes.size && regionMatches(d, i + 3, idBytes)) {
                    var j = i + 3 + len
                    // SeekPosition should follow within a few bytes.
                    while (j < minOf(d.size - 3, i + 32)) {
                        if (d[j] == 0x53.toByte() && d[j + 1] == 0xAC.toByte()) {
                            val plen = d[j + 2].toInt() and 0x7F
                            var pos = 0L
                            for (k in 0 until plen) pos = (pos shl 8) or (d[j + 3 + k].toLong() and 0xFF)
                            return pos
                        }
                        j++
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * Scan ChapterTimeStart(0x91)/TimeEnd(0x92)/ChapString(0x85) atoms, but
     * ONLY inside the Chapters element's declared byte range — scanning
     * arbitrary data produces phantom chapters (0x91/0x85 occur everywhere).
     * [d] must begin at (or contain) the Chapters element id.
     */
    private fun scanChapters(d: ByteArray): List<Chapter> {
        val idIdx = indexOf(d, byteArrayOf(0x10, 0x43, 0xA7.toByte(), 0x70)) ?: return emptyList()
        var i = idIdx + 4
        val sizeLen = vintLength(d, i) ?: return emptyList()
        var size = (d[i].toInt() and 0xFF and ((0x80 shr (sizeLen - 1)) - 1)).toLong()
        for (k in 1 until sizeLen) size = (size shl 8) or (d[i + k].toLong() and 0xFF)
        i += sizeLen
        val end = minOf(d.size, i + size.toInt())

        val out = ArrayList<Chapter>()
        var curStart = -1L
        var curEnd = -1L
        while (i < end - 2) {
            val b = d[i].toInt() and 0xFF
            val lenByte = d[i + 1].toInt() and 0xFF
            when {
                b == 0x91 && lenByte and 0x80 != 0 && lenByte and 0x7F < 9 -> {
                    val l = lenByte and 0x7F
                    if (i + 2 + l <= d.size) {
                        var ns = 0L
                        for (k in 0 until l) ns = (ns shl 8) or (d[i + 2 + k].toLong() and 0xFF)
                        curStart = ns / 1_000_000
                        i += 2 + l
                        continue
                    }
                }
                b == 0x92 && lenByte and 0x80 != 0 && lenByte and 0x7F < 9 -> {
                    val l = lenByte and 0x7F
                    if (i + 2 + l <= d.size) {
                        var ns = 0L
                        for (k in 0 until l) ns = (ns shl 8) or (d[i + 2 + k].toLong() and 0xFF)
                        curEnd = ns / 1_000_000
                        i += 2 + l
                        continue
                    }
                }
                b == 0x85 && lenByte and 0x80 != 0 && lenByte and 0x7F in 1..59 -> {
                    val l = lenByte and 0x7F
                    if (i + 2 + l <= d.size) {
                        val name = try {
                            String(d, i + 2, l, Charsets.UTF_8)
                        } catch (e: Exception) {
                            ""
                        }
                        if (name.isNotBlank() && name.all { !it.isISOControl() } && curStart >= 0) {
                            out.add(Chapter(name.trim(), curStart, curEnd))
                            curStart = -1; curEnd = -1
                        }
                        i += 2 + l
                        continue
                    }
                }
            }
            i++
        }
        return out
    }

    private fun vintLength(d: ByteArray, i: Int): Int? {
        if (i >= d.size) return null
        val b = d[i].toInt() and 0xFF
        for (l in 1..8) if (b and (0x80 shr (l - 1)) != 0) return l
        return null
    }

    private fun indexOf(d: ByteArray, pattern: ByteArray): Int? {
        outer@ for (i in 0..d.size - pattern.size) {
            for (j in pattern.indices) if (d[i + j] != pattern[j]) continue@outer
            return i
        }
        return null
    }

    private fun regionMatches(d: ByteArray, at: Int, pattern: ByteArray): Boolean {
        if (at + pattern.size > d.size) return false
        for (j in pattern.indices) if (d[at + j] != pattern[j]) return false
        return true
    }
}
