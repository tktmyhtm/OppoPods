package moe.chenxy.oppopods.pods
import android.util.Log

object HuaweiCRC {
    private val CRC16_TABLE = intArrayOf(
        0, 4129, 8258, 12387, 16516, 20645, 24774, 28903, -32504, -28375, -24246, -20117, -15988, -11859, -7730, -3601,
        4657, 528, 12915, 8786, 21173, 17044, 29431, 25302, -27847, -31976, -19589, -23718, -11331, -15460, -3073, -7202
    )
    fun compute(bArr: ByteArray, length: Int): Short {
        var s = 0
        for (i in 0 until length) {
            val idx = ((s ushr 8) xor (bArr[i].toInt() and 0xFF)) and 0xFF
            s = (CRC16_TABLE[idx] xor (s shl 8)) and 0xFFFF
        }
        return s.toShort()
    }
}

object MbbProtocol {
    const val HEADER: Byte = 0x5A
    const val TYPE_SPP: Byte = 0x00
    const val TYPE_BLE: Byte = 0x01
    const val SVC_BATTERY: Byte = 0x0A
    const val SVC_ANC: Byte = 0x02
    const val SVC_GENERIC: Byte = 0x01
    const val CMD_BAT_REPORT: Byte = 0x0D
    const val CMD_ANC_MODE: Byte = 0x04

    fun isFrameStart(b1: Byte, b2: Byte) = b1 == HEADER && (b2 == TYPE_SPP || b2 == TYPE_BLE)

    fun extractSppPayload(frame: ByteArray): ByteArray? {
        if (frame.size < 7 || frame[0] != HEADER || frame[1] != TYPE_SPP) return null
        val plen = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        val total = 4 + plen
        if (frame.size < total) return null
        return frame.copyOfRange(4, total)
    }

    fun buildBleFrame(svc: Byte, cmd: Byte, payload: ByteArray = byteArrayOf()): ByteArray {
        val seq: Byte = 0xF0.toByte()
        val data = byteArrayOf(HEADER, TYPE_BLE, svc, cmd, seq) + payload
        val crc = HuaweiCRC.compute(data, data.size)
        return data + byteArrayOf(((crc.toInt() ushr 8) and 0xFF).toByte(), (crc.toInt() and 0xFF).toByte())
    }
}

object MbbCmd {
    val QUERY_BATTERY: ByteArray by lazy { MbbProtocol.buildBleFrame(0x01, 0x06) }
    val QUERY_ANC: ByteArray by lazy { MbbProtocol.buildBleFrame(0x02, 0x04) }
    fun ancCommand(mode: Int): ByteArray {
        val p = when (mode) { 2 -> byteArrayOf(0x01, 0x01, 0x02); 3 -> byteArrayOf(0x01, 0x01, 0x04); else -> byteArrayOf(0x01, 0x01, 0x01) }
        return MbbProtocol.buildBleFrame(0x02, 0x42, p)
    }
}

class HuaweiPacketFramer {
    private var pending = ByteArray(0)
    fun append(buffer: ByteArray, length: Int): List<ByteArray> {
        if (length <= 0) return emptyList()
        pending += buffer.copyOfRange(0, length)
        val frames = mutableListOf<ByteArray>()
        while (pending.size >= 7) {
            var start = -1
            for (i in 0 until pending.size - 1) {
                if (MbbProtocol.isFrameStart(pending[i], pending[i + 1])) { start = i; break }
            }
            if (start < 0) { pending = ByteArray(0); break }
            if (start > 0) pending = pending.copyOfRange(start, pending.size)
            if (pending.size < 7) break
            val plen = ((pending[2].toInt() and 0xFF) shl 8) or (pending[3].toInt() and 0xFF)
            val frameLen = 4 + plen
            if (plen < 2 || frameLen > 4096) { pending = pending.copyOfRange(1, pending.size); continue }
            if (pending.size < frameLen) break
            frames += pending.copyOfRange(0, frameLen)
            pending = pending.copyOfRange(frameLen, pending.size)
        }
        return frames
    }
}

object BatteryParser {
    data class BatteryInfo(val level: Int, val isCharging: Boolean)
    data class BatteryResult(val left: BatteryInfo?, val right: BatteryInfo?, val case: BatteryInfo?)

    fun parseSpp(data: ByteArray): BatteryResult? {
        val payload = MbbProtocol.extractSppPayload(data) ?: return null
        if (payload.size < 3) return null
        val svc = payload[0].toInt() and 0xFF
        val cmd = payload[1].toInt() and 0xFF
        if (svc != 0x0A || cmd != 0x0D) return null
        return parseTlv(payload, 2)
    }

    fun parseBle(data: ByteArray): BatteryResult? {
        if (data.size < 9 || data[0] != 0x5A.toByte() || data[1] != 0x01.toByte()) return null
        if (data[2] != 0x01.toByte()) return null
        val cmd = data[3].toInt() and 0xFF
        if (cmd != 0x06 && cmd != 0x86) return null
        return parseTlv(data, 5)
    }

    private fun parseTlv(data: ByteArray, off: Int): BatteryResult? {
        var i = off; var lv = -1; var rv = -1; var cv = -1
        var lc = false; var rc = false; var cc = false
        try {
            while (i < data.size - 2) {
                val tag = data[i].toInt() and 0xFF
                val b1 = data[i + 1].toInt() and 0xFF
                val length: Int; val vs: Int
                if ((b1 and 0x80) != 0) {
                    if (i + 2 >= data.size) break
                    length = ((b1 and 0x7F) shl 7) + (data[i + 2].toInt() and 0x7F); vs = i + 3
                } else { length = b1 and 0x7F; vs = i + 2 }
                if (vs + length > data.size) break
                when (tag) {
                    2 -> { if (length >= 1) lv = data[vs].toInt() and 0xFF
                           if (length >= 2) rv = data[vs + 1].toInt() and 0xFF
                           if (length >= 3) cv = data[vs + 2].toInt() and 0xFF }
                    3 -> { if (length >= 1) lc = data[vs].toInt() == 1
                           if (length >= 2) rc = data[vs + 1].toInt() == 1
                           if (length >= 3) cc = data[vs + 2].toInt() == 1 }
                }
                i = vs + length
            }
        } catch (_: Exception) { return null }
        if (lv != -1 && rv != -1) {
            if (kotlin.math.abs(lv - rv) <= 15 && lv > 0 && rv > 0) { if (lv > rv) lv = rv else rv = lv }
            return BatteryResult(left = BatteryInfo(lv, lc), right = BatteryInfo(rv, rc), case = if (cv >= 0) BatteryInfo(cv, cc) else null)
        }
        return null
    }
}

object AncModeParser {
    fun parseSpp(data: ByteArray): NoiseControlMode? {
        val payload = MbbProtocol.extractSppPayload(data) ?: return null
        if (payload.size < 3 || payload[0] != MbbProtocol.SVC_ANC || payload[1] != MbbProtocol.CMD_ANC_MODE) return null
        return when (payload[2].toInt() and 0xFF) { 0 -> NoiseControlMode.OFF; 1 -> NoiseControlMode.NOISE_CANCELLATION; 2 -> NoiseControlMode.TRANSPARENCY; else -> null }
    }
    fun parseBle(data: ByteArray): NoiseControlMode? {
        if (data.size < 9 || data[0] != 0x5A.toByte() || data[1] != 0x01.toByte() || data[2] != MbbProtocol.SVC_ANC) return null
        val cmd = data[3].toInt() and 0xFF
        if (cmd != 0x04 && cmd != 0x44) return null
        if (data.size < 8) return null
        return when (data[7].toInt() and 0xFF) { 0 -> NoiseControlMode.OFF; 1 -> NoiseControlMode.NOISE_CANCELLATION; 2 -> NoiseControlMode.TRANSPARENCY; else -> null }
    }
}

enum class NoiseControlMode { OFF, NOISE_CANCELLATION, TRANSPARENCY }
