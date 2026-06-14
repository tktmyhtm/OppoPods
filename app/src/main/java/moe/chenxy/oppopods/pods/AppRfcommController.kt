package moe.chenxy.oppopods.pods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import java.io.IOException
import java.io.InputStream

@SuppressLint("MissingPermission")
class AppRfcommController {
    companion object {
        private const val TAG = "OppoPods-AppRfcomm"
        private const val BATTERY_POLL_INTERVAL_MS = 30_000L
    }
    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }
    private var socket: BluetoothSocket? = null
    private var isConnected = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var batteryPollJob: Job? = null
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    private val _batteryParams = MutableStateFlow(BatteryParams())
    val batteryParams: StateFlow<BatteryParams> = _batteryParams
    private val _ancMode = MutableStateFlow(NoiseControlMode.OFF)
    val ancMode: StateFlow<NoiseControlMode> = _ancMode
    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName
    fun connect(device: BluetoothDevice) {
        if (_connectionState.value == ConnectionState.CONNECTING) return
        _deviceName.value = device.name ?: device.address
        _connectionState.value = ConnectionState.CONNECTING
        batteryPollJob?.cancel()
        scope.launch {
            try {
                delay(300)
                socket = device.createRfcommSocketToServiceRecord(java.util.UUID.fromString("0000fe01-0000-1000-8000-00805f9b34fb"))
                socket?.connect()
                Log.d(TAG, "RFCOMM connected to ${device.name}")
                isConnected = true
                _connectionState.value = ConnectionState.CONNECTED
                startPacketReader(socket!!.inputStream)
                delay(300)
                queryStatus()
                startBatteryPolling()
            } catch (e: IOException) {
                Log.e(TAG, "RFCOMM connect failed", e)
                _connectionState.value = ConnectionState.ERROR
                isConnected = false
                batteryPollJob?.cancel()
            }
        }
    }
    private fun startBatteryPolling() {
        batteryPollJob?.cancel()
        batteryPollJob = scope.launch {
            while (isConnected) {
                delay(BATTERY_POLL_INTERVAL_MS)
                if (isConnected) queryStatus()
            }
        }
    }
    private fun startPacketReader(inputStream: InputStream) {
        scope.launch {
            val buffer = ByteArray(1024)
            val framer = HuaweiPacketFramer()
            try {
                while (isConnected) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        framer.append(buffer, bytesRead).forEach { packet -> handlePacket(packet) }
                    } else if (bytesRead == -1) break
                }
            } catch (e: IOException) {
                if (isConnected) Log.e(TAG, "Read error", e)
            }
            if (isConnected) disconnect()
        }
    }
    private fun handlePacket(packet: ByteArray) {
        val battery = BatteryParser.parseSpp(packet)
        if (battery != null) {
            val left = PodParams(battery.left?.level ?: 0, battery.left?.isCharging == true, battery.left != null, 0)
            val right = PodParams(battery.right?.level ?: 0, battery.right?.isCharging == true, battery.right != null, 0)
            val case = PodParams(battery.case?.level ?: 0, battery.case?.isCharging == true, battery.case != null, 0)
            _batteryParams.value = BatteryParams(left, right, case)
            return
        }
        val anc = AncModeParser.parseSpp(packet)
        if (anc != null) {
            Log.d(TAG, "ANC mode received: $anc")
            _ancMode.value = anc
            return
        }
    }
    private fun sendPacket(packet: ByteArray) {
        try { socket?.outputStream?.write(packet); socket?.outputStream?.flush() }
        catch (e: IOException) { Log.e(TAG, "Send failed", e) }
    }
    fun setANCMode(mode: NoiseControlMode) {
        val mbbMode = when (mode) {
            NoiseControlMode.OFF -> 1
            NoiseControlMode.NOISE_CANCELLATION -> 2
            NoiseControlMode.TRANSPARENCY -> 3
            NoiseControlMode.ADAPTIVE -> 4
        }
        _ancMode.value = mode
        scope.launch { sendPacket(MbbCmd.ancCommand(mbbMode)) }
    }
    private fun queryStatus() {
        scope.launch {
            sendPacket(MbbCmd.QUERY_BATTERY)
            delay(50)
            sendPacket(MbbCmd.QUERY_ANC)
        }
    }
    fun refreshStatus() { if (isConnected) queryStatus() }
    fun disconnect() {
        isConnected = false
        batteryPollJob?.cancel()
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _batteryParams.value = BatteryParams()
        _ancMode.value = NoiseControlMode.OFF
        _deviceName.value = ""
    }
}

