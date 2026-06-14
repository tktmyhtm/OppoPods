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
    private var lastConnectTime = 0L
    private val GLOBAL_THROTTLE_MS = 2000L

    fun connectPod(ctx: Context, device: BluetoothDevice, prefs: SharedPreferences?) {
        val now = System.currentTimeMillis()
        if (now - lastConnectTime < GLOBAL_THROTTLE_MS) return
        lastConnectTime = now

        context = ctx.applicationContext
        currentDevice = device

        Log.e(TAG, "🚨 捕获 FreeBuds，启动 GATT: ${device.address}")

        mainHandler.post {
            context?.let { safeCtx ->
                Toast.makeText(safeCtx, "LSPosed: 捕获 FreeBuds", Toast.LENGTH_LONG).show()
            }
        }

        disconnect()
        registerReceiver()
        currentGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnectedPod(ctx: Context, device: BluetoothDevice) {
        if (currentDevice?.address == device.address) {
            Log.e(TAG, "🚨 耳机断开")
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
            Log.e(TAG, "🚀 BLE 指令: ${cmd.joinToString("") { "%02X".format(it) }}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 指令下发失败", e)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.e(TAG, "✅ GATT 连接成功，发现服务...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnect()
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
                Log.e(TAG, "🎉 FreeBuds 并网成功！")

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
        // 全局节流阀：至少间隔 3 秒才能发一次广播
        if (now - lastBroadcastTime < 3000) return
        lastBroadcastTime = now

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

            if (stateChanged) {
                Log.e(TAG, "📤 放行广播")

                val currentBattery = lastBatteryResult
                if (currentBattery != null) broadcastBattery(currentBattery)

                val currentAnc = lastAncMode
                if (currentAnc != null) broadcastAnc(currentAnc)

                mainHandler.post {
                    context?.let { ctx ->
                        currentBattery?.let { b ->
                            Toast.makeText(ctx, "电量: 左:${b.left?.level} 右:${b.right?.level}", Toast.LENGTH_SHORT).show()
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
        }
        ctx.sendBroadcast(intent)
        Log.e(TAG, "📤 broadcastConnected")
    }

    private fun broadcastDisconnected(ctx: Context, device: BluetoothDevice) {
        val intent = Intent(OppoPodsAction.ACTION_PODS_DISCONNECTED).apply {
            putExtra("address", device.address)
        }
        ctx.sendBroadcast(intent)
        Log.e(TAG, "📤 broadcastDisconnected")
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
        ctx.sendBroadcast(intent)
        Log.e(TAG, "📤 broadcastBattery")
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
        ctx.sendBroadcast(intent)
        Log.e(TAG, "📤 broadcastAnc")
    }
}