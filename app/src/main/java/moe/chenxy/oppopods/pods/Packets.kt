package moe.chenxy.oppopods.pods

import android.util.Log

/**
 * 🎯 华为原厂高阶查表式 CRC16 算力矩阵
 * 统一采用 IntArray 表达，彻底绝杀 Kotlin 短整型字面量溢出的编译报错
 */
object HuaweiCRC {
    private val CRC16_TABLE = intArrayOf(
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

    fun compute(bArr: ByteArray, length: Int): Short {
        var s10 = 0
        for (i11 in 0 until length) {
            val index = ((s10 ushr 8) xor (bArr[i11].toInt() and 0xFF)) and 0xFF
            s10 = (CRC16_TABLE[index] xor (s10 shl 8)) and 0xFFFF
        }
        return s10.toShort()
    }
}

/**
 * 🎯 无损替换为华为原厂标准的 [5A 01] 物理流数据帧分配滑动滑窗状态机
 */
class OppoPacketFramer {
    private var pending = ByteArray(0)
    fun append(buffer: ByteArray, length: Int): List<ByteArray> {
        if (length <= 0) return emptyList()
        pending += buffer.copyOfRange(0, length)
        val frames = mutableListOf<ByteArray>()
        while (pending.size >= 9) {
            var start = -1
            for (i in 0 until pending.size - 1) {
                if (pending[i] == 0x5A.toByte() && pending[i + 1] == 0x01.toByte()) {
                    start = i
                    break
                }
            }
            if (start < 0) {
                pending = if (pending.isNotEmpty()) byteArrayOf(pending.last()) else ByteArray(0)
                break
            }
            if (start > 0) {
                pending = pending.copyOfRange(start, pending.size)
            }
            if (pending.size < 9) break
            
            val lenMSB = pending[3].toInt() and 0xFF
            val lenLSB = pending[4].toInt() and 0xFF
            val innerLen = (lenMSB shl 8) or lenLSB
            val frameLen = 7 + innerLen
            
            if (innerLen < 2 || frameLen > 512) {
                pending = pending.copyOfRange(1, pending.size)
                continue
            }
            if (pending.size < frameLen) break
            
            frames += pending.copyOfRange(0, frameLen)
            pending = pending.copyOfRange(frameLen, pending.size)
        }
        return frames
    }
}

object OppoPackets {
    /**
     * 🎯 接管所有的发送拼包逻辑，完全改写为带强类型的华为物理大帧
     */
    fun buildPacket(cmd: Int, seq: Int = 0xF0, payload: ByteArray = byteArrayOf()): ByteArray {
        val serviceId: Byte = 1.toByte()
        var cmdId: Byte = 0x42.toByte() 
        var finalPayload = payload

        if (cmd == Cmd.SET_ANC) {
            cmdId = 0x42.toByte()
            if (payload.isNotEmpty()) {
                val modeByte = payload.last().toInt() and 0xFF
                finalPayload = when (modeByte) {
                    0x01 -> byteArrayOf(43, 4, 1, 2, 1, 0) // 关闭
                    0x02 -> byteArrayOf(43, 4, 1, 2, 1, 1) // 降噪
                    0x04 -> byteArrayOf(43, 4, 1, 2, 2, 1) // 透传
                    else -> byteArrayOf(43, 4, 1, 2, 1, 1)
                }
            }
        }

        val packetLength = 2 + finalPayload.size
        val lenMSB = ((packetLength ushr 8) and 0xFF).toByte()
        val lenLSB = (packetLength and 0xFF).toByte()

        val checkBlock = ByteArray(5 + finalPayload.size)
        checkBlock[0] = seq.toByte()
        checkBlock[1] = lenMSB
        checkBlock[2] = lenLSB
        checkBlock[3] = serviceId
        checkBlock[4] = cmdId
        System.arraycopy(finalPayload, 0, checkBlock, 5, finalPayload.size)

        val crcValue = HuaweiCRC.compute(checkBlock, checkBlock.size)

        val finalPacket = ByteArray(2 + checkBlock.size + 2)
        finalPacket[0] = 0x5A.toByte()
        finalPacket[1] = 0x01.toByte()
        System.arraycopy(checkBlock, 0, finalPacket, 2, checkBlock.size)
        finalPacket[finalPacket.size - 2] = ((crcValue.toInt() ushr 8) and 0xFF).toByte()
        finalPacket[finalPacket.size - 1] = (crcValue.toInt() and 0xFF).toByte()

        return finalPacket
    }
}

enum class NoiseControlMode { OFF, NOISE_CANCELLATION, ADAPTIVE, TRANSPARENCY }
object BatteryComponent { const val LEFT = 1; const val RIGHT = 2; const val CASE = 3 }
object GameModeFeature { const val LOW_LATENCY = 0x06; const val MAIN = 0x28 }
object Cmd {
    const val SET_ANC = 0x0404
    const val SET_GAME_MODE = 0x0403
    const val QUERY_BATTERY = 0x0106
    const val BATTERY_RESPONSE = 0x8106
    const val ANC_MODE_NOTIFY = 0x0204
    const val QUERY_STATUS = 0x010D
    const val QUERY_STATUS_RESPONSE = 0x810D
}

object Enums {
    val ANC_NOISE_CANCEL: ByteArray by lazy { OppoPackets.buildPacket(Cmd.SET_ANC, 0, byteArrayOf(0x01, 0x01, 0x02)) }
    val ANC_TRANSPARENCY: ByteArray by lazy { OppoPackets.buildPacket(Cmd.SET_ANC, 0, byteArrayOf(0x01, 0x01, 0x04)) }
    val ANC_OFF: ByteArray by lazy { OppoPackets.buildPacket(Cmd.SET_ANC, 0, byteArrayOf(0x01, 0x01, 0x01)) }
    val ANC_ADAPTIVE: ByteArray by lazy { OppoPackets.buildPacket(Cmd.SET_ANC, 0, byteArrayOf(0x01, 0x01, 0x02)) }

    private fun makeHuaweiHeartbeat(): ByteArray {
        val checkBlock = byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x02.toByte(), 0x01.toByte(), 0x02.toByte())
        val crc = HuaweiCRC.compute(checkBlock, checkBlock.size)
        return byteArrayOf(
            0x5A.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x02.toByte(), 0x01.toByte(), 0x02.toByte(),
            ((crc.toInt() ushr 8) and 0xFF).toByte(), (crc.toInt() and 0xFF).toByte()
        )
    }

    val QUERY_BATTERY: ByteArray by lazy { makeHuaweiHeartbeat() }
    val QUERY_ANC: ByteArray by lazy { makeHuaweiHeartbeat() }
    val QUERY_STATUS: ByteArray by lazy { makeHuaweiHeartbeat() }

    fun gameModePackets(enabled: Boolean, implementation: Any): List<ByteArray> = emptyList()
}

/**
 * 🎯 承接原厂变长解包事实，清洗脱壳出无损电量参数 facts
 */
object BatteryParser {
    data class BatteryInfo(val level: Int, val isCharging: Boolean)
    data class BatteryResult(val left: BatteryInfo?, val right: BatteryInfo?, val case: BatteryInfo?)

    fun parse(data: ByteArray): BatteryResult? {
        if (data.size < 9 || data[0] != 0x5A.toByte() || data[1] != 0x01.toByte()) return null
        if ((data[5].toInt() and 0xFF) != 1 || (data[6].toInt() and 0xFF) != 39) return null

        var i10 = 7
        var leftLevel = -1; var rightLevel = -1; var caseLevel = -1
        var leftCharging = false; var rightCharging = false; var caseCharging = false

        try {
            while (i10 < data.size - 2) {
                val b10 = data[i10]
                val i12 = i10 + 1
                val length: Int
                val i11: Int
                val b12Unsigned = data[i12].toInt() and 0xFF
                if ((b12Unsigned and 128) != 0) {
                    val b10Plus2Unsigned = data[i10 + 2].toInt() and 0xFF
                    length = ((b12Unsigned and 127) shl 7) + (b10Plus2Unsigned and 127)
                    i11 = i10 + 3
                } else {
                    length = b12Unsigned and 127
                    i11 = i10 + 2
                }
                val length2 = i11 + length
                if (length2 > data.size) break

                val block = data.copyOfRange(i11, length2)
                when (b10.toInt() and 0xFF) {
                    2 -> {
                        if (block.size >= 3) {
                            leftLevel = block[0].toInt() and 0xFF
                            rightLevel = block[1].toInt() and 0xFF
                            caseLevel = block[2].toInt() and 0xFF
                        }
                    }
                    3 -> {
                        if (block.size >= 3) {
                            leftCharging = block[0].toInt() == 1
                            rightCharging = block[1].toInt() == 1
                            caseCharging = block[2].toInt() == 1
                        }
                    }
                }
                i10 = length2
            }
            if (leftLevel != -1 && rightLevel != -1) {
                if (Math.abs(leftLevel - rightLevel) <= 15 && leftLevel > 0 && rightLevel > 0) {
                    if (leftLevel > rightLevel) leftLevel = rightLevel else rightLevel = leftLevel
                }
                return BatteryResult(
                    left = BatteryInfo(leftLevel, leftCharging),
                    right = BatteryInfo(rightLevel, rightCharging),
                    case = BatteryInfo(caseLevel, caseCharging)
                )
            }
        } catch (e: Exception) {}
        return null
    }

    fun parseActiveReport(data: ByteArray): BatteryResult? = parse(data)
}

object AncModeParser {
    fun parse(data: ByteArray): NoiseControlMode? {
        if (data.size < 9 || data[0] != 0x5A.toByte() || data[1] != 0x01.toByte()) return null
        if ((data[5].toInt() and 0xFF) != 1 || (data[6].toInt() and 0xFF) != 66) return null
        return when (data[7].toInt() and 0xFF) {
            0 -> NoiseControlMode.OFF
            1 -> NoiseControlMode.NOISE_CANCELLATION
            2 -> NoiseControlMode.TRANSPARENCY
            else -> null
        }
    }
}

object GameModeParser { fun parse(data: ByteArray, implementation: Any): Boolean? = null }
object SwitchFeatureSetParser {
    class Result(val status: Int, val value: Int?)
    fun parse(data: ByteArray): Result? = null
}