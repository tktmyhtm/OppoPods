package moe.chenxy.oppopods.pods

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.utils.MediaControl
import moe.chenxy.oppopods.utils.SystemApisUtils
import moe.chenxy.oppopods.utils.SystemApisUtils.setIconVisibility
import moe.chenxy.oppopods.utils.miuiStrongToast.MiuiStrongToastUtil
import moe.chenxy.oppopods.utils.miuiStrongToast.MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsPrefsKey
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import java.io.IOException
import java.io.InputStream
import android.content.SharedPreferences
import java.util.concurrent.Executor

@SuppressLint("MissingPermission", "StaticFieldLeak")
object RfcommController {
    private const val TAG = "OppoPods-RfcommController"
    private const val RFCOMM_CHANNEL = 15
    private const val BATTERY_POLL_INTERVAL_MS = 30_000L

    // Basic Objects
    private var socket: BluetoothSocket? = null
    private var mContext: Context? = null
    lateinit var mDevice: BluetoothDevice
    private val audioManager: AudioManager? by lazy {
        mContext?.getSystemService(AudioManager::class.java)
    }
    private lateinit var mPrefs: SharedPreferences

    private var scanToken: MediaRouter2.ScanToken? = null
    var routes: List<MediaRoute2Info> = listOf()
    private lateinit var mediaRouter: MediaRouter2

    // Status
    private var mShowedConnectedToast = false
    private var isConnected = false
    private var lastTempBatt = 0
    lateinit var currentBatteryParams: BatteryParams
    private var currentAnc: Int = 1
    private var currentGameMode: Boolean = false
    // Adaptive模式状态缓存，通过广播同步确保跨进程实时一致，避免 SharedPreferences 跨进程缓存导致读取过时值
    private var adaptiveModeEnabled: Boolean = true
    private var lastKnownCaseBattery: Int = 0
    private var lastKnownCaseCharging: Boolean = false
    private var cachedDeviceName: String = ""

    // Polling job
    private var batteryPollJob: kotlinx.coroutines.Job? = null

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(p0: Context?, p1: Intent?) {
            handleUIEvent(p1!!)
        }
    }

    private fun changeUIAncStatus(status: Int) {
        if (status < 1 || status > 4) return
        Intent(OppoPodsAction.ACTION_PODS_ANC_CHANGED).apply {
            this.putExtra("status", status)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext!!.sendBroadcast(this)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
            putExtra("status", status)
        }
    }

    private fun changeUIBatteryStatus(status: BatteryParams) {
        Intent(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED).apply {
            this.putExtra("status", status)
            putBatteryExtras(status)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext!!.sendBroadcast(this)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("status", status)
            putBatteryExtras(status)
        }
    }

    private fun changeUIGameModeStatus(enabled: Boolean) {
        Intent(OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED).apply {
            this.putExtra("enabled", enabled)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mContext!!.sendBroadcast(this)
        }
    }

    fun handleUIEvent(intent: Intent) {
        when (intent.action) {
            OppoPodsAction.ACTION_PODS_UI_INIT -> {
                Log.i(TAG, "UI Init")
                if (::currentBatteryParams.isInitialized)
                    changeUIBatteryStatus(currentBatteryParams)
                changeUIAncStatus(currentAnc)
                changeUIGameModeStatus(currentGameMode)
                Intent(OppoPodsAction.ACTION_PODS_CONNECTED).apply {
                    this.putExtra("device_name", mDevice.name ?: cachedDeviceName)
                    this.`package` = BuildConfig.APPLICATION_ID
                    this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    mContext!!.sendBroadcast(this)
                }
                sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                    putExtra("device_name", mDevice.name ?: cachedDeviceName)
                }
            }
            OppoPodsAction.ACTION_ANC_SELECT -> {
                val status = intent.getIntExtra("status", 0)
                setANCMode(status)
            }
            OppoPodsAction.ACTION_REFRESH_STATUS -> {
                queryStatus()
            }
            OppoPodsAction.ACTION_GAME_MODE_SET -> {
                val enabled = intent.getBooleanExtra("enabled", false)
                setGameMode(enabled)
            }
            OppoPodsAction.ACTION_CYCLE_ANC -> {
                cycleAnc()
            }
            OppoPodsAction.ACTION_ADAPTIVE_MODE_CHANGED -> {
                // 跨进程同步 Adaptive 模式开关状态，确保 cycleAnc() 使用实时值
                adaptiveModeEnabled = intent.getBooleanExtra("enabled", true)
                Log.d(TAG, "Adaptive mode synced: $adaptiveModeEnabled")
                // 若关闭 Adaptive 且当前处于 Adaptive 模式，自动切换至降噪模式
                if (!adaptiveModeEnabled && currentAnc == 4) {
                    setANCMode(2)
                }
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun handleBatteryChanged(result: BatteryParser.BatteryResult) {
        val left = PodParams(
            result.left?.level ?: 0,
            result.left?.isCharging == true,
            result.left != null,
            0
        )
        val right = PodParams(
            result.right?.level ?: 0,
            result.right?.isCharging == true,
            result.right != null,
            0
        )
        val case = if (result.case != null) {
            lastKnownCaseBattery = result.case.level
            lastKnownCaseCharging = result.case.isCharging
            PodParams(
                result.case.level,
                result.case.isCharging,
                true,
                0
            )
        } else {
            PodParams(
                lastKnownCaseBattery,
                lastKnownCaseCharging,
                false,
                0
            )
        }

        if (BuildConfig.DEBUG) {
            Log.v(TAG, "batt left ${left.battery} right ${right.battery} case ${case.battery}")
        }

        val shouldShowToast = !mShowedConnectedToast
        if (shouldShowToast) {
            // Wait until at least one connected ear has valid battery data
            val hasValidData = (left.isConnected && left.battery > 0) ||
                    (right.isConnected && right.battery > 0)
            if (!hasValidData) return
        }

        val batteryParams = BatteryParams(left, right, case)
        currentBatteryParams = batteryParams

        if (shouldShowToast) {
            MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(mContext!!, batteryParams)
            mShowedConnectedToast = true
        }
        MiuiStrongToastUtil.showPodsNotificationByMiuiBt(mContext!!, batteryParams, mDevice)
        changeUIBatteryStatus(batteryParams)

        lastTempBatt = if (left.isConnected && right.isConnected)
            minOf(left.battery, right.battery)
        else if (left.isConnected)
            left.battery
        else if (right.isConnected)
            right.battery
        else SystemApisUtils.BATTERY_LEVEL_UNKNOWN

        setRegularBatteryLevel(lastTempBatt)
    }

    private val routeCallback = object : MediaRouter2.RouteCallback() {
        override fun onRoutesUpdated(routes: List<MediaRoute2Info>) {
            Log.v(TAG, "routes updated: $routes")
            this@RfcommController.routes = routes
        }
    }

    private fun startRoutesScan() {
        val executor = Executor { p0 ->
            CoroutineScope(Dispatchers.IO).launch { p0?.run() }
        }
        val preferredFeature = listOf(MediaRoute2Info.FEATURE_LIVE_AUDIO, MediaRoute2Info.FEATURE_LIVE_VIDEO)
        mediaRouter.registerRouteCallback(executor, routeCallback, RouteDiscoveryPreference.Builder(preferredFeature, true).build())
        scanToken = mediaRouter.requestScan(MediaRouter2.ScanRequest.Builder().build())
    }

    private fun stopRoutesScan() {
        scanToken?.let { mediaRouter.cancelScanRequest(it) }
        mediaRouter.unregisterRouteCallback(routeCallback)
    }

    /**
     * Create RFCOMM socket to OPPO earphone on channel 15 via reflection.
     */
    @SuppressLint("DiscouragedPrivateApi")
    private fun createRfcommSocket(device: BluetoothDevice): BluetoothSocket {
        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return method.invoke(device, RFCOMM_CHANNEL) as BluetoothSocket
    }

    fun connectPod(context: Context, device: BluetoothDevice, prefs: SharedPreferences) {
        mContext = context
        mDevice = device
        mPrefs = prefs
        cachedDeviceName = device.name ?: ""
        // 初始化 Adaptive 模式状态缓存，从 SharedPreferences 读取当前值
        adaptiveModeEnabled = mPrefs.getBoolean("adaptive_mode", true)
        Log.d(TAG, "Adaptive mode initial: $adaptiveModeEnabled")

        context.registerReceiver(broadcastReceiver, IntentFilter().apply {
            this.addAction(OppoPodsAction.ACTION_ANC_SELECT)
            this.addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
            this.addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
            this.addAction(OppoPodsAction.ACTION_GAME_MODE_SET)
            this.addAction(OppoPodsAction.ACTION_CYCLE_ANC)
            this.addAction(OppoPodsAction.ACTION_ADAPTIVE_MODE_CHANGED)
        }, Context.RECEIVER_EXPORTED)

        Intent(OppoPodsAction.ACTION_PODS_CONNECTED).apply {
            this.putExtra("device_name", cachedDeviceName)
            this.`package` = BuildConfig.APPLICATION_ID
            this.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
        sendExternalPodsStatusBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
            putExtra("device_name", cachedDeviceName)
        }

        MediaControl.mContext = mContext
        mediaRouter = MediaRouter2.getInstance(mContext!!)
        startRoutesScan()

        isConnected = true

        // Start persistent connection and battery polling
        CoroutineScope(Dispatchers.IO).launch {
            delay(500)
            try {
                socket = createRfcommSocket(device)
                socket!!.connect()
                Log.d(TAG, "RFCOMM connected!")

                // Start reader thread
                startPacketReader(socket!!.inputStream)

                // Initial status query (combo: battery wake + mode)
                delay(300)
                queryStatus()

                // Auto-enable game mode if preference is set.
                // Read remote module preferences since this runs in com.android.bluetooth.
                if (mPrefs.getBoolean("auto_game_mode", false)) {
                    delay(100)
                    sendPacketSafe(Enums.GAME_MODE_ON)
                }
            } catch (e: IOException) {
                Log.e(TAG, "RFCOMM connect failed", e)
                isConnected = false
                return@launch
            }
        }

        // Start battery polling
        batteryPollJob = CoroutineScope(Dispatchers.IO).launch {
            delay(2000) // Wait for initial connection
            while (isConnected) {
                delay(BATTERY_POLL_INTERVAL_MS)
                if (isConnected) {
                    queryStatus()
                }
            }
        }
    }

    private fun sendExternalPodsStatusBroadcast(action: String, fill: Intent.() -> Unit = {}) {
        val ctx = mContext ?: return
        listOf("com.milink.service", "com.xiaomi.bluetooth", "com.android.settings").forEach { targetPackage ->
            Intent(action).apply {
                if (::mDevice.isInitialized) {
                    putExtra("address", mDevice.address)
                    putExtra("device_name", mDevice.name ?: cachedDeviceName)
                }
                fill()
                setPackage(targetPackage)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                ctx.sendBroadcast(this)
            }
        }
    }

    private fun Intent.putBatteryExtras(status: BatteryParams) {
        putExtra("left_battery", status.left?.battery ?: 0)
        putExtra("left_charging", status.left?.isCharging == true)
        putExtra("left_connected", status.left?.isConnected == true)
        putExtra("right_battery", status.right?.battery ?: 0)
        putExtra("right_charging", status.right?.isCharging == true)
        putExtra("right_connected", status.right?.isConnected == true)
        putExtra("case_battery", status.case?.battery ?: 0)
        putExtra("case_charging", status.case?.isCharging == true)
        putExtra("case_connected", status.case?.isConnected == true)
    }

    private fun startPacketReader(inputStream: InputStream) {
        CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(1024)
            try {
                while (isConnected) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        val packet = buffer.copyOfRange(0, bytesRead)
                        handleOppoPacket(packet)
                    } else if (bytesRead == -1) {
                        Log.d(TAG, "RFCOMM stream ended")
                        break
                    }
                }
            } catch (e: IOException) {
                if (isConnected) {
                    Log.e(TAG, "RFCOMM read error", e)
                }
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun handleOppoPacket(packet: ByteArray) {
        if (BuildConfig.DEBUG) {
            Log.v(TAG, "Received: ${packet.toHexString(HexFormat.UpperCase)}")
        }

        // Try parse as battery response (query response, Cmd=0x8106)
        val batteryResult = BatteryParser.parse(packet)
        if (batteryResult != null) {
            handleBatteryChanged(batteryResult)
            return
        }

        // Try parse as active battery report (unsolicited, Cmd=0x0204, type=0x01)
        val activeResult = BatteryParser.parseActiveReport(packet)
        if (activeResult != null) {
            handleBatteryChanged(activeResult)
            return
        }

        // Try parse as ANC mode response
        val ancResult = AncModeParser.parse(packet)
        if (ancResult != null) {
            Log.d(TAG, "ANC mode received: $ancResult")
            currentAnc = when (ancResult) {
                NoiseControlMode.OFF -> 1
                NoiseControlMode.NOISE_CANCELLATION -> 2
                NoiseControlMode.TRANSPARENCY -> 3
                NoiseControlMode.ADAPTIVE -> 4
            }
            changeUIAncStatus(currentAnc)
            return
        }

        // Try parse as batch query response for game mode (Cmd=0x810D)
        val gameModeResult = GameModeParser.parse(packet)
        if (gameModeResult != null) {
            Log.d(TAG, "Game mode received: $gameModeResult")
            currentGameMode = gameModeResult
            changeUIGameModeStatus(gameModeResult)
            return
        }

        // Unknown packet - log in debug
        if (BuildConfig.DEBUG) {
            Log.v(TAG, "Unknown OPPO packet: ${packet.toHexString(HexFormat.UpperCase)}")
        }
    }

    fun disconnectedPod(context: Context, device: BluetoothDevice) {
        isConnected = false
        batteryPollJob?.cancel()

        try {
            socket?.close()
        } catch (_: IOException) {}
        socket = null

        mContext?.let {
            stopRoutesScan()
            cancelPodsNotificationByMiuiBt(context, device)
            Intent(OppoPodsAction.ACTION_PODS_DISCONNECTED).apply {
                context.sendBroadcast(this)
            }
            it.unregisterReceiver(broadcastReceiver)
        }

        mShowedConnectedToast = false
        lastKnownCaseBattery = 0
        lastKnownCaseCharging = false
        cachedDeviceName = ""
        mContext = null
        MediaControl.mContext = null
    }

    private fun sendPacketSafe(packet: ByteArray) {
        try {
            socket?.outputStream?.write(packet)
            socket?.outputStream?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Send packet failed", e)
        }
    }

    fun setGameMode(enabled: Boolean) {
        Log.d(TAG, "setGameMode: $enabled")
        currentGameMode = enabled
        val packet = if (enabled) Enums.GAME_MODE_ON else Enums.GAME_MODE_OFF
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(packet)
        }
    }

    fun cycleAnc() {
        // 使用广播同步的缓存值，避免 SharedPreferences 跨进程缓存导致读取过时值
        val next = when (currentAnc) {
            2 -> if (adaptiveModeEnabled) 4 else 3  // NC → Adaptive（若启用）或 Transparency
            4 -> 3  // Adaptive → Transparency
            3 -> 1  // Transparency → OFF
            else -> 2  // OFF or unknown → NC
        }
        setANCMode(next)
    }

    fun setANCMode(mode: Int) {
        Log.d(TAG, "setANCMode: $mode")
        currentAnc = mode  // 乐观更新，与 AppRfcommController 保持一致
        val packet = when (mode) {
            1 -> Enums.ANC_OFF
            2 -> Enums.ANC_NOISE_CANCEL
            3 -> Enums.ANC_TRANSPARENCY
            4 -> Enums.ANC_ADAPTIVE
            else -> return
        }
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(packet)
        }
    }

    fun queryBattery() {
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(Enums.QUERY_BATTERY)
        }
    }

    /**
     * Combo query strategy: send batch query (wake + game mode), then battery, then ANC.
     */
    fun queryStatus() {
        CoroutineScope(Dispatchers.IO).launch {
            sendPacketSafe(Enums.QUERY_STATUS)
            delay(50)
            sendPacketSafe(Enums.QUERY_BATTERY)
            delay(50)
            sendPacketSafe(Enums.QUERY_ANC)
        }
    }

    fun disconnectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

        MediaControl.sendPause()

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                    }
                }
            }
            override fun onServiceDisconnected(profile: Int) { }
        }, BluetoothProfile.HEADSET)

        CoroutineScope(Dispatchers.Default).launch {
            delay(500)
            for (route in routes) {
                if (route.type == MediaRoute2Info.TYPE_BUILTIN_SPEAKER) {
                    Log.d(TAG, "found speaker route $route")
                    mediaRouter.transferTo(route)
                }
            }
        }

        setRegularBatteryLevel(lastTempBatt)
    }

    fun connectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    try {
                        val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        method.invoke(proxy, device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                    }
                }
            }
            override fun onServiceDisconnected(profile: Int) { }
        }, BluetoothProfile.HEADSET)

        for (route in routes) {
            if (route.type == MediaRoute2Info.TYPE_BLUETOOTH_A2DP && route.name == device!!.name) {
                Log.d(TAG, "found bt route $route")
                mediaRouter.transferTo(route)
            }
        }

        val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
        statusBarManager.setIconVisibility("wireless_headset", true)
        setRegularBatteryLevel(lastTempBatt)
    }

    fun setRegularBatteryLevel(level: Int) {
        try {
            val service = getObjectField(mContext, "mAdapterService")
            callMethod(service, "setBatteryLevel", mDevice, level, false)
        } catch (e: Exception) {
            Log.e(TAG, "setRegularBatteryLevel failed", e)
        }
    }

    private fun getObjectField(instance: Any?, fieldName: String): Any? {
        if (instance == null) return null
        var cls: Class<*>? = instance.javaClass
        while (cls != null) {
            runCatching {
                return cls.getDeclaredField(fieldName).apply { isAccessible = true }.get(instance)
            }
            cls = cls.superclass
        }
        throw NoSuchFieldException(fieldName)
    }

    private fun callMethod(instance: Any?, methodName: String, vararg args: Any?): Any? {
        if (instance == null) return null
        var cls: Class<*>? = instance.javaClass
        while (cls != null) {
            cls.declaredMethods.firstOrNull { it.name == methodName && it.parameterTypes.size == args.size }?.let {
                it.isAccessible = true
                return it.invoke(instance, *args)
            }
            cls = cls.superclass
        }
        throw NoSuchMethodException(methodName)
    }
}
