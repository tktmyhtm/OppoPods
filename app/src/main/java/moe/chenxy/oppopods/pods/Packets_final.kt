package moe.chenxy.oppopods.pods

import android.util.Log

/**
 * CRC16-XMODEM for Huawei SPP protocol.
 * Matches OpenFreebuds implementation exactly.
 */
object HuaweiCRC {
    private val TABLE = intArrayOf(
        0, 4129, 8258, 12387, 16516, 20645, 24774, 28903, -32504, -28375, -24246, -20117, -15988, -11859, -7730, -3601,
        4657, 528, 12915, 8786, 21173, 17044, 29431, 25302, -27847, -31976, -19589, -23718, -11331, -15460, -3073, -7202,
        9314, 13379, 1056, 5121, 25830, 29895, 17572, 21637, -23190, -19125, -31448, -27383, -6674, -2609, -14932, -10867,
        13907, 9842, 5649, 1584, 30423, 26358, 22165, 18100, -18597, -22662, -26855, -30920, -2081, -6146, -10339, -14404,
        18628, 22757, 26758, 30887, 2112, 6241, 10242, 14371, -13876, -9747, -5746, -1617, -30392, -26263, -22262, -18133,
        23285, 19156, 31415, 27286, 6769, 2640, 14899, 10770, -9219, -13348, -1089, -5218, -25735, -29864, -17605, -21734,
        27814, 31879, 19684, 23749, 11298, 15363, 3168, 7233, -4690, -625, -12820, -8755, -21206, -17141, -29336, -25271,
        32407, 28342, 24277, 20212, 15891, 11826, 7761, 3696, -97, -4162, -8227, -12292, -16613, -20678, -24743, -28808,
        -28280, -32343, -20022, -24085, -12020, -16083, -3762, -7825, 4224, 161, 12482, 8419, 20484, 16421, 28742, 24679,
        -31815, -27752, -23557, -19494, -15555, -11492, -7297, -3234, 689, 4752, 8947, 13010, 16949, 21012, 25207, 29270,
        -18966, -23093, -27224, -31351, -2706, -6833, -10964, -15091, 13538, 9411, 5280, 1153, 29798, 25671, 21540, 17413,
        -22565, -18438, -30823, -26696, -6305, -2178, -14563, -10436, 9939, 14066, 1681, 5808, 26199, 30326, 17941, 22068,
        -9908, -13971, -1778, -5841, -26168, -30231, -18038, -22101, 22596, 18533, 30726, 26663, 6336, 2273, 14466, 10403,
        -13443, -9380, -5313, -1250, -29703, -25640, -21573, -17510, 19061, 23124, 27191, 31254, 2801, 6864, 10931, 14994,
        -722, -4849, -8852, -12979, -16982, -21109, -25112, -29239, 31782, 27655, 23652, 19525, 15522, 11395, 7392, 3265,
        -4321, -194, -12451, -8324, -20581, -16454, -28711, -24584, 28183, 32310, 20053, 24180, 11923, 16050, 3793, 7920
    )

    fun compute(data: ByteArray, length: Int): Short {
        var s = 0
        for (i in 0 until length) {
            val idx = ((s ushr 8) xor (data[i].toInt() and 0xFF)) and 0xFF
            s = (TABLE[idx] xor (s shl 8)) and 0xFFFF
        }
        return s.toShort()
    }
}

/**
 * Huawei SPP protocol.
 *
 * Frame format:  5A [len_hi] [len_lo] 00 [svc] [cmd] [TLV params...] [crc_hi] [crc_lo]
 *
 * 5A:        frame header
 * len:       2-byte big-endian = total bytes after the 4-byte header
 * 00:        padding byte
 * svc:       1-byte service ID (0x0A=battery, 0x02=ANC, 0x01=generic)
 * cmd:       1-byte command ID
 * TLV:       [type:1][len:1][value:len] repeated
 * CRC16:     XMODEM, over [5A + len + 00 + svc + cmd + TLV]
 *
 * Confirmed from Huawei SmartAudio app logs:
 *   received length: 164  data: 5a00a0000a0d01...
 *   => 5A + len=0x00A0(160) + 00 + svc=0x0A + cmd=0x0D + TLV...
 */
object SppProtocol {
    const val HEADER: Byte = 0x5A
    const val PADDING: Byte = 0x00

    fun isFrameStart(b1: Byte) = b1 == HEADER

    /** Extract body (svc+cmd+TLV) from a complete SPP frame, stripping CRC. */
    fun extractBody(frame: ByteArray): ByteArray? {
        if (frame.size < 7 || frame[0] != HEADER) return null
        val bodyLen = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
        val totalSize = 4 + bodyLen
        if (frame.size < totalSize) return null
        // Body at offset 4; last 2 bytes are CRC
        return frame.copyOfRange(4, totalSize - 2)
    }

    /** Build a complete SPP frame for sending to the headset. */
    fun buildFrame(svc: Byte, cmd: Byte, vararg params: Pair<Byte, ByteArray>): ByteArray {
        var body = byteArrayOf(svc, cmd)
        for ((type, value) in params) {
            body += byteArrayOf(type, value.size.toByte()) + value
        }
        val header = byteArrayOf(HEADER, 0, 0, PADDING)
        val bodyWithCrc = body + byteArrayOf(0, 0)
        val totalLen = bodyWithCrc.size
        header[1] = ((totalLen shr 8) and 0xFF).toByte()
        header[2] = (totalLen and 0xFF).toByte()
        val pkt = header + bodyWithCrc
        val preCrc = pkt.copyOfRange(0, pkt.size - 2)
        val crc = HuaweiCRC.compute(preCrc, preCrc.size)
        pkt[pkt.size - 2] = ((crc.toInt() ushr 8) and 0xFF).toByte()
        pkt[pkt.size - 1] = (crc.toInt() and 0xFF).toByte()
        return pkt
    }

    /** Build a read-request frame (empty parameter placeholders). */
    fun buildReadFrame(svc: Byte, cmd: Byte, vararg paramTypes: Byte): ByteArray {
        return buildFrame(svc, cmd, *paramTypes.map { it to byteArrayOf() }.toTypedArray())
    }
}

/** MBB (Huawei) command definitions. */
object MbbCmd {
    // Battery read (svc=0x01, cmd=0x08, params 1,2,3)
    val QUERY_BATTERY: ByteArray by lazy {
        SppProtocol.buildReadFrame(0x01, 0x08, 0x01, 0x02, 0x03)
    }
    // ANC read (svc=0x2b, cmd=0x2a, params 1,2)
    val QUERY_ANC: ByteArray by lazy {
        SppProtocol.buildReadFrame(0x2b, 0x2a, 0x01, 0x02)
    }
    // ANC write (svc=0x2b, cmd=0x04, param 1 = [mode_byte, 0x00/0xff])
    fun ancCommand(mode: NoiseControlMode): ByteArray {
        val modeByte = when (mode) {
            NoiseControlMode.OFF -> 0x00
            NoiseControlMode.NOISE_CANCELLATION -> 0x01
            NoiseControlMode.TRANSPARENCY -> 0x02
            NoiseControlMode.ADAPTIVE -> 0x03
        }
        val data = byteArrayOf(modeByte.toByte(), if (modeByte == 0) 0x00 else 0xFF.toByte())
        return SppProtocol.buildFrame(0x2b, 0x04, 0x01.toByte() to data)
    }
}

/**
 * Huawei SPP frame parser for stream-based reconstruction.
 */
class HuaweiPacketFramer {
    private var pending = ByteArray(0)

    fun append(buffer: ByteArray, length: Int): List<ByteArray> {
        if (length <= 0) return emptyList()
        pending += buffer.copyOfRange(0, length)
        val frames = mutableListOf<ByteArray>()
        while (pending.size >= 7) {
            var start = -1
            for (i in pending.indices) {
                if (SppProtocol.isFrameStart(pending[i])) { start = i; break }
            }
            if (start < 0) { pending = ByteArray(0); break }
            if (start > 0) pending = pending.copyOfRange(start, pending.size)
            if (pending.size < 7) break
            val bodyLen = ((pending[1].toInt() and 0xFF) shl 8) or (pending[2].toInt() and 0xFF)
            val frameLen = 4 + bodyLen
            if (bodyLen < 3 || frameLen > 4096) {
                pending = pending.copyOfRange(1, pending.size); continue
            }
            if (pending.size < frameLen) break
            frames += pending.copyOfRange(0, frameLen)
            pending = pending.copyOfRange(frameLen, pending.size)
        }
        return frames
    }
}

// --- Battery ---

data class BatteryInfo(val level: Int, val isCharging: Boolean)
data class BatteryResult(
    val global: Int?,
    val left: BatteryInfo?,
    val right: BatteryInfo?,
    val case: BatteryInfo?
)

object BatteryParser {
    /**
     * Parse SPP frame for battery.
     * svc=0x0A,cmd=0x0D (battery report, from logs)
     * svc=0x01,cmd=0x08 (query response)
     * TLV: type=2 -> left/right/case levels (3 bytes)
     *      type=3 -> charging flags
     */
    fun parse(frame: ByteArray): BatteryResult? {
        val body = SppProtocol.extractBody(frame) ?: return null
        if (body.size < 6) return null
        val svc = body[0].toInt() and 0xFF
        val cmd = body[1].toInt() and 0xFF
        val isBattery = (svc == 0x0A && cmd == 0x0D) || (svc == 0x01 && cmd == 0x08)
        if (!isBattery) return null
        return parseTlv(body, 2)
    }

    private fun parseTlv(data: ByteArray, off: Int): BatteryResult? {
        var i = off; var g: Int? = null
        var lv = -1; var rv = -1; var cv = -1
        var lc = false; var rc = false; var cc = false
        try {
            while (i < data.size - 2) {
                val tag = data[i].toInt() and 0xFF
                val len = data[i + 1].toInt() and 0xFF
                val vs = i + 2
                if (vs + len > data.size) break
                when (tag) {
                    1 -> { if (len >= 1) g = data[vs].toInt() and 0xFF }
                    2 -> {
                        if (len >= 1) lv = data[vs].toInt() and 0xFF
                        if (len >= 2) rv = data[vs + 1].toInt() and 0xFF
                        if (len >= 3) cv = data[vs + 2].toInt() and 0xFF
                    }
                    3 -> {
                        if (len >= 1) lc = data[vs].toInt() == 1
                        if (len >= 2) rc = data[vs + 1].toInt() == 1
                        if (len >= 3) cc = data[vs + 2].toInt() == 1
                    }
                }
                i = vs + len
            }
        } catch (_: Exception) { return null }
        if (lv != -1 && rv != -1) {
            if (kotlin.math.abs(lv - rv) <= 15 && lv > 0 && rv > 0)
                if (lv > rv) lv = rv else rv = lv
            return BatteryResult(g,
                BatteryInfo(lv, lc), BatteryInfo(rv, rc),
                if (cv >= 0) BatteryInfo(cv, cc) else null)
        }
        return null
    }
}

// --- ANC ---

enum class NoiseControlMode(val value: Int) {
    OFF(0), NOISE_CANCELLATION(1), TRANSPARENCY(2), ADAPTIVE(3);
    companion object {
        fun fromByte(b: Byte): NoiseControlMode? =
            entries.firstOrNull { it.value == (b.toInt() and 0xFF) }
    }
}

object AncModeParser {
    /** Parse SPP frame for ANC mode. svc=0x2b, cmd=0x2a/0x03/0x04. */
    fun parse(frame: ByteArray): NoiseControlMode? {
        val body = SppProtocol.extractBody(frame) ?: return null
        if (body.size < 4) return null
        val svc = body[0].toInt() and 0xFF
        val cmd = body[1].toInt() and 0xFF
        if (svc != 0x2b || (cmd != 0x2a && cmd != 0x03 && cmd != 0x04)) return null
        return parseTlv(body, 2)
    }

    private fun parseTlv(data: ByteArray, off: Int): NoiseControlMode? {
        var i = off
        try {
            while (i < data.size - 2) {
                val tag = data[i].toInt() and 0xFF
                val len = data[i + 1].toInt() and 0xFF
                val vs = i + 2
                if (vs + len > data.size) break
                if (tag == 1 && len >= 2)
                    return NoiseControlMode.fromByte(data[vs + 1])
                i = vs + len
            }
        } catch (_: Exception) {}
        return null
    }
}