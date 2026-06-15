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
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import java.io.InputStream
import java.util.UUID

@SuppressLint("MissingPermission")
object RfcommController {
    private const val TAG = "OppoPods-Bus"
    private val UUID_SERVICE = UUID.fromString("0000fe01-0000-1000-8000-00805f9b34fb")
    private val UUID_NOTIFY = UUID.fromString("0000fe03-0000-1000-8000-00805f9b34fb")
    private val UUID_WRITE = UUID.fromString("0000fe02-0000-1000-8000-00805f9b34fb")
    private val UUID_DESCR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val UUID_SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    private var currentGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private var context: Context? = null
    private var currentDevice: BluetoothDevice? = null
    private var commandReceiver: BroadcastReceiver? = null
    private var sppThread: Thread? = null
    private var sppRunning = false
    private val framer = HuaweiPacketFramer()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastBatteryResult: BatteryResult? = null
    private var lastAncMode: NoiseControlMode? = null
    private var lastBatteryBroadcastMs = 0L
    private var lastAncBroadcastMs = 0L
    private var lastConnectMs = 0L
    private val BATTERY_BROADCAST_INTERVAL_MS = 5000L
    private val ANC_BROADCAST_INTERVAL_MS = 3000L
    private val CONNECT_THROTTLE_MS = 5000L

    fun connectPod(ctx: Context, device: BluetoothDevice, prefs: SharedPreferences?) {
        val now = System.currentTimeMillis()
        if (now - lastConnectMs < CONNECT_THROTTLE_MS) return
        lastConnectMs = now
        context = ctx.applicationContext
        currentDevice = device
        Log.e(TAG, "[conn] FreeBuds: " + device.address)
        disconnect()
        registerReceiver()
        currentGatt = device.connectGatt(context, false, gattCallback)
        startSppConnection(device)
    }

    fun disconnectedPod(ctx: Context, device: BluetoothDevice) {
        if (currentDevice?.address == device.address) {
            Log.e(TAG, "[disc] disconnected")
            disconnect()
        }
    }

    private fun disconnect() {
        try { currentGatt?.disconnect() } catch (_: Exception) {}
        try { currentGatt?.close() } catch (_: Exception) {}
        currentGatt = null; writeChar = null; isConnected = false
        sppRunning = false; sppThread = null
        lastBatteryResult = null; lastAncMode = null
    }

    private fun registerReceiver() {
        if (commandReceiver != null) return
        commandReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    OppoPodsAction.ACTION_ANC_SELECT -> {
                        val s = intent.getIntExtra("status", 1)
                        val mode = when (s) {
                            1 -> NoiseControlMode.OFF
                            2 -> NoiseControlMode.NOISE_CANCELLATION
                            3 -> NoiseControlMode.TRANSPARENCY
                            4 -> NoiseControlMode.ADAPTIVE
                            else -> NoiseControlMode.OFF
                        }
                        sendCommand(MbbCmd.ancCommand(mode))
                    }
                    OppoPodsAction.ACTION_REFRESH_STATUS -> {
                        sendCommand(MbbCmd.QUERY_BATTERY)
                        sendCommand(MbbCmd.QUERY_ANC)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_ANC_SELECT)
            addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
        }
        try { context?.registerReceiver(commandReceiver, filter, 2)
        } catch (_: Exception) { context?.registerReceiver(commandReceiver, filter) }
    }

    private fun sendCommand(cmd: ByteArray) {
        val gatt = currentGatt ?: return
        val char = writeChar ?: return
        try {
            @Suppress("DEPRECATION")
            char.value = cmd
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
            Log.e(TAG, "[BLE] " + cmd.joinToString("") { String.format("%02X", it) })
        } catch (e: Exception) {
            Log.e(TAG, "[BLE] write failed", e)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.e(TAG, "[GATT] connected")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnect()
            }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
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
            Log.e(TAG, "[GATT] ready! querying...")
            mainHandler.postDelayed({
                sendCommand(MbbCmd.QUERY_BATTERY)
                sendCommand(MbbCmd.QUERY_ANC)
            }, 1000)
        }
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == UUID_NOTIFY) {
                @Suppress("DEPRECATION")
                val data = characteristic.value ?: return
                processBleData(data)
            }
        }
    }

    private fun startSppConnection(device: BluetoothDevice) {
        sppRunning = true
        sppThread = Thread {
            try {
                val socket = OppoRfcommSocketFactory.connect(device, TAG)
                socket.connect()
                Log.e(TAG, "[SPP] connected!")
                val inputStream: InputStream = socket.inputStream
                val buffer = ByteArray(4096)
                while (sppRunning) {
                    val bytesRead = try { inputStream.read(buffer)
                    } catch (e: Exception) { Log.e(TAG, "[SPP] " + e.message); break }
                    if (bytesRead <= 0) break
                    processSppData(buffer.copyOfRange(0, bytesRead))
                }
                try { socket.close() } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "[SPP] failed: " + e.message)
            }
            sppRunning = false
        }.apply { name = "oppopods-spp"; isDaemon = true; start() }
    }

    private fun processSppData(data: ByteArray) {
        for (frame in framer.append(data, data.size)) processFrame(frame)
    }
    private fun processBleData(data: ByteArray) {
        for (frame in framer.append(data, data.size)) processFrame(frame)
    }
    private fun processFrame(frame: ByteArray) {
        val now = System.currentTimeMillis()
        val battery = BatteryParser.parse(frame)
        if (battery != null && battery != lastBatteryResult) {
            lastBatteryResult = battery
            if (now - lastBatteryBroadcastMs >= BATTERY_BROADCAST_INTERVAL_MS) {
                lastBatteryBroadcastMs = now; broadcastBattery(battery)
            }
        }
        val anc = AncModeParser.parse(frame)
        if (anc != null && anc != lastAncMode) {
            lastAncMode = anc
            if (now - lastAncBroadcastMs >= ANC_BROADCAST_INTERVAL_MS) {
                lastAncBroadcastMs = now; broadcastAnc(anc)
            }
        }
    }

    private fun broadcastBattery(battery: BatteryResult) {
        val ctx = context ?: return
        ctx.sendBroadcast(Intent(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED).apply {
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
        })
        Log.e(TAG, "[bcast] bat")
    }
    private fun broadcastAnc(anc: NoiseControlMode) {
        val ctx = context ?: return
        ctx.sendBroadcast(Intent(OppoPodsAction.ACTION_PODS_ANC_CHANGED).apply {
            putExtra("address", currentDevice?.address)
            putExtra("status", when(anc) { NoiseControlMode.OFF -> 1; NoiseControlMode.NOISE_CANCELLATION -> 2; NoiseControlMode.TRANSPARENCY -> 3; NoiseControlMode.ADAPTIVE -> 4 })
        })
        Log.e(TAG, "[bcast] anc")
    }
}package moe.chenxy.oppopods.pods

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
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import java.io.InputStream
import java.util.UUID

@SuppressLint("MissingPermission")
object RfcommController {
    private const val TAG = "OppoPods-Bus"
    private val UUID_SERVICE = UUID.fromString("0000fe01-0000-1000-8000-00805f9b34fb")
    private val UUID_NOTIFY = UUID.fromString("0000fe03-0000-1000-8000-00805f9b34fb")
    private val UUID_WRITE = UUID.fromString("0000fe02-0000-1000-8000-00805f9b34fb")
    private val UUID_DESCR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val UUID_SPP = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    private var currentGatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var isConnected = false
    private var context: Context? = null
    private var currentDevice: BluetoothDevice? = null
    private var commandReceiver: BroadcastReceiver? = null
    private var sppThread: Thread? = null
    private var sppRunning = false
    private val framer = HuaweiPacketFramer()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastBatteryResult: BatteryResult? = null
    private var lastAncMode: NoiseControlMode? = null
    private var lastBatteryBroadcastMs = 0L
    private var lastAncBroadcastMs = 0L
    private var lastConnectMs = 0L
    private val BATTERY_BROADCAST_INTERVAL_MS = 5000L
    private val ANC_BROADCAST_INTERVAL_MS = 3000L
    private val CONNECT_THROTTLE_MS = 5000L

    fun connectPod(ctx: Context, device: BluetoothDevice, prefs: SharedPreferences?) {
        val now = System.currentTimeMillis()
        if (now - lastConnectMs < CONNECT_THROTTLE_MS) return
        lastConnectMs = now
        context = ctx.applicationContext
        currentDevice = device
        Log.e(TAG, "[conn] FreeBuds: " + device.address)
        disconnect()
        registerReceiver()
        currentGatt = device.connectGatt(context, false, gattCallback)
        startSppConnection(device)
    }

    fun disconnectedPod(ctx: Context, device: BluetoothDevice) {
        if (currentDevice?.address == device.address) {
            Log.e(TAG, "[disc] disconnected")
            disconnect()
        }
    }

    private fun disconnect() {
        try { currentGatt?.disconnect() } catch (_: Exception) {}
        try { currentGatt?.close() } catch (_: Exception) {}
        currentGatt = null; writeChar = null; isConnected = false
        sppRunning = false; sppThread = null
        lastBatteryResult = null; lastAncMode = null
    }

    private fun registerReceiver() {
        if (commandReceiver != null) return
        commandReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    OppoPodsAction.ACTION_ANC_SELECT -> {
                        val s = intent.getIntExtra("status", 1)
                        val mode = when (s) {
                            1 -> NoiseControlMode.OFF
                            2 -> NoiseControlMode.NOISE_CANCELLATION
                            3 -> NoiseControlMode.TRANSPARENCY
                            4 -> NoiseControlMode.ADAPTIVE
                            else -> NoiseControlMode.OFF
                        }
                        sendCommand(MbbCmd.ancCommand(mode))
                    }
                    OppoPodsAction.ACTION_REFRESH_STATUS -> {
                        sendCommand(MbbCmd.QUERY_BATTERY)
                        sendCommand(MbbCmd.QUERY_ANC)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_ANC_SELECT)
            addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
        }
        try { context?.registerReceiver(commandReceiver, filter, 2)
        } catch (_: Exception) { context?.registerReceiver(commandReceiver, filter) }
    }

    private fun sendCommand(cmd: ByteArray) {
        val gatt = currentGatt ?: return
        val char = writeChar ?: return
        try {
            @Suppress("DEPRECATION")
            char.value = cmd
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
            Log.e(TAG, "[BLE] " + cmd.joinToString("") { String.format("%02X", it) })
        } catch (e: Exception) {
            Log.e(TAG, "[BLE] write failed", e)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.e(TAG, "[GATT] connected")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnect()
            }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
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
            Log.e(TAG, "[GATT] ready! querying...")
            mainHandler.postDelayed({
                sendCommand(MbbCmd.QUERY_BATTERY)
                sendCommand(MbbCmd.QUERY_ANC)
            }, 1000)
        }
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == UUID_NOTIFY) {
                @Suppress("DEPRECATION")
                val data = characteristic.value ?: return
                processBleData(data)
            }
        }
    }

    private fun startSppConnection(device: BluetoothDevice) {
        sppRunning = true
        sppThread = Thread {
            try {
                val socket = OppoRfcommSocketFactory.connect(device, TAG)
                socket.connect()
                Log.e(TAG, "[SPP] connected!")
                val inputStream: InputStream = socket.inputStream
                val buffer = ByteArray(4096)
                while (sppRunning) {
                    val bytesRead = try { inputStream.read(buffer)
                    } catch (e: Exception) { Log.e(TAG, "[SPP] " + e.message); break }
                    if (bytesRead <= 0) break
                    processSppData(buffer.copyOfRange(0, bytesRead))
                }
                try { socket.close() } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "[SPP] failed: " + e.message)
            }
            sppRunning = false
        }.apply { name = "oppopods-spp"; isDaemon = true; start() }
    }

    private fun processSppData(data: ByteArray) {
        for (frame in framer.append(data, data.size)) processFrame(frame)
    }
    private fun processBleData(data: ByteArray) {
        for (frame in framer.append(data, data.size)) processFrame(frame)
    }
    private fun processFrame(frame: ByteArray) {
        val now = System.currentTimeMillis()
        val battery = BatteryParser.parse(frame)
        if (battery != null && battery != lastBatteryResult) {
            lastBatteryResult = battery
            if (now - lastBatteryBroadcastMs >= BATTERY_BROADCAST_INTERVAL_MS) {
                lastBatteryBroadcastMs = now; broadcastBattery(battery)
            }
        }
        val anc = AncModeParser.parse(frame)
        if (anc != null && anc != lastAncMode) {
            lastAncMode = anc
            if (now - lastAncBroadcastMs >= ANC_BROADCAST_INTERVAL_MS) {
                lastAncBroadcastMs = now; broadcastAnc(anc)
            }
        }
    }

    private fun broadcastBattery(battery: BatteryResult) {
        val ctx = context ?: return
        ctx.sendBroadcast(Intent(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED).apply {
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
        })
        Log.e(TAG, "[bcast] bat")
    }
    private fun broadcastAnc(anc: NoiseControlMode) {
        val ctx = context ?: return
        ctx.sendBroadcast(Intent(OppoPodsAction.ACTION_PODS_ANC_CHANGED).apply {
            putExtra("address", currentDevice?.address)
            putExtra("status", when(anc) { NoiseControlMode.OFF -> 1; NoiseControlMode.NOISE_CANCELLATION -> 2; NoiseControlMode.TRANSPARENCY -> 3; NoiseControlMode.ADAPTIVE -> 4 })
        })
        Log.e(TAG, "[bcast] anc")
    }
}