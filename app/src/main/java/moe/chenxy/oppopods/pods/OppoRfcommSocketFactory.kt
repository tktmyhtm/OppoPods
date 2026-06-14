package moe.chenxy.oppopods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.UUID

object OppoRfcommSocketFactory {
    private val preferredUuids = listOf(
        UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"), // 万能串口 SPP
        UUID.fromString("00000003-0000-1000-8000-00805f9b34fb")  // 华为常见 RFCOMM 变种
    )

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    fun connect(
        device: BluetoothDevice,
        logTag: String,
        connectionMethod: RfcommConnectionMethod = RfcommConnectionMethod.UUID
    ): BluetoothSocket {
        val failures = mutableListOf<Exception>()

        // 🚀 终极杀招：利用 LSPosed 最高豁免权，暴力扫射华为隐藏的物理射频信道！
        // 优先尝试华为最爱用的 Channel 1, 2, 4, 15，然后遍历 1-30 所有的底层门牌号
        val channelsToTry = listOf(1, 2, 4, 15, 3, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 18, 19, 20)
        
        for (channel in channelsToTry) {
            try {
                Log.d(logTag, "LSPosed特权引擎 -> 绕过防火墙，暴力强连隐藏通道 Channel $channel ...")
                // 只有在 LSPosed 进程里，这种极高危的反射才不会被 Android 16 熔断！
                val method = device::class.java.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                method.isAccessible = true
                val socket = method.invoke(device, channel) as BluetoothSocket
                socket.connect() // 发起强行握手
                
                Log.d(logTag, "💥 大满贯！华为 FreeBuds 已在 Channel $channel 彻底并网通信！")
                return socket
            } catch (e: Exception) {
                // 遇到 -1 拒收，直接静默换下一个通道继续轰炸
                failures.add(e)
            }
        }

        // 🚀 备用防线：如果物理信道全被屏蔽，退回测试公开 UUID
        for (uuid in preferredUuids) {
            try {
                Log.d(logTag, "物理通道扫射失败，尝试备用 UUID 通道: $uuid")
                val socket = device.createRfcommSocketToServiceRecord(uuid)
                socket.connect()
                Log.d(logTag, "💥 成功！华为 FreeBuds 通过通用 UUID 建立隧道！")
                return socket
            } catch (e: Exception) {
                failures.add(e)
            }
        }

        throw IOException("通信坍塌：所有物理信道与 UUID 均被华为底层固件拒绝！", failures.lastOrNull())
    }
}