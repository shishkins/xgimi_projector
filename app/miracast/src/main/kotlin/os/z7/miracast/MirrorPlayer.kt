package os.z7.miracast

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Приём и воспроизведение потока WFD: RTP/UDP → MPEG-TS → H.264 (аппаратный декодер на Surface)
 * и AAC (MediaCodec → AudioTrack). Видео выводится по часам звука: кадр с PTS X показывается,
 * когда динамик играет звук с тем же PTS — так нет рассинхрона, как в нашем AirPlay.
 */
internal class MirrorPlayer(private val surface: Surface, private val listener: Listener) {

    interface Listener {
        fun onVideoSize(width: Int, height: Int)
        fun onFirstFrame()
        /** Потеряны пакеты — стоит попросить ключевой кадр. */
        fun onLoss()
    }

    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private val clock = AvClock()
    private val video = VideoDecoder()
    private val audio = AudioPlayer()
    private val demux = TsDemuxer(object : TsDemuxer.Output {
        override fun onVideo(accessUnit: ByteArray, ptsUs: Long) = video.offer(accessUnit, ptsUs)
        override fun onAudio(pes: ByteArray, ptsUs: Long) = audio.offer(pes, ptsUs)
    })

    fun start() {
        running = true
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.receiveBufferSize = 4 * 1024 * 1024
        s.bind(InetSocketAddress(Wfd.RTP_PORT))
        socket = s
        Thread({ receiveLoop(s) }, "wfd-rtp").apply { priority = Thread.MAX_PRIORITY }.start()
        video.start()
        audio.start()
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        video.stop()
        audio.stop()
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(65536)
        val pkt = DatagramPacket(buf, buf.size)
        var expected = -1
        var packets = 0L
        var lost = 0L
        var bytes = 0L
        var lastReport = SystemClock.elapsedRealtime()
        var lastLossReport = 0L
        while (running) {
            try {
                s.receive(pkt)
            } catch (e: Exception) {
                if (running) L.w("rtp: ${e.message}")
                break
            }
            val n = pkt.length
            if (n < 12 || (buf[0].toInt() and 0xC0) != 0x80) continue
            val seq = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            val cc = buf[0].toInt() and 0x0F
            var off = 12 + cc * 4
            if (buf[0].toInt() and 0x10 != 0 && off + 4 <= n) {
                off += 4 + 4 * (((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF))
            }
            if (expected >= 0 && seq != expected) {
                val gap = (seq - expected) and 0xFFFF
                if (gap in 1..1000) {
                    lost += gap
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastLossReport > 1000) { lastLossReport = now; listener.onLoss() }
                }
            }
            expected = (seq + 1) and 0xFFFF
            packets++
            bytes += n
            if (off < n) demux.feed(buf, off, n - off)
            val now = SystemClock.elapsedRealtime()
            if (now - lastReport >= 5000) {
                L.i("rtp: %.1f Mbit/s, %d pkts, lost %d, video %d fps, late %d, audio %s".format(
                    bytes * 8 / 1000.0 / (now - lastReport), packets, lost,
                    video.takeFrames() * 1000 / (now - lastReport), video.takeLate(), audio.state()))
                packets = 0; lost = 0; bytes = 0; lastReport = now
            }
        }
    }

    /**
     * Общие часы: какой PTS звучит сейчас. Пока звука нет, — от первого кадра видео с запасом.
     */
    private inner class AvClock {
        @Volatile var audioPtsUs = Long.MIN_VALUE
        @Volatile var audioAtNs = 0L
        private var basePts = Long.MIN_VALUE
        private var baseNs = 0L

        fun setAudio(ptsUs: Long, atNs: Long) { audioPtsUs = ptsUs; audioAtNs = atNs }

        /** Когда показать кадр с [ptsUs] (System.nanoTime). */
        fun renderAt(ptsUs: Long): Long {
            val now = System.nanoTime()
            if (audioPtsUs != Long.MIN_VALUE && now - audioAtNs < 1_000_000_000L) {
                return audioAtNs + (ptsUs - audioPtsUs) * 1000
            }
            val at = if (basePts == Long.MIN_VALUE) Long.MIN_VALUE else baseNs + (ptsUs - basePts) * 1000
            if (at == Long.MIN_VALUE || at < now - 300_000_000L || at > now + 1_000_000_000L) {
                basePts = ptsUs
                baseNs = now + Tuning.videoOnlyLatencyMs * 1_000_000L
                return baseNs
            }
            return at
        }
    }

    /** H.264 → Surface. Декодер настраивается по первому кадру с SPS/PPS. */
    private inner class VideoDecoder {
        private val queue = LinkedBlockingQueue<Pair<ByteArray, Long>>(120)
        private var codec: MediaCodec? = null
        private var thread: Thread? = null
        @Volatile private var frames = 0L
        @Volatile private var late = 0L
        private var first = true

        fun offer(au: ByteArray, ptsUs: Long) {
            if (!queue.offer(au to ptsUs)) { queue.clear(); L.w("video: queue overflow, dropped") }
        }

        fun takeFrames(): Long = frames.also { frames = 0 }
        fun takeLate(): Long = late.also { late = 0 }

        fun start() {
            thread = Thread({ loop() }, "wfd-video").apply { priority = Thread.MAX_PRIORITY - 1; start() }
        }

        fun stop() {
            thread?.interrupt()
            thread?.join(1000)
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            codec = null
        }

        private fun loop() {
            val info = MediaCodec.BufferInfo()
            try {
                while (running) {
                    val item = queue.poll(5, TimeUnit.MILLISECONDS)
                    if (item != null) feed(item.first, item.second)
                    drain(info)
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                L.w("video: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }

        private fun feed(au: ByteArray, ptsUs: Long) {
            var c = codec
            if (c == null) {
                val (sps, pps) = H264.parameterSets(au) ?: return // ждём кадр с SPS/PPS (ключевой)
                val size = H264.size(sps)
                L.i("video: SPS ${size?.first}x${size?.second}, starting decoder")
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                    size?.first ?: 1920, size?.second ?: 1080)
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
                fmt.setInteger(MediaFormat.KEY_PRIORITY, 0) // реальное время
                c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                L.i("video: decoder ${c.name}")
                c.configure(fmt, surface, null, 0)
                c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                c.start()
                codec = c
            }
            val idx = c.dequeueInputBuffer(20_000)
            if (idx < 0) { L.w("video: no input buffer, frame dropped"); return }
            val b = c.getInputBuffer(idx)!!
            b.clear()
            if (au.size > b.capacity()) { c.queueInputBuffer(idx, 0, 0, ptsUs, 0); return }
            b.put(au)
            c.queueInputBuffer(idx, 0, au.size, ptsUs, 0)
        }

        private fun drain(info: MediaCodec.BufferInfo) {
            val c = codec ?: return
            while (true) {
                val idx = c.dequeueOutputBuffer(info, 0)
                when {
                    idx >= 0 -> {
                        val at = clock.renderAt(info.presentationTimeUs)
                        val now = System.nanoTime()
                        if (at < now - 2_000_000L) {
                            late++
                            c.releaseOutputBuffer(idx, true) // опоздал — показать сразу, не копить
                        } else {
                            c.releaseOutputBuffer(idx, at)
                        }
                        frames++
                        if (first) { first = false; listener.onFirstFrame() }
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        val w = f.getInteger(MediaFormat.KEY_WIDTH)
                        val h = f.getInteger(MediaFormat.KEY_HEIGHT)
                        val cw = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1 else w
                        val ch = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 else h
                        L.i("video: output ${cw}x$ch")
                        listener.onVideoSize(cw, ch)
                    }
                    else -> return
                }
            }
        }
    }

    /**
     * AAC (ADTS в PES) → PCM → AudioTrack. Дыры заполняются тишиной, наложения отбрасываются —
     * поэтому номер сэмпла в AudioTrack линейно соответствует PTS, и по нему идут часы видео.
     */
    private inner class AudioPlayer {
        private val queue = LinkedBlockingQueue<Pair<ByteArray, Long>>(400)
        private var codec: MediaCodec? = null
        private var track: AudioTrack? = null
        private var thread: Thread? = null
        private var rate = 48000
        private var channels = 2
        private var framesWritten = 0L
        /** PTS сэмпла номер [anchorFrame] в AudioTrack. */
        private var anchorPts = Long.MIN_VALUE
        private var anchorFrame = 0L
        private val ts = AudioTimestamp()
        private var lastClock = 0L

        fun state(): String = if (track == null) "off" else "${rate}Hz/${channels}ch"

        fun offer(pes: ByteArray, ptsUs: Long) {
            if (!queue.offer(pes to ptsUs)) { queue.clear(); L.w("audio: queue overflow, dropped") }
        }

        fun start() {
            thread = Thread({ loop() }, "wfd-audio").apply { priority = Thread.MAX_PRIORITY - 1; start() }
        }

        fun stop() {
            thread?.interrupt()
            thread?.join(1000)
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            codec = null; track = null
        }

        private fun loop() {
            val info = MediaCodec.BufferInfo()
            try {
                while (running) {
                    val item = queue.poll(5, TimeUnit.MILLISECONDS)
                    if (item != null) feed(item.first, item.second)
                    drain(info)
                    updateClock()
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                L.w("audio: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }

        private fun feed(pes: ByteArray, ptsUs: Long) {
            var p = 0
            var i = 0
            while (p + 7 <= pes.size) {
                if ((pes[p].toInt() and 0xFF) != 0xFF || (pes[p + 1].toInt() and 0xF0) != 0xF0) { p++; continue }
                val protectionAbsent = pes[p + 1].toInt() and 1
                val profile = (pes[p + 2].toInt() shr 6) and 3
                val sfi = (pes[p + 2].toInt() shr 2) and 0xF
                val ch = ((pes[p + 2].toInt() and 1) shl 2) or ((pes[p + 3].toInt() shr 6) and 3)
                val len = ((pes[p + 3].toInt() and 3) shl 11) or ((pes[p + 4].toInt() and 0xFF) shl 3) or
                    ((pes[p + 5].toInt() and 0xE0) shr 5)
                val hdr = if (protectionAbsent == 1) 7 else 9
                if (len < hdr || p + len > pes.size) break
                if (codec == null) open(profile, sfi, ch)
                val c = codec ?: return
                val frameRate = SAMPLE_RATES.getOrElse(sfi) { 48000 }
                val pts = ptsUs + i * 1024L * 1_000_000 / frameRate
                val idx = c.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val b = c.getInputBuffer(idx)!!
                    b.clear()
                    b.put(pes, p + hdr, len - hdr)
                    c.queueInputBuffer(idx, 0, len - hdr, pts, 0)
                }
                p += len
                i++
            }
        }

        private fun open(profile: Int, sfi: Int, ch: Int) {
            rate = SAMPLE_RATES.getOrElse(sfi) { 48000 }
            channels = if (ch in 1..2) ch else 2
            val asc = ((profile + 1) shl 11) or (sfi shl 7) or (channels shl 3)
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels)
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf((asc shr 8).toByte(), asc.toByte())))
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(fmt, null, null, 0)
            c.start()
            codec = c
            val chMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val min = AudioTrack.getMinBufferSize(rate, chMask, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(chMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min * 2, rate * channels * 2 / 10))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            track = t
            L.i("audio: AAC ${rate}Hz ${channels}ch via ${c.name}, track buffer ${t.bufferSizeInFrames} frames")
        }

        private fun drain(info: MediaCodec.BufferInfo) {
            val c = codec ?: return
            val t = track ?: return
            while (true) {
                val idx = c.dequeueOutputBuffer(info, 0)
                if (idx < 0) return
                val b = c.getOutputBuffer(idx)!!
                val bytesPerFrame = 2 * channels
                val frames = info.size / bytesPerFrame
                if (frames > 0) {
                    if (anchorPts == Long.MIN_VALUE) {
                        // запас, чтобы видео (декодер дольше) успевало к своему звуку
                        val pre = rate.toLong() * Tuning.audioDelayMs / 1000
                        writeSilence(t, pre)
                        anchorPts = info.presentationTimeUs
                        anchorFrame = framesWritten
                    }
                    val expectedPts = anchorPts + (framesWritten - anchorFrame) * 1_000_000 / rate
                    val drift = info.presentationTimeUs - expectedPts
                    when {
                        drift > 40_000 -> writeSilence(t, minOf(drift, 500_000) * rate / 1_000_000)
                        drift < -40_000 -> { c.releaseOutputBuffer(idx, false); continue } // наложение
                    }
                    val chunk = ByteArray(info.size)
                    b.position(info.offset)
                    b.get(chunk)
                    t.write(chunk, 0, chunk.size)
                    framesWritten += frames
                }
                c.releaseOutputBuffer(idx, false)
            }
        }

        private fun writeSilence(t: AudioTrack, frames: Long) {
            if (frames <= 0) return
            val zeros = ByteArray((frames * 2 * channels).toInt())
            t.write(zeros, 0, zeros.size)
            framesWritten += frames
        }

        private fun updateClock() {
            val t = track ?: return
            if (anchorPts == Long.MIN_VALUE) return
            val now = System.nanoTime()
            if (now - lastClock < 20_000_000L) return
            lastClock = now
            if (t.getTimestamp(ts)) {
                val pts = anchorPts + (ts.framePosition - anchorFrame) * 1_000_000 / rate
                clock.setAudio(pts, ts.nanoTime)
            }
        }
    }

    private companion object {
        val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    }
}

/** Разбор H.264 Annex B: параметры потока для настройки декодера. */
internal object H264 {
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
    /** Запас звука перед первым сэмплом: видео декодируется дольше и должно успевать к звуку. */
    val audioDelayMs: Long get() = prop("persist.z7.mc.audio_delay_ms", 60)
    /** Задержка показа, пока звука нет. */
    val videoOnlyLatencyMs: Long get() = prop("persist.z7.mc.video_latency_ms", 50)

    private fun prop(name: String, def: Long): Long = try {
        (Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
            .invoke(null, name, "") as String).toLongOrNull() ?: def
    } catch (_: Exception) { def }
}
