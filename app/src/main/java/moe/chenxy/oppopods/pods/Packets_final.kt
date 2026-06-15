package moe.chenxy.oppopods.pods

import android.util.Log
import kotlin.math.abs

// ============================================================================
// CRC16-XMODEM for Huawei SPP protocol.
// Ported directly from OpenFreebuds (melianmiko/OpenFreebuds) - verified working.
// ============================================================================
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

    fun compute(data: ByteArray): Short {
        var s = 0
        for (i in data.indices) {
            val idx = ((s ushr 8) xor (data[i].toInt() and 0xFF)) and 0xFF
            s = (TABLE[idx] xor (s shl 8)) and 0xFFFF
        }
        return s.toShort()
    }
}

// ============================================================================
// Huawei SPP Protocol - ported from OpenFreebuds HuaweiSppPackage
//
// Frame format:  5A [len_hi] [len_lo] 00 [cmd_hi] [cmd_lo] [TLV...] [CRC_hi] [CRC_lo]
//
//   5A:        magic header byte
//   len:       2-byte big-endian = len(body) + 1  (body = cmd(2) + TLV)
//   00:        padding byte
//   cmd:       2-byte command ID = [svc_byte, cmd_byte]
//   TLV:       type(1) + length(1) + value(length) repeated
//   CRC:       CRC16-XMODEM over everything before CRC bytes
// ============================================================================
object SppProtocol {
    const val HEADER: Byte = 0x5A

    /** Build a complete SPP frame from command + TLV params (matching OpenFreebuds to_bytes()). */
    fun buildFrame(svc: Byte, cmd: Byte, vararg params: Pair<Byte, ByteArray>): ByteArray {
        var body = byteArrayOf(svc, cmd)
        for ((type, value) in params) {
            body += byteArrayOf(type, value.size.toByte()) + value
        }
        val lengthField = (body.size + 1) and 0xFFFF
        val header = byteArrayOf(HEADER, (lengthField shr 8).toByte(), lengthField.toByte(), 0x00)
        val pkt = header + body
        val pktWithCrc = pkt + HuaweiCRC.compute(pkt).toBytes()
        return pktWithCrc
    }

    /** Build a read-request frame (empty parameter placeholders). */
    fun buildReadFrame(svc: Byte, cmd: Byte, vararg paramTypes: Byte): ByteArray {
        val tlvParams = paramTypes.map { it to byteArrayOf() }.toTypedArray()
        return buildFrame(svc, cmd, *tlvParams)
    }

    /** Check if a byte is the frame start marker. */
    fun isFrameStart(b1: Byte) = b1 == HEADER

    /**
     * Parse a complete SPP frame.
     * Returns: Pair(commandId, paramMap) or null if invalid.
     * Matching OpenFreebuds from_bytes().
     */
    fun parseFrame(frame: ByteArray): Pair<ByteArray, Map<Int, ByteArray>>? {
        if (frame.size < 7 || frame[0] != HEADER || frame[3] != 0x00.toByte()) return null
        val length = ((frame[1].toInt() and 0xFF) shl 8) or (frame[2].toInt() and 0xFF)
        val totalSize = 4 + length
        val frameLen = frame.size
        if (frameLen < totalSize) return null

        val cmdId = byteArrayOf(frame[4], frame[5])
        val params = mutableMapOf<Int, ByteArray>()

        var pos = 6
        while (pos < length + 3 && pos + 1 < frameLen) {
            val tag = frame[pos].toInt() and 0xFF
            val tlvLen = frame[pos + 1].toInt() and 0xFF
            val valueStart = pos + 2
            if (valueStart + tlvLen > frameLen) break
            val value = frame.copyOfRange(valueStart, valueStart + tlvLen)
            params[tag] = value
            pos = valueStart + tlvLen
        }
        return Pair(cmdId, params)
    }
}

fun Short.toBytes(): ByteArray = byteArrayOf(
    ((this.toInt() ushr 8) and 0xFF).toByte(),
    (this.toInt() and 0xFF).toByte()
)

// ============================================================================
// Packet framer for reconstructing frames from stream data.
// ============================================================================
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
            if (bodyLen < 3 || bodyLen > 4090) {
                pending = pending.copyOfRange(1, pending.size); continue
            }
            val frameLen = 4 + bodyLen
            if (pending.size < frameLen) break
            frames += pending.copyOfRange(0, frameLen)
            pending = pending.copyOfRange(frameLen, pending.size)
        }
        return frames
    }
}

// ============================================================================
// Command definitions - matching OpenFreebuds spp_commands.py
// ============================================================================
object MbbCmd {
    val QUERY_BATTERY: ByteArray by lazy {
        SppProtocol.buildReadFrame(0x01, 0x08, 0x01, 0x02, 0x03)
    }
    val QUERY_ANC: ByteArray by lazy {
        SppProtocol.buildReadFrame(0x2b, 0x2a, 0x01, 0x02)
    }
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

// ============================================================================
// Battery handler - matching OpenFreebuds handler/battery.py
//
// Commands:
//   CMD_BATTERY_READ   = b"\x01\x08"  - query response
//   CMD_BATTERY_NOTIFY = b"\x01\x27"  - unsolicited notification
// TLV params:
//   param 1: [global%%]           (1 byte)
//   param 2: [left, right, case] (3 bytes)
//   param 3: [charging_flags]    (>=1 bytes, 0x01 = charging)
// ============================================================================
data class BatteryInfo(val level: Int, val isCharging: Boolean)
data class BatteryResult(
    val global: Int?,
    val left: BatteryInfo?,
    val right: BatteryInfo?,
    val case: BatteryInfo?
)

object BatteryParser {
    fun parse(frame: ByteArray): BatteryResult? {
        val parsed = SppProtocol.parseFrame(frame) ?: return null
        val (cmdId, params) = parsed
        val isBattery = (cmdId[0].toInt() and 0xFF == 0x01) &&
                ((cmdId[1].toInt() and 0xFF == 0x27) || (cmdId[1].toInt() and 0xFF == 0x08))
        if (!isBattery) return null

        var global: Int? = null
        var left = -1; var right = -1; var case_ = -1
        var leftCharging = false; var rightCharging = false; var caseCharging = false

        if (params.containsKey(1) && params[1]!!.size >= 1) {
            global = params[1]!![0].toInt() and 0xFF
        }
        if (params.containsKey(2) && params[2]!!.size >= 1) {
            left = params[2]!![0].toInt() and 0xFF
            if (params[2]!!.size >= 2) right = params[2]!![1].toInt() and 0xFF
            if (params[2]!!.size >= 3) case_ = params[2]!![2].toInt() and 0xFF
        }
        if (params.containsKey(3)) {
            val charging = params[3]!!
            if (charging.size >= 1) leftCharging = charging[0].toInt() == 1
            if (charging.size >= 2) rightCharging = charging[1].toInt() == 1
            if (charging.size >= 3) caseCharging = charging[2].toInt() == 1
        }

        if (left >= 0 && right >= 0) {
            if (abs(left - right) <= 15 && left > 0 && right > 0) {
                if (left > right) left = right else right = left
            }
            return BatteryResult(
                global,
                BatteryInfo(left, leftCharging),
                BatteryInfo(right, rightCharging),
                if (case_ >= 0) BatteryInfo(case_, caseCharging) else null
            )
        }
        return null
    }
}

// ============================================================================
// ANC handler - matching OpenFreebuds handler/anc.py
//
// Commands:
//   CMD_ANC_READ  = b"\x2b\x2a"  - query/response
//   CMD_ANC_WRITE = b"\x2b\x04"  - write
// TLV param 1: [level_byte, mode_byte] (2 bytes)
//   mode: 0=normal(off), 1=cancellation, 2=awareness(transparency)
// ============================================================================
enum class NoiseControlMode(val value: Int) {
    OFF(0), NOISE_CANCELLATION(1), TRANSPARENCY(2), ADAPTIVE(3);
    companion object {
        fun fromByte(b: Byte): NoiseControlMode? =
            entries.firstOrNull { it.value == (b.toInt() and 0xFF) }
    }
}

object AncModeParser {
    fun parse(frame: ByteArray): NoiseControlMode? {
        val parsed = SppProtocol.parseFrame(frame) ?: return null
        val (cmdId, params) = parsed
        val svc = cmdId[0].toInt() and 0xFF
        val cmd = cmdId[1].toInt() and 0xFF
        if (svc != 0x2b || (cmd != 0x2a && cmd != 0x03 && cmd != 0x04)) return null
        if (params.containsKey(1) && params[1]!!.size >= 2) {
            return NoiseControlMode.fromByte(params[1]!![1])
        }
        return null
    }
}