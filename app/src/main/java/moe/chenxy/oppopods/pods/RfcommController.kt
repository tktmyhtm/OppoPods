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
    private var lastConnectTime = 0L
    private val BINDER_THROTTLE_MS = 2000L
    private const val TAG = "OppoPods-GATT"
    
    private val UUID_SERVICE = UUID.fromString("0000fe01-0000-1000-8000-00805f9b34fb")
    private val UUID_NOTIFY = UUID.fromString("0000fe03-0000-1000-8000-00805f9b34fb")
    private val UUID_WRITE = UUID.fromString("0000fe02-0000-1000-8000-00805f9b34fb")
    private val UUID_DESCR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var currentGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private var context: Context? = null
    private var currentDevice: BluetoothDevice? = null
    private var commandReceiver: BroadcastReceiver? = null
    
    private val framer = OppoPacketFramer()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastBatteryResult: BatteryParser.BatteryResult? = null
    private var lastAncMode: NoiseControlMode? = null
    private var lastBroadcastTime = 0L

fun connectPod(ctx: Context, device: BluetoothDevice, prefs: SharedPreferences?) {
    val now = System.currentTimeMillis()
    if (now - lastConnectTime < BINDER_THROTTLE_MS) return  // ← 加这行
    lastConnectTime = now                                    // ← 加这行
    context = ctx.applicationContext
        Log.e(TAG, "🚨🚨🚨 雷达触发！启动 BLE GATT 引擎，准备接管: ${device.address}")
        
        // 🛡️ 修复了这里的 Context 空指针编译错误！
        mainHandler.post { 
            context?.let { safeCtx ->
                Toast.makeText(safeCtx, "LSPosed: 捕获 FreeBuds，防止拥堵启动!", Toast.LENGTH_LONG).show() 
            }
        }
        
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
            try {
                context?.registerReceiver(commandReceiver, filter, 2)
            } catch (e: Exception) {
                context?.registerReceiver(commandReceiver, filter)
            }
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
                context?.let { ctx -> currentDevice?.let { dev -> broadcastDisconnected(ctx, dev) } }
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
                context?.let { ctx -> currentDevice?.let { dev -> broadcastConnected(ctx, dev) } }
                
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

            if (battery != null && battery != lastBatteryResult) {
                lastBatteryResult = battery
                stateChanged = true
            }
            if (anc != null && anc != lastAncMode) {
                lastAncMode = anc
                stateChanged = true
            }

            if (stateChanged || (now - lastBroadcastTime > 3000)) {
                lastBroadcastTime = now
                Log.e(TAG, "📤 节流放行：向系统发送电量/降噪更新广播！")
                
                // 🛡️ 修复了这里的强转编译报错！
                val currentBattery = lastBatteryResult
                if (currentBattery != null) broadcastBattery(currentBattery)
                
                val currentAnc = lastAncMode
                if (currentAnc != null) broadcastAnc(currentAnc)
                
                // 🛡️ 修复了这里的 Toast 弹窗编译报错！
                mainHandler.post { 
                    context?.let { ctx ->
                        currentBattery?.let { b ->
                            Toast.makeText(ctx, "LSPosed 电量获取成功!\n左:${b.left?.level} 右:${b.right?.level}", Toast.LENGTH_SHORT).show() 
                        }
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