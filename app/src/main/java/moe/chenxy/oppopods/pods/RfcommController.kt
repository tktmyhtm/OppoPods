package moe.chenxy.oppopods.pods

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import java.util.UUID

@SuppressLint("MissingPermission")
object RfcommController {
    private const val TAG = "OppoPods-GATT"
    
    private val UUID_SERVICE = UUID.fromString("0000fe01-0000-1000-8000-00805f9b34fb")
    private val UUID_NOTIFY = UUID.fromString("0000fe02-0000-1000-8000-00805f9b34fb")
    private val UUID_WRITE = UUID.fromString("0000fe03-0000-1000-8000-00805f9b34fb")
    private val UUID_DESCR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var currentGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private var context: Context? = null
    private var currentDevice: BluetoothDevice? = null
    private var commandReceiver: BroadcastReceiver? = null
    
    private val framer = OppoPacketFramer()
    private val mainHandler = Handler(Looper.getMainLooper())

    // 🛡️ 状态缓存池：用来拦截冗余广播
    private var lastBatteryResult: BatteryParser.BatteryResult? = null
    private var lastAncMode: NoiseControlMode? = null
    private var lastBroadcastTime = 0L

    fun connectPod(ctx: Context, device: BluetoothDevice, prefs: SharedPreferences?) {
        context = ctx.applicationContext
        currentDevice = device
        
        Log.e(TAG, "🚨🚨🚨 雷达触发！启动 BLE GATT 引擎，准备接管: ${device.address}")
        mainHandler.post { Toast.makeText(context, "LSPosed: 捕获 FreeBuds，防止拥堵启动!", Toast.LENGTH_LONG).show() }
        
        disconnect()
        registerReceiver()
        currentGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnectedPod(ctx: Context, device: BluetoothDevice) {
        if (currentDevice?.address == device.address) {
            Log.e(TAG, "🚨🚨🚨 检测到耳机断开！")
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
        lastBatteryResult = null
        lastAncMode = null
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
            Log.e(TAG, "🚀 BLE 指令下发: ${cmd.joinToString("") { "%02X".format(it) }}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 指令下发坍塌", e)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.e(TAG, "✅ 物理链路握手成功！扫描 fe01 服务...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnect()
                context?.let { currentDevice?.let { dev -> broadcastDisconnected(it, dev) } }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(UUID_SERVICE) ?: return
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
                    }
                }
                
                isConnected = true
                Log.e(TAG, "🎉 华为 FreeBuds 彻底并网！")
                context?.let { currentDevice?.let { dev -> broadcastConnected(it, dev) } }
                
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
        val now = System.currentTimeMillis()

        for (frame in frames) {
            val battery = BatteryParser.parse(frame)
            val anc = AncModeParser.parse(frame)
            
            var stateChanged = false

            // 🛡️ 拦截器：只有数据真正变化时才放行
            if (battery != null && battery != lastBatteryResult) {
                lastBatteryResult = battery
                stateChanged = true
            }
            if (anc != null && anc != lastAncMode) {
                lastAncMode = anc
                stateChanged = true
            }

            // 🛡️ 节流阀：即使状态发生变化，每 1.5 秒也最多只允许发送 1 次广播！
            if (stateChanged || (now - lastBroadcastTime > 3000)) {
                lastBroadcastTime = now
                Log.e(TAG, "📤 节流放行：向系统发送电量/降噪更新广播！")
                
                if (lastBatteryResult != null) broadcastBattery(lastBatteryResult!!)
                if (lastAncMode != null) broadcastAnc(lastAncMode!!)
                
                // 为了确保你看到胜利的瞬间，我们强制在屏幕弹个电量
                mainHandler.post { 
                    lastBatteryResult?.let { b -> 
                        Toast.makeText(context, "LSPosed 电量获取成功!\n左:${b.left?.level} 右:${b.right?.level}", Toast.LENGTH_SHORT).show() 
                    }
                }
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