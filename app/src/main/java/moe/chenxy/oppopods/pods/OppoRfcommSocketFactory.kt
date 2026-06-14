package moe.chenxy.oppopods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.UUID

object OppoRfcommSocketFactory {
    // 🎯 斩断 HeyMelody 常量，强行注入全平台经典蓝牙串口 SPP 安全通信句柄 UUID
    private val preferredUuids = listOf(
        UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    )

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    fun connect(
        device: BluetoothDevice,
        logTag: String,
        connectionMethod: RfcommConnectionMethod = RfcommConnectionMethod.UUID
    ): BluetoothSocket {
        val failures = mutableListOf<Exception>()
        for (uuid in preferredUuids) {
            val socket = try {
                Log.d(logTag, "LSPosed特权并网 -> Creating RFCOMM via static UUID: $uuid")
                device.createRfcommSocketToServiceRecord(uuid)
            } catch (e: Exception) {
                failures += e
                continue
            }
            try {
                socket.connect()
                Log.d(logTag, "Huawei RFCOMM channel safely linked inside host bluetooth stack!")
                return socket
            } catch (e: Exception) {
                failures += e
                try { socket.close() } catch (_: IOException) {}
            }
        }
        throw IOException("Xposed Core Exception: Unable to establish RFCOMM pipe via universal service record", failures.lastOrNull())
    }
}