package os.z7.miracast

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * RTSP-сторона приёмника WFD (Wi-Fi Display 1.0, раздел 6). Приёмник сам подключается к источнику
 * (порт 7236), дальше источник ведёт: M1 OPTIONS → (мы) M2 OPTIONS → M3 GET_PARAMETER (наши
 * возможности) → M4 SET_PARAMETER (выбранный формат) → M5 trigger SETUP → (мы) M6 SETUP → M7 PLAY.
 * Потом источник раз в ~30 с шлёт пустой GET_PARAMETER (keep-alive), мы отвечаем 200.
 */
internal class RtspSink(
    private val host: String,
    private val port: Int,
    private val listener: Listener,
) {
    interface Listener {
        /** Источник выбрал формат (M4). */
        fun onFormat(video: String?, audio: String?)
        /** Сеанс запущен (M7 PLAY → 200): RTP пошёл на [Wfd.RTP_PORT]. */
        fun onPlaying()
        fun onClosed(reason: String)
    }

    private var socket: Socket? = null
    private lateinit var out: OutputStream
    @Volatile private var closed = false
    private var cseq = 1
    private var presentationUrl: String? = null
    private var session: String? = null
    /** Ответ на наш запрос (по CSeq) — что делать с ним. */
    private val pending = HashMap<Int, (Message) -> Unit>()

    fun start() {
        Thread({ run() }, "wfd-rtsp").start()
    }

    fun close() {
        closed = true
        try { socket?.close() } catch (_: Exception) {}
    }

    /** Попросить ключевой кадр (M13), например после потери пакетов. */
    fun requestIdr() {
        val url = presentationUrl ?: return
        sendRequest("SET_PARAMETER", url, "wfd_idr_request\r\n", "text/parameters") {}
    }

    private fun run() {
        try {
            val s = Socket()
            s.tcpNoDelay = true
            // источник поднимает RTSP-сервер чуть позже группы: несколько попыток
            var lastError: Exception? = null
            for (attempt in 1..20) {
                try {
                    s.connect(InetSocketAddress(host, port), 3000)
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e
                    Thread.sleep(500)
                }
                if (closed) return
            }
            if (lastError != null) throw lastError
            socket = s
            out = s.getOutputStream()
            L.i("rtsp: connected to $host:$port")
            val input = BufferedInputStream(s.getInputStream())
            while (!closed) {
                val msg = Message.read(input) ?: break
                handle(msg)
            }
            if (!closed) listener.onClosed("source closed the connection")
        } catch (e: Exception) {
            if (!closed) {
                L.w("rtsp: ${e.javaClass.simpleName}: ${e.message}")
                listener.onClosed(e.message ?: e.javaClass.simpleName)
            }
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun handle(m: Message) {
        if (m.isResponse) {
            val handler = synchronized(pending) { pending.remove(m.cseq) }
            L.i("rtsp: <- ${m.firstLine} (cseq ${m.cseq})")
            handler?.invoke(m)
            return
        }
        L.i("rtsp: <- ${m.method} ${m.uri}" + if (m.body.isNotEmpty()) "\n${m.body.trimEnd()}" else "")
        when (m.method) {
            "OPTIONS" -> {
                reply(m, extra = "Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER\r\n")
                // M2: наш OPTIONS к источнику
                sendRequest("OPTIONS", "*", null, null, extraHeaders = "Require: org.wfa.wfd1.0\r\n") {}
            }
            "GET_PARAMETER" -> {
                if (m.body.isBlank()) { reply(m); return } // keep-alive (M16)
                val sb = StringBuilder()
                for (line in m.body.lines()) {
                    val p = line.trim()
                    if (p.isEmpty()) continue
                    val v = Wfd.answer(p)
                    if (v != null) sb.append(p).append(": ").append(v).append("\r\n")
                    else L.i("rtsp: M3 asks for unsupported '$p'")
                }
                reply(m, body = sb.toString(), contentType = "text/parameters")
            }
            "SET_PARAMETER" -> {
                val params = m.params()
                params["wfd_presentation_URL"]?.let { presentationUrl = it.trim().split(" ").first() }
                val video = params["wfd_video_formats"]
                val audio = params["wfd_audio_codecs"]
                if (video != null || audio != null) {
                    L.i("rtsp: source chose video '${video?.let { Wfd.describeChosen(it) }}' audio '$audio'")
                    listener.onFormat(video, audio)
                }
                reply(m)
                when (params["wfd_trigger_method"]?.trim()?.uppercase()) {
                    "SETUP" -> sendSetup()
                    "TEARDOWN" -> {
                        presentationUrl?.let { sendRequest("TEARDOWN", it, null, null) {} }
                        listener.onClosed("source stopped casting")
                    }
                    "PLAY", "PAUSE" -> presentationUrl?.let { url ->
                        sendRequest(params["wfd_trigger_method"]!!.trim().uppercase(), url, null, null) {}
                    }
                }
            }
            else -> reply(m, status = "501 Not Implemented")
        }
    }

    private fun sendSetup() {
        val url = presentationUrl ?: run { L.w("rtsp: SETUP without presentation URL"); return }
        sendRequest(
            "SETUP", url, null, null,
            extraHeaders = "Transport: RTP/AVP/UDP;unicast;client_port=${Wfd.RTP_PORT}\r\n",
        ) { r ->
            session = r.header("session")?.substringBefore(';')?.trim()
            if (r.status != 200 || session == null) {
                listener.onClosed("SETUP failed: ${r.firstLine}")
                return@sendRequest
            }
            sendRequest("PLAY", url, null, null) { p ->
                if (p.status == 200) listener.onPlaying() else listener.onClosed("PLAY failed: ${p.firstLine}")
            }
        }
    }

    private fun reply(
        req: Message,
        status: String = "200 OK",
        extra: String = "",
        body: String = "",
        contentType: String? = null,
    ) {
        val sb = StringBuilder("RTSP/1.0 $status\r\nCSeq: ${req.cseq}\r\n")
        sb.append(extra)
        session?.let { sb.append("Session: $it\r\n") }
        if (body.isNotEmpty()) {
            sb.append("Content-Type: ${contentType ?: "text/parameters"}\r\n")
            sb.append("Content-Length: ${body.toByteArray().size}\r\n")
        }
        sb.append("\r\n").append(body)
        write(sb.toString())
        if (body.isNotEmpty()) L.i("rtsp: -> $status\n${body.trimEnd()}")
    }

    private fun sendRequest(
        method: String,
        uri: String,
        body: String?,
        contentType: String?,
        extraHeaders: String = "",
        onResponse: (Message) -> Unit,
    ) {
        val seq: Int
        synchronized(pending) { seq = cseq++; pending[seq] = onResponse }
        val sb = StringBuilder("$method $uri RTSP/1.0\r\nCSeq: $seq\r\nUser-Agent: z7os-wfd/1.0\r\n")
        sb.append(extraHeaders)
        session?.let { if (method != "SETUP") sb.append("Session: $it\r\n") }
        if (body != null) {
            sb.append("Content-Type: ${contentType ?: "text/parameters"}\r\n")
            sb.append("Content-Length: ${body.toByteArray().size}\r\n")
        }
        sb.append("\r\n")
        if (body != null) sb.append(body)
        L.i("rtsp: -> $method $uri (cseq $seq)")
        write(sb.toString())
    }

    @Synchronized
    private fun write(s: String) {
        try {
            out.write(s.toByteArray())
            out.flush()
        } catch (e: Exception) {
            L.w("rtsp: write failed: ${e.message}")
        }
    }

    /** Одно RTSP-сообщение: запрос или ответ. */
    class Message(val firstLine: String, private val headers: Map<String, String>, val body: String) {
        val isResponse = firstLine.startsWith("RTSP/")
        val method: String = if (isResponse) "" else firstLine.substringBefore(' ')
        val uri: String = if (isResponse) "" else firstLine.split(' ').getOrElse(1) { "" }
        val status: Int = if (isResponse) firstLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0 else 0
        val cseq: Int = header("cseq")?.trim()?.toIntOrNull() ?: -1

        fun header(name: String) = headers[name.lowercase()]

        /** Тело text/parameters: "имя: значение" построчно. */
        fun params(): Map<String, String> = body.lines().mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.toMap()

        companion object {
            fun read(input: InputStream): Message? {
                val first = readLine(input) ?: return null
                if (first.isEmpty()) return read(input)
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return null
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(len)
                var got = 0
                while (got < len) {
                    val n = input.read(body, got, len - got)
                    if (n < 0) return null
                    got += n
                }
                return Message(first, headers, String(body))
            }

            private fun readLine(input: InputStream): String? {
                val buf = ByteArrayOutputStream()
                while (true) {
                    val b = input.read()
                    if (b < 0) return if (buf.size() == 0) null else buf.toString()
                    if (b == '\n'.code) break
                    if (b != '\r'.code) buf.write(b)
                }
                return buf.toString()
            }
        }
    }
}
