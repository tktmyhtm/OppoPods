package moe.chenxy.oppopods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.UUID

object OppoRfcommSocketFactory {
    private val preferredUuids = listOf(
        UUID.fromString("00001101-0000-1000-8000-00805f9b34fb") // 万能经典蓝牙 SPP
    )

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    fun connect(
        device: BluetoothDevice,
        logTag: String,
        connectionMethod: RfcommConnectionMethod = RfcommConnectionMethod.UUID
    ): BluetoothSocket {
        val failures = mutableListOf<Exception>()

        // 🚀 核心战术：避开耳机刚连上时的音频链路分配高负载期
        Log.d(logTag, "A2DP链路已连，强制休眠 2.5 秒，等待 FreeBuds 硬件空闲...")
        try { Thread.sleep(2500) } catch (e: Exception) {}

        val maxRetries = 4 // 允许循环强突 4 次
        for (attempt in 1..maxRetries) {
            Log.d(logTag, "发起第 $attempt 波强连冲锋...")

            // 战术 1：尝试 Insecure 万能串口
            for (uuid in preferredUuids) {
                try {
                    val socket = device.createInsecureRfcommSocketToServiceRecord(uuid)
                    socket.connect()
                    Log.d(logTag, "💥 赢了！在第 $attempt 波冲锋中，通过 Insecure UUID 成功凿开通道！")
                    return socket
                } catch (e: Exception) {
                    failures.add(e)
                }
            }

            // 战术 2：暴力扫射隐藏底层物理信道
            val channelsToTry = listOf(1, 2, 4, 15)
            for (channel in channelsToTry) {
                try {
                    val method = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                    method.isAccessible = true
                    val socket = method.invoke(device, channel) as BluetoothSocket
                    socket.connect()
                    Log.d(logTag, "💥 赢了！在第 $attempt 波冲锋中，强行打通 Channel $channel 隐藏隧道！")
                    return socket
                } catch (e: Exception) {
                    failures.add(e)
                }
            }

            // 如果这一波全被 -1 踹回，说明硬件仍在忙碌，歇 1.5 秒再上
            if (attempt < maxRetries) {
                Log.w(logTag, "第 $attempt 波被硬件防火墙 (-1) 拒收，进入 1.5 秒战术冷却...")
                try { Thread.sleep(1500) } catch (e: Exception) {}
            }
        }

        throw IOException("通信最终坍塌：经过多次循环强突，物理信道依然被锁死！", failures.lastOrNull())
    }
}