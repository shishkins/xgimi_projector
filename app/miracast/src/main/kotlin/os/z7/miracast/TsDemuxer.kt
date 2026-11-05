package os.z7.miracast

import java.io.ByteArrayOutputStream

/**
 * Разбор MPEG-TS из RTP-пакетов WFD: PAT → PMT → PES видео (H.264, тип 0x1B) и звука
 * (AAC ADTS, тип 0x0F). Выдаёт целые PES с PTS в микросекундах (часы 90 кГц, без переполнения).
 */
internal class TsDemuxer(private val out: Output) {

    interface Output {
        fun onVideo(accessUnit: ByteArray, ptsUs: Long)
        fun onAudio(pes: ByteArray, ptsUs: Long)
    }

    private var pmtPid = -1
    private var videoPid = -1
    private var audioPid = -1
    private val video = Pes()
    private val audio = Pes()
    private var lastPts90 = Long.MIN_VALUE
    private var ptsBase = 0L

    /** [data] — полезная нагрузка RTP: целое число TS-пакетов по 188 байт. */
    fun feed(data: ByteArray, offset: Int, length: Int) {
        var p = offset
        val end = offset + length
        while (p + TS <= end) {
            if (data[p] != SYNC) { p++; continue }
            packet(data, p)
            p += TS
        }
    }

    private fun packet(d: ByteArray, p: Int) {
        val pusi = d[p + 1].toInt() and 0x40 != 0
        val pid = ((d[p + 1].toInt() and 0x1F) shl 8) or (d[p + 2].toInt() and 0xFF)
        val afc = (d[p + 3].toInt() shr 4) and 0x3
        var s = p + 4
        if (afc == 2 || afc == 0) return // только адаптационное поле / зарезервировано
        if (afc == 3) s += 1 + (d[p + 4].toInt() and 0xFF)
        val e = p + TS
        if (s >= e) return
        when (pid) {
            0 -> if (pusi) parsePat(d, s + 1 + (d[s].toInt() and 0xFF), e)
            pmtPid -> if (pusi) parsePmt(d, s + 1 + (d[s].toInt() and 0xFF), e)
            videoPid -> video.add(d, s, e, pusi) { b, pts -> out.onVideo(b, pts) }
            audioPid -> audio.add(d, s, e, pusi) { b, pts -> out.onAudio(b, pts) }
        }
    }

    private fun parsePat(d: ByteArray, s: Int, e: Int) {
        // таблица: id(1) flags+len(2) tsid(2) ver(1) sec(1) last(1), затем программы по 4 байта
        val sectionLen = ((d[s + 1].toInt() and 0x0F) shl 8) or (d[s + 2].toInt() and 0xFF)
        var p = s + 8
        val end = minOf(e, s + 3 + sectionLen - 4)
        while (p + 4 <= end) {
            val program = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
            val pid = ((d[p + 2].toInt() and 0x1F) shl 8) or (d[p + 3].toInt() and 0xFF)
            if (program != 0) {
                if (pmtPid != pid) L.i("ts: PMT pid=$pid")
                pmtPid = pid
                return
            }
            p += 4
        }
    }

    private fun parsePmt(d: ByteArray, s: Int, e: Int) {
        val sectionLen = ((d[s + 1].toInt() and 0x0F) shl 8) or (d[s + 2].toInt() and 0xFF)
        val infoLen = ((d[s + 10].toInt() and 0x0F) shl 8) or (d[s + 11].toInt() and 0xFF)
        var p = s + 12 + infoLen
        val end = minOf(e, s + 3 + sectionLen - 4)
        while (p + 5 <= end) {
            val type = d[p].toInt() and 0xFF
            val pid = ((d[p + 1].toInt() and 0x1F) shl 8) or (d[p + 2].toInt() and 0xFF)
            val esInfo = ((d[p + 3].toInt() and 0x0F) shl 8) or (d[p + 4].toInt() and 0xFF)
            when (type) {
                0x1B -> { if (videoPid != pid) L.i("ts: H.264 pid=$pid"); videoPid = pid }
                0x0F -> { if (audioPid != pid) L.i("ts: AAC pid=$pid"); audioPid = pid }
                else -> if (pid != videoPid && pid != audioPid) L.i("ts: ignoring stream type 0x%02x pid=%d".format(type, pid))
            }
            p += 5 + esInfo
        }
    }

    /** 33-битные отсчёты 90 кГц → непрерывные микросекунды. */
    private fun unwrap(pts90: Long): Long {
        if (lastPts90 != Long.MIN_VALUE) {
            val diff = pts90 - lastPts90
            if (diff < -(1L shl 32)) ptsBase += 1L shl 33
            else if (diff > (1L shl 32)) ptsBase -= 1L shl 33
        }
        lastPts90 = pts90
        return (pts90 + ptsBase) * 100 / 9
    }

    /** Сборка одного PES из TS-пакетов: выдаётся на начале следующего (видео идёт без длины). */
    private inner class Pes {
        private val buf = ByteArrayOutputStream(256 * 1024)
        private var started = false

        fun add(d: ByteArray, s: Int, e: Int, pusi: Boolean, emit: (ByteArray, Long) -> Unit) {
            if (pusi) {
                flush(emit)
                started = true
            }
            if (started) buf.write(d, s, e - s)
        }

        private fun flush(emit: (ByteArray, Long) -> Unit) {
            if (!started || buf.size() < 9) { buf.reset(); return }
            val b = buf.toByteArray()
            buf.reset()
            if (b[0].toInt() != 0 || b[1].toInt() != 0 || b[2].toInt() != 1) return
            val flags = b[7].toInt() and 0xFF
            val headerLen = b[8].toInt() and 0xFF
            val payload = 9 + headerLen
            if (payload > b.size || flags and 0x80 == 0) return
            val pts90 = ((b[9].toLong() and 0x0E) shl 29) or
                ((b[10].toLong() and 0xFF) shl 22) or ((b[11].toLong() and 0xFE) shl 14) or
                ((b[12].toLong() and 0xFF) shl 7) or ((b[13].toLong() and 0xFE) shr 1)
            emit(b.copyOfRange(payload, b.size), unwrap(pts90))
        }
    }

    private companion object {
        const val TS = 188
        const val SYNC: Byte = 0x47
    }
}
