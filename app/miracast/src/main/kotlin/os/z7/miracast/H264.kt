package os.z7.miracast

/** Разбор H.264 Annex B: параметры потока для настройки декодера. */
internal object H264 {
    /** Есть ли в кадре IDR (NAL 5) — с него декодер может начать без порчи картинки. */
    fun isKeyFrame(au: ByteArray): Boolean {
        var i = 0
        while (i + 3 < au.size) {
            if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1) {
                val t = au[i + 3].toInt() and 0x1F
                if (t == 5 || t == 7) return true
                i += 3
            } else i++
        }
        return false
    }

    /** SPS и PPS (со стартовыми кодами) из кадра, если они там есть. */
    fun parameterSets(au: ByteArray): Pair<ByteArray, ByteArray>? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < au.size) {
            if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1) { starts += i + 3; i += 3 } else i++
        }
        for ((k, s) in starts.withIndex()) {
            var e = if (k + 1 < starts.size) starts[k + 1] - 3 else au.size
            while (e > s && au[e - 1].toInt() == 0) e--
            val type = au[s].toInt() and 0x1F
            val nal = byteArrayOf(0, 0, 0, 1) + au.copyOfRange(s, e)
            if (type == 7 && sps == null) sps = nal
            if (type == 8 && pps == null) pps = nal
        }
        return if (sps != null && pps != null) sps to pps else null
    }

    /** Ширина и высота из SPS (с учётом обрезки), null — если не разобрать. */
    fun size(spsWithStart: ByteArray): Pair<Int, Int>? = try {
        val r = BitReader(unescape(spsWithStart.copyOfRange(5, spsWithStart.size)))
        val profile = r.bits(8); r.bits(16); r.ue()
        var chroma = 1
        if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = r.ue(); if (chroma == 3) r.bits(1)
            r.ue(); r.ue(); r.bits(1)
            if (r.bits(1) == 1) for (n in 0 until if (chroma == 3) 12 else 8) if (r.bits(1) == 1) skipScaling(r, if (n < 6) 16 else 64)
        }
        r.ue()
        when (r.ue()) {
            0 -> r.ue()
            1 -> { r.bits(1); r.se(); r.se(); repeat(r.ue()) { r.se() } }
        }
        r.ue(); r.bits(1)
        val wMbs = r.ue() + 1
        val hMapUnits = r.ue() + 1
        val frameMbsOnly = r.bits(1)
        if (frameMbsOnly == 0) r.bits(1)
        r.bits(1)
        var w = wMbs * 16
        var h = (2 - frameMbsOnly) * hMapUnits * 16
        if (r.bits(1) == 1) {
            val cl = r.ue(); val cr = r.ue(); val ct = r.ue(); val cb = r.ue()
            val cx = if (chroma == 0) 1 else 2
            val cy = (if (chroma == 1) 2 else 1) * (2 - frameMbsOnly)
            w -= (cl + cr) * cx; h -= (ct + cb) * cy
        }
        w to h
    } catch (_: Exception) { null }

    private fun skipScaling(r: BitReader, n: Int) {
        var last = 8; var next = 8
        for (j in 0 until n) { if (next != 0) next = (last + r.se() + 256) % 256; if (next != 0) last = next }
    }

    private fun unescape(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var zeros = 0
        for (x in b) {
            if (zeros >= 2 && x.toInt() == 3) { zeros = 0; continue }
            out.write(x.toInt())
            zeros = if (x.toInt() == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitReader(private val b: ByteArray) {
        private var pos = 0
        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or ((b[pos shr 3].toInt() shr (7 - (pos and 7))) and 1); pos++ }; return v }
        fun ue(): Int { var z = 0; while (bits(1) == 0) z++; return (1 shl z) - 1 + if (z > 0) bits(z) else 0 }
        fun se(): Int { val v = ue(); return if (v and 1 == 1) (v + 1) / 2 else -(v / 2) }
    }
}

/** Настройки задержки — свойствами, чтобы подбирать на проекторе без пересборки. */
internal object Tuning {
    /** Целевая задержка звука в AudioTrack: видео декодируется дольше и должно успевать к звуку. */
    val audioDelayMs: Long get() = prop("persist.z7.mc.audio_delay_ms", 80)
    /** Предел частоты кадров, который объявляем источнику: 60 или 30. */
    val maxFps: Long get() = prop("persist.z7.mc.max_fps", 60)
    /** Размер буфера AudioTrack (не меньше системного минимума). */
    val audioBufferMs: Long get() = prop("persist.z7.mc.audio_buffer_ms", 60)
    /** Задержка показа, пока звука нет. */
    val videoOnlyLatencyMs: Long get() = prop("persist.z7.mc.video_latency_ms", 50)

    private fun prop(name: String, def: Long): Long = try {
        (Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
            .invoke(null, name, "") as String).toLongOrNull() ?: def
    } catch (_: Exception) { def }
}
