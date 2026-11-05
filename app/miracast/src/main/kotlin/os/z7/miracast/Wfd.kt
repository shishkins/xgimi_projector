package os.z7.miracast

import android.util.Log

internal object L {
    private const val TAG = "z7mc"
    fun i(msg: String) { Log.i(TAG, msg) }
    fun w(msg: String, t: Throwable? = null) { Log.w(TAG, msg, t) }
}

/**
 * Что приёмник сообщает источнику в ответ на M3 (WFD 1.0, раздел 6.1).
 *
 * Стоковый приёмник XGIMI объявлял «родным» 1080p60 (0x40), но не включал этот режим в маску CEA —
 * Windows отвергала такой ответ и уходила в 1024×768. Здесь родной режим входит в маску.
 */
internal object Wfd {
    /** Порт, на который источник шлёт RTP (MPEG-TS). Чётный, RTCP не используем. */
    const val RTP_PORT = 15550

    /** Стандартный порт RTSP источника WFD. */
    const val DEFAULT_CONTROL_PORT = 7236

    /**
     * Родной режим: биты 2..0 — таблица (0 = CEA), 7..3 — номер режима. CEA 8 = 1920×1080p60.
     * Маска CEA 0x1FFFF: все режимы 640×480 … 1080p24, включая 1080p60 и 1080p30.
     * Профили: 02 = Constrained High, 01 = Constrained Baseline; уровень 0x10 = 4.2 (нужен для 1080p60).
     */
    private const val NATIVE = "40"
    private const val CEA = "0001FFFF"
    private const val VESA = "00000000"
    private const val HH = "00000000"
    val VIDEO_FORMATS =
        "$NATIVE 00 02 10 $CEA $VESA $HH 00 0000 0000 00 none none, " +
            "01 10 $CEA $VESA $HH 00 0000 0000 00 none none"

    /** Только AAC 48 кГц стерео: LPCM в WFD-обёртке не нужен, Windows шлёт AAC. */
    const val AUDIO_CODECS = "AAC 00000001 00"

    /** Значение параметра для ответа M3; null — параметр не поддерживаем (в ответ не включаем). */
    fun answer(param: String): String? = when (param) {
        "wfd_video_formats" -> VIDEO_FORMATS
        "wfd_audio_codecs" -> AUDIO_CODECS
        "wfd_client_rtp_ports" -> "RTP/AVP/UDP;unicast $RTP_PORT 0 mode=play"
        "wfd_content_protection" -> "none"
        "wfd_display_edid" -> "none"
        "wfd_coupled_sink" -> "none"
        "wfd_uibc_capability" -> "none"
        "wfd_standby_resume_capability" -> "none"
        "wfd_3d_video_formats" -> "none"
        "wfd_connector_type" -> "05"
        "wfd_idr_request_capability" -> "1"
        "microsoft_latency_management_capability" -> "supported"
        "microsoft_format_change_capability" -> "supported"
        else -> null
    }

    /** Разбор строки вида "02 10 00000080 ..." из M4 в человекочитаемый режим (для журнала). */
    fun describeChosen(videoFormats: String): String {
        val t = videoFormats.trim().split(Regex("\\s+"))
        if (t.size < 6) return videoFormats
        val profile = when (t[2]) { "01" -> "CBP"; "02" -> "CHP"; else -> t[2] }
        val cea = t[4].toLongOrNull(16) ?: 0
        val vesa = t[5].toLongOrNull(16) ?: 0
        val hh = t.getOrNull(6)?.toLongOrNull(16) ?: 0
        val mode = when {
            cea != 0L -> "CEA " + (CEA_MODES.getOrNull(bit(cea)) ?: "bit${bit(cea)}")
            vesa != 0L -> "VESA " + (VESA_MODES.getOrNull(bit(vesa)) ?: "bit${bit(vesa)}")
            hh != 0L -> "HH bit${bit(hh)}"
            else -> "?"
        }
        return "$mode $profile level=${t[3]}"
    }

    private fun bit(v: Long) = java.lang.Long.numberOfTrailingZeros(v)

    private val CEA_MODES = listOf(
        "640x480p60", "720x480p60", "720x480i60", "720x576p50", "720x576i50", "1280x720p30",
        "1280x720p60", "1920x1080p30", "1920x1080p60", "1920x1080i60", "1280x720p25", "1280x720p50",
        "1920x1080p25", "1920x1080p50", "1920x1080i50", "1280x720p24", "1920x1080p24",
    )
    private val VESA_MODES = listOf(
        "800x600p30", "800x600p60", "1024x768p30", "1024x768p60", "1152x864p30", "1152x864p60",
        "1280x768p30", "1280x768p60", "1280x800p30", "1280x800p60", "1360x768p30", "1360x768p60",
        "1366x768p30", "1366x768p60", "1280x1024p30", "1280x1024p60", "1400x1050p30", "1400x1050p60",
        "1440x900p30", "1440x900p60", "1600x900p30", "1600x900p60", "1600x1200p30", "1600x1200p60",
        "1680x1024p30", "1680x1024p60", "1680x1050p30", "1680x1050p60", "1920x1200p30",
    )
}
