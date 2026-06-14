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

        // 🚀 终极杀招一：使用 Insecure (无感非加密) 通道！
        // 这是对付华为 FreeBuds MBB 协议拒收 (-1) 的最核心武器
        for (uuid in preferredUuids) {
            try {
                Log.d(logTag, "LSPosed特权引擎 -> 尝试 Insecure (无感非加密) UUID: $uuid")
                val socket = device.createInsecureRfcommSocketToServiceRecord(uuid)
                socket.connect()
                Log.d(logTag, "💥 成功！华为 FreeBuds 通过 Insecure UUID 完美并网！")
                return socket
            } catch (e: Exception) {
                failures.add(e)
            }
        }

        // 🚀 终极杀招二：暴力扫射华为隐藏的 Insecure 物理信道
        val channelsToTry = listOf(1, 2, 3, 4, 15, 5, 6, 7, 8, 9, 10)
        for (channel in channelsToTry) {
            try {
                Log.d(logTag, "尝试暴力破解 Insecure 隐藏信道: Channel $channel")
                // 注意这里调用的是 Insecure (非加密) 的隐藏底层反射接口！
                val method = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                method.isAccessible = true
                val socket = method.invoke(device, channel) as BluetoothSocket
                socket.connect()
                Log.d(logTag, "💥 大满贯！华为 FreeBuds 已在 Insecure Channel $channel 强行建立通信隧道！")
                return socket
            } catch (e: Exception) {
                failures.add(e)
            }
        }

        // 🛡️ 备用防线：回退尝试传统 Secure (加密) 通道
        for (uuid in preferredUuids) {
            try {
                Log.d(logTag, "退回尝试传统 Secure (加密) UUID: $uuid")
                val socket = device.createRfcommSocketToServiceRecord(uuid)
                socket.connect()
                return socket
            } catch (e: Exception) {
                failures.add(e)
            }
        }

        throw IOException("通信彻底坍塌：所有 Insecure 与 Secure 信道均被华为固件拒绝 (-1)", failures.lastOrNull())
    }
}