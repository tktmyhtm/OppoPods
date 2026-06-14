package moe.chenxy.oppopods.pods

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.util.Log
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import java.util.UUID

@SuppressLint("MissingPermission")
object RfcommController {
    private const val TAG = "OppoPods-GATT"
    
    // 🎯 直接挂载咱们挖出来的华为 FreeBuds 真实 BLE GATT 通信矩阵！
    private val UUID_SERVICE = UUID.fromString("0000fe01-0000-1000-8000-00805f9b34fb")
    private val UUID_NOTIFY = UUID.fromString("0000fe02-0000-1000-8000-00805f9b34fb")
    private val UUID_WRITE = UUID.fromString("0000fe03-0000-1000-8000-00805f9b34fb")
    private val UUID_DESCR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") // 开启监听的标准描述符

    private var currentGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private var context: Context? = null
    private var currentDevice: BluetoothDevice? = null
    private var commandReceiver: BroadcastReceiver? = null
    
    private val framer = OppoPacketFramer()

    fun connectPod(ctx: Context, device: BluetoothDevice, prefs: SharedPreferences?) {
        context = ctx.applicationContext
        currentDevice = device
        
        Log.d(TAG, "LSPosed引擎 -> 彻底抛弃旧串口，启动纯血华为 BLE GATT 协议栈: ${device.address}")
        
        disconnect()
        registerReceiver()
        
        // Android 16/HyperOS 特权进程直接发起无感低功耗并网！
        currentGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnectedPod(ctx: Context, device: BluetoothDevice) {
        if (currentDevice?.address == device.address) {
            disconnect()
            broadcastDisconnected(ctx, device)
        }
    }

    private fun disconnect() {
        try {
            currentGatt?.disconnect()
            currentGatt?.close()
        } catch (e: Exception) {}
        currentGatt = null
        writeChar = null
        isConnected = false
    }

    private fun registerReceiver() {
        if (commandReceiver == null) {
            commandReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    when (intent?.action) {
                        OppoPodsAction.ACTION_ANC_SELECT -> {
                            val status = intent.getIntExtra("status", 1)
                            val payload = when (status) {
                                1 -> Enums.ANC_OFF
                                2 -> Enums.ANC_NOISE_CANCEL
                                3 -> Enums.ANC_TRANSPARENCY
                                else -> Enums.ANC_OFF
                            }
                            sendCommand(payload)
                        }
                        OppoPodsAction.ACTION_REFRESH_STATUS -> {
                            sendCommand(Enums.QUERY_BATTERY)
                            sendCommand(Enums.QUERY_ANC)
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(OppoPodsAction.ACTION_ANC_SELECT)
                addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
            }
            context?.registerReceiver(commandReceiver, filter, Context.RECEIVER_EXPORTED)
        }
    }

    private fun sendCommand(cmd: ByteArray) {
        val gatt = currentGatt ?: return
        val char = writeChar ?: return
        try {
            @Suppress("DEPRECATION")
            char.value = cmd
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
            Log.d(TAG, "🚀 BLE 通道下发高维指令: ${cmd.joinToString("") { "%02X".format(it) }}")
        } catch (e: Exception) {
            Log.e(TAG, "GATT 指令下发坍塌", e)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "✅ BLE 物理链路已握手！开始扫描 fe01 私有服务...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.e(TAG, "❌ BLE 链路断开")
                disconnect()
                context?.let { currentDevice?.let { dev -> broadcastDisconnected(it, dev) } }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(UUID_SERVICE)
                if (service == null) {
                    Log.e(TAG, "❌ 致命错误：当前耳机未暴露华为 MBB 服务 $UUID_SERVICE")
                    return
                }
                
                writeChar = service.getCharacteristic(UUID_WRITE)
                val notifyChar = service.getCharacteristic(UUID_NOTIFY)
                
                if (notifyChar != null) {
                    gatt.setCharacteristicNotification(notifyChar, true)
                    val descriptor = notifyChar.getDescriptor(UUID_DESCR)
                    if (descriptor != null) {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                        Log.d(TAG, "✅ 已强行挂载 Notify 监听队列: $UUID_NOTIFY")
                    }
                }
                
                isConnected = true
                Log.d(TAG, "🎉 大满贯！华为 FreeBuds 彻底通过 BLE GATT 并网！")
                context?.let { currentDevice?.let { dev -> broadcastConnected(it, dev) } }
                
                // 🚀 主动连环发送心跳包，把它的电量和降噪状态逼出来
                sendCommand(Enums.QUERY_BATTERY)
                sendCommand(Enums.QUERY_ANC)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == UUID_NOTIFY) {
                @Suppress("DEPRECATION")
                val data = characteristic.value ?: return
                processData(data)
            }
        }
    }

    private fun processData(data: ByteArray) {
        val frames = framer.append(data, data.size)
        for (frame in frames) {
            Log.d(TAG, "📥 捕获底层 MBB 帧: ${frame.joinToString("") { "%02X".format(it) }}")
            
            val battery = BatteryParser.parse(frame)
            if (battery != null) {
                Log.d(TAG, "🔋 脱壳电量: 左${battery.left?.level} 右${battery.right?.level} 舱${battery.case?.level}")
                broadcastBattery(battery)
            }
            
            val anc = AncModeParser.parse(frame)
            if (anc != null) {
                Log.d(TAG, "🎧 脱壳降噪模式: $anc")
                broadcastAnc(anc)
            }
        }
    }

    private fun broadcastConnected(ctx: Context, device: BluetoothDevice) {
        val intent = Intent(OppoPodsAction.ACTION_PODS_CONNECTED).apply {
            putExtra("address", device.address)
            putExtra("device_name", device.name ?: "Huawei FreeBuds")
            setPackage("com.android.settings")
        }
        ctx.sendBroadcast(intent)
        intent.setPackage("com.milink.service")
        ctx.sendBroadcast(intent)
    }

    private fun broadcastDisconnected(ctx: Context, device: BluetoothDevice) {
        val intent = Intent(OppoPodsAction.ACTION_PODS_DISCONNECTED).apply {
            putExtra("address", device.address)
            setPackage("com.android.settings")
        }
        ctx.sendBroadcast(intent)
        intent.setPackage("com.milink.service")
        ctx.sendBroadcast(intent)
    }

    private fun broadcastBattery(battery: BatteryParser.BatteryResult) {
        val ctx = context ?: return
        val intent = Intent(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED).apply {
            putExtra("address", currentDevice?.address)
            putExtra("left_battery", battery.left?.level ?: 0)
            putExtra("left_charging", battery.left?.isCharging ?: false)
            putExtra("left_connected", battery.left != null)
            putExtra("right_battery", battery.right?.level ?: 0)
            putExtra("right_charging", battery.right?.isCharging ?: false)
            putExtra("right_connected", battery.right != null)
            putExtra("case_battery", battery.case?.level ?: 0)
            putExtra("case_charging", battery.case?.isCharging ?: false)
            putExtra("case_connected", battery.case != null)
        }
        intent.setPackage("com.android.settings")
        ctx.sendBroadcast(intent)
        intent.setPackage("com.milink.service")
        ctx.sendBroadcast(intent)
    }

    private fun broadcastAnc(anc: NoiseControlMode) {
        val ctx = context ?: return
        val status = when (anc) {
            NoiseControlMode.OFF -> 1
            NoiseControlMode.NOISE_CANCELLATION -> 2
            NoiseControlMode.TRANSPARENCY -> 3
            else -> 1
        }
        val intent = Intent(OppoPodsAction.ACTION_PODS_ANC_CHANGED).apply {
            putExtra("address", currentDevice?.address)
            putExtra("status", status)
        }
        intent.setPackage("com.android.settings")
        ctx.sendBroadcast(intent)
        intent.setPackage("com.milink.service")
        ctx.sendBroadcast(intent)
    }
}