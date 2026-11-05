package os.z7.miracast

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Приём и воспроизведение потока WFD: RTP/UDP → MPEG-TS → H.264 (аппаратный декодер на Surface)
 * и AAC (MediaCodec → AudioTrack). Видео выводится по часам звука: кадр с PTS X показывается,
 * когда динамик играет звук с тем же PTS.
 *
 * Сжатые кадры не выбрасываются никогда: пропавший P-кадр портит картинку до следующего
 * ключевого (красные пятна, остатки старых окон). Если очередь всё же переполнилась — ждём
 * ключевой кадр и просим его у источника.
 */
internal class MirrorPlayer(private val surface: Surface, private val listener: Listener) {

    interface Listener {
        fun onVideoSize(width: Int, height: Int)
        fun onFirstFrame()
        /** Нужен ключевой кадр (потеря пакетов или сброс очереди). */
        fun onNeedIdr()
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
        video.start()
        audio.start()
        Thread({ receiveLoop(s) }, "wfd-rtp").apply { priority = Thread.MAX_PRIORITY }.start()
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
                    video.corrupted() // кадр с дырой испортит картинку — до ключевого не показываем
                }
            }
            expected = (seq + 1) and 0xFFFF
            packets++
            bytes += n
            if (off < n) demux.feed(buf, off, n - off)
            val now = SystemClock.elapsedRealtime()
            if (now - lastReport >= 5000) {
                val dt = now - lastReport
                L.i("rtp: %.1f Mbit/s, lost %d | video %s | audio %s".format(
                    bytes * 8 / 1000.0 / dt, lost, video.report(dt), audio.report()))
                packets = 0; lost = 0; bytes = 0; lastReport = now
            }
        }
    }

    /** Общие часы: какой PTS звучит сейчас. Пока звука нет, — от первого кадра видео с запасом. */
    private inner class AvClock {
        @Volatile private var audioPtsUs = Long.MIN_VALUE
        @Volatile private var audioAtNs = 0L
        private var basePts = Long.MIN_VALUE
        private var baseNs = 0L

        fun setAudio(ptsUs: Long, atNs: Long) { audioPtsUs = ptsUs; audioAtNs = atNs }

        /** Когда показать кадр с [ptsUs] (System.nanoTime). */
        fun renderAt(ptsUs: Long): Long {
            val now = System.nanoTime()
            if (audioPtsUs != Long.MIN_VALUE && now - audioAtNs < 500_000_000L) {
                // кадр, отложенный надолго, держит буфер декодера — и тот встаёт
                return minOf(audioAtNs + (ptsUs - audioPtsUs) * 1000, now + MAX_LEAD_NS)
            }
            val at = if (basePts == Long.MIN_VALUE) Long.MIN_VALUE else baseNs + (ptsUs - basePts) * 1000
            if (at == Long.MIN_VALUE || at < now - 200_000_000L || at > now + 1_000_000_000L) {
                basePts = ptsUs
                baseNs = now + Tuning.videoOnlyLatencyMs * 1_000_000L
                return baseNs
            }
            return at
        }
    }

    /**
     * H.264 → Surface в асинхронном режиме MediaCodec: свободный входной буфер берёт следующий
     * кадр из очереди, готовый выходной — выводится к сроку по часам. Всё в одном потоке.
     */
    private inner class VideoDecoder {
        private val thread = HandlerThread("wfd-video", android.os.Process.THREAD_PRIORITY_DISPLAY)
        private lateinit var handler: Handler
        private var codec: MediaCodec? = null
        private val pending = ArrayDeque<Pair<ByteArray, Long>>()
        private val freeInputs = ArrayDeque<Int>()
        private var waitKey = true
        private var first = true
        // статистика за период отчёта
        private var frames = 0
        private var late = 0
        private var leadSumMs = 0L

        fun start() {
            thread.start()
            handler = Handler(thread.looper)
        }

        fun stop() {
            handler.post {
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                codec = null
            }
            thread.quitSafely()
            thread.join(1000)
        }

        /** Из потока приёма: кадр готов. */
        fun offer(au: ByteArray, ptsUs: Long) {
            handler.post { enqueue(au, ptsUs) }
        }

        /** Потерян пакет: текущие кадры могут быть битыми — ждём ключевой. */
        fun corrupted() {
            handler.post {
                if (!waitKey) L.w("video: packet loss, waiting for a key frame")
                awaitKey()
            }
        }

        /** Ждать ключевой кадр, напоминая источнику, пока не придёт (Windows отвечает не на каждый запрос). */
        private fun awaitKey() {
            waitKey = true
            handler.removeCallbacks(idrReminder)
            listener.onNeedIdr()
            handler.postDelayed(idrReminder, IDR_RETRY_MS)
        }

        private val idrReminder = object : Runnable {
            override fun run() {
                if (!waitKey || !running) return
                listener.onNeedIdr()
                handler.postDelayed(this, IDR_RETRY_MS)
            }
        }

        fun report(periodMs: Long): String {
            val r = "${frames * 1000 / periodMs} fps, late $late, lead ${if (frames > 0) leadSumMs / frames else 0} ms, queue ${pending.size}"
            frames = 0; late = 0; leadSumMs = 0
            return r
        }

        private fun enqueue(au: ByteArray, ptsUs: Long) {
            if (waitKey) {
                if (!H264.isKeyFrame(au)) return
                waitKey = false
                handler.removeCallbacks(idrReminder)
                pending.clear()
            }
            if (codec == null && !open(au)) return
            if (pending.size >= MAX_PENDING) {
                // декодер не успевает: старые кадры уже не нужны, начинаем со свежего ключевого
                L.w("video: decoder behind by ${pending.size} frames, skipping to a key frame")
                pending.clear()
                awaitKey()
                return
            }
            pending.add(au to ptsUs)
            pump()
        }

        private fun pump() {
            val c = codec ?: return
            while (pending.isNotEmpty() && freeInputs.isNotEmpty()) {
                val idx = freeInputs.poll()!!
                val (au, pts) = pending.poll()!!
                val b = c.getInputBuffer(idx) ?: continue
                b.clear()
                if (au.size > b.capacity()) { L.w("video: frame ${au.size} B too big"); c.queueInputBuffer(idx, 0, 0, pts, 0); continue }
                b.put(au)
                c.queueInputBuffer(idx, 0, au.size, pts, 0)
            }
        }

        private fun open(au: ByteArray): Boolean {
            val (sps, pps) = H264.parameterSets(au) ?: return false
            val size = H264.size(sps)
            L.i("video: SPS ${size?.first}x${size?.second}, starting decoder")
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                size?.first ?: 1920, size?.second ?: 1080)
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0)
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                    freeInputs.add(index); pump()
                }
                override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    render(mc, index, info)
                }
                override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                    L.w("video: codec error ${e.diagnosticInfo}", e)
                }
                override fun onOutputFormatChanged(mc: MediaCodec, f: MediaFormat) {
                    val w = f.getInteger(MediaFormat.KEY_WIDTH)
                    val h = f.getInteger(MediaFormat.KEY_HEIGHT)
                    val cw = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1 else w
                    val ch = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1 else h
                    L.i("video: output ${cw}x$ch")
                    listener.onVideoSize(cw, ch)
                }
            }, handler)
            c.configure(fmt, surface, null, 0)
            c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            c.start()
            L.i("video: decoder ${c.name}")
            codec = c
            return true
        }

        private fun render(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val at = clock.renderAt(info.presentationTimeUs)
            val now = System.nanoTime()
            val leadMs = (at - now) / 1_000_000
            frames++
            leadSumMs += leadMs
            if (at < now + 2_000_000L) {
                late++
                c.releaseOutputBuffer(index, true) // опоздал — показать сразу
            } else {
                c.releaseOutputBuffer(index, at)
            }
            if (first) { first = false; listener.onFirstFrame() }
        }
    }

    /**
     * AAC (ADTS в PES) → PCM → AudioTrack. AudioTrack на этом проекторе начинает играть только
     * с полным буфером, поэтому PCM пишется всегда (блокирующая запись сама держит темп), а
     * задержка ограничивается на входе: пакет, отставший от самого свежего больше чем на
     * [Tuning.audioDelayMs], выбрасывается. Часы видео: какой PTS сейчас звучит (getTimestamp).
     */
    private inner class AudioPlayer {
        private val queue = LinkedBlockingQueue<Pair<ByteArray, Long>>(200)
        private var codec: MediaCodec? = null
        private var track: AudioTrack? = null
        private var thread: Thread? = null
        private var rate = 48000
        private var channels = 2
        private var framesWritten = 0L
        /** PTS конца последнего записанного куска: сэмпл номер framesWritten. */
        private var endPts = Long.MIN_VALUE
        private val ts = AudioTimestamp()
        private var lastClock = 0L
        private var trimmed = 0L
        @Volatile private var pendingMs = 0L

        fun report(): String = if (track == null) "off" else "buffer $pendingMs ms, trimmed ${trimmed.also { trimmed = 0 }} ms"

        @Volatile private var newestPts = Long.MIN_VALUE

        fun offer(pes: ByteArray, ptsUs: Long) {
            newestPts = ptsUs
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
                    if (item != null) {
                        val behindMs = (newestPts - item.second) / 1000
                        if (track != null && behindMs > Tuning.audioDelayMs) trimmed += 21 // ~1 кадр AAC
                        else feed(item.first, item.second, info)
                    }
                    drain(info)
                    updateClock()
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                L.w("audio: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }

        private fun feed(pes: ByteArray, ptsUs: Long, info: MediaCodec.BufferInfo) {
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
                val pts = ptsUs + i * 1024L * 1_000_000 / rate
                var idx = c.dequeueInputBuffer(0)
                while (idx < 0 && running) { drain(info); idx = c.dequeueInputBuffer(2_000) }
                if (idx < 0) return
                val b = c.getInputBuffer(idx)!!
                b.clear()
                b.put(pes, p + hdr, len - hdr)
                c.queueInputBuffer(idx, 0, len - hdr, pts, 0)
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
            // буфер не больше нужного: он же — задержка звука (играет только полный)
            val want = (rate * channels * 2 * Tuning.audioBufferMs / 1000).toInt()
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(chMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min, want))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            track = t
            L.i("audio: AAC ${rate}Hz ${channels}ch via ${c.name}, track buffer ${t.bufferSizeInFrames} frames, target ${Tuning.audioDelayMs} ms")
        }

        private fun drain(info: MediaCodec.BufferInfo) {
            val c = codec ?: return
            val t = track ?: return
            while (true) {
                val idx = c.dequeueOutputBuffer(info, 0)
                if (idx < 0) return
                val frames = info.size / (2 * channels)
                if (frames > 0) {
                    val b = c.getOutputBuffer(idx)!!
                    val chunk = ByteArray(info.size)
                    b.position(info.offset)
                    b.get(chunk)
                    t.write(chunk, 0, chunk.size)
                    framesWritten += frames
                    endPts = info.presentationTimeUs + frames * 1_000_000L / rate
                    pendingMs = (framesWritten - t.playbackHeadPosition.toLong()) * 1000 / rate
                }
                c.releaseOutputBuffer(idx, false)
            }
        }

        private fun updateClock() {
            val t = track ?: return
            if (endPts == Long.MIN_VALUE) return
            val now = System.nanoTime()
            if (now - lastClock < 10_000_000L) return
            lastClock = now
            if (t.getTimestamp(ts)) {
                // сэмпл ts.framePosition звучит в ts.nanoTime; его PTS — от конца записанного
                val pts = endPts - (framesWritten - ts.framePosition) * 1_000_000 / rate
                clock.setAudio(pts, ts.nanoTime)
            }
        }
    }

    private companion object {
        val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        /** Больше кадров в очереди — декодер явно не успевает. */
        const val MAX_PENDING = 30
        const val IDR_RETRY_MS = 500L
        /** Дальше этого кадр не откладываем, даже если звук «впереди». */
        const val MAX_LEAD_NS = 150_000_000L
    }
}
