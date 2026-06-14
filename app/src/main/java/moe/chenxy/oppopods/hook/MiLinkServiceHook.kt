package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import android.view.View
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import java.util.concurrent.CompletableFuture

@SuppressLint("MissingPermission")
object MiLinkServiceHook : HookContext() {
    private const val TAG = "OppoPods-MiLink"
    private const val FAKE_DEVICE_ID = "01010901"
    private const val PREFS_NAME = "oppopods_milink_state"
    private const val PANEL_REFRESH_THROTTLE_MS = 5_000L
    private const val FIND_RING_IDLE = 0
    private const val FIND_RING_ACTIVE = 103
    private const val FIND_RING_RESULT_SUCCESS = 100
    private const val HEADSET_FIND_RING_CHANGED = 10
    private val knownOppoAddresses = linkedSetOf<String>()
    private var context: Context? = null
    private var receiverRegistered = false
    private var currentAddress: String? = null
    private var currentName: String? = null
    private var currentBattery: BatteryParams = BatteryParams()
    private var currentAnc = 1
    private var currentGameMode = false
    private var lastPanelRefreshMs = 0L
    private var lastHeadsetController: Any? = null
    private var lastHeadsetDevice: BluetoothDevice? = null

    override fun onHook() {
        hookContextEntry()
        hookMxBluetoothRuntime()
        hookHeadsetRuntimeDisplay()
        hookFindRingControllerCommand()
        hookFindRingCommand()
        hookFindRingTitle()
    }

    private fun hookContextEntry() {
        listOf(
            "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService",
            "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager"
        ).forEach { className ->
            runCatching {
                hookBefore(findMethod(className, "getInstanceForIsMiTWS", Context::class.java)) {
                    registerStatusReceiver(args[0] as? Context)
                }
            }.onFailure { Log.w(TAG, "hook $className.getInstanceForIsMiTWS skipped", it) }
        }
    }

    private fun hookMxBluetoothRuntime() {
        val classes = listOf(
            "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager",
            "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService"
        )
        classes.forEach { className ->
            hookBluetoothDeviceResult(className, "checkIsMiTWS") { 1 }
            hookBluetoothDeviceResult(className, "getDeviceId") { FAKE_DEVICE_ID }
            hookBluetoothDeviceResult(className, "getBatteryLevel") { 1 }
            hookBluetoothDeviceResult(className, "getAncState") { miLinkAncState() }
            hookBluetoothDeviceResult(className, "getDeviceRunInfo") { 0 }
            hookBluetoothDeviceResult(className, "getSpatialMode") { 0 }
            hookBluetoothDeviceResult(className, "getWearStatus") { "0,0" }
            hookBluetoothDeviceResult(className, "isLeAudio") { false }
            hookAncCommand(className, "openAnc", 2, 1)
            hookAncCommand(className, "closeAnc", 1, 0)
            hookAncCommand(className, "openTransparent", 3, 2)
        }
        classes.forEach { className ->
            hookStringAddressResult(className, "isMiTWS") { true }
            hookStringAddressResult(className, "isSupportAudioSwitch") { 1 }
            hookStringAddressResult(className, "getRingFindState") { miLinkFindRingActive() }
        }
    }

    private fun hookHeadsetRuntimeDisplay() {
        hookBluetoothDeviceResult("com.miui.headset.runtime.ProfileContext", "getDeviceId") { FAKE_DEVICE_ID }
        hookBluetoothDeviceResult("com.miui.headset.runtime.ProfileContext", "getBatteryLevel", reconnectOnRead = true) { miLinkBatteryLevels() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.ProfileContext", "getFindRingState") { miLinkFindRingState() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getDeviceId") { FAKE_DEVICE_ID }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getAncState") { miLinkAncState() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getFindRingState") { miLinkFindRingState() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getBatteryLevelCache", reconnectOnRead = true) { miLinkBatteryLevels() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getHeadsetPropertyBlock", reconnectOnRead = true) { batteryPercentForMiLink() }
        hookAncStateBlock()
        hookHeadsetInfoNoArg("getDeviceId") { FAKE_DEVICE_ID }
        hookHeadsetInfoNoArg("component3") { FAKE_DEVICE_ID }
        hookHeadsetInfoNoArg("getPowers", reconnectOnRead = true) { miLinkBatteryLevels() }
        hookHeadsetInfoNoArg("component4", reconnectOnRead = true) { miLinkBatteryLevels() }
        hookHeadsetInfoNoArg("getMode") { miLinkAncState() }
        hookHeadsetInfoNoArg("component5") { miLinkAncState() }
        hookHeadsetInfoNoArg("getFindRingState") { miLinkFindRingState() }
        hookHeadsetInfoNoArg("component11") { miLinkFindRingState() }
    }

    private fun hookBluetoothDeviceResult(
        className: String,
        methodName: String,
        reconnectOnRead: Boolean = false,
        result: () -> Any
    ) {
        runCatching {
            hookAfter(findMethod(className, methodName, BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice ?: return@hookAfter
                if (!isOppoPod(device)) return@hookAfter
                rememberHeadsetController(className, instance, device)
                if (reconnectOnRead) {
                    requestPanelBluetoothStatus("$className.$methodName")
                }
                this.result = result()
                if (className == "com.miui.headset.runtime.AncBatteryController" && methodName == "getHeadsetPropertyBlock") {
                    notifyHeadsetPropertyChanged(instance, device, 4)
                }
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName(BluetoothDevice) skipped", it) }
    }

    private fun hookStringAddressResult(className: String, methodName: String, result: () -> Any) {
        runCatching {
            hookAfter(findMethod(className, methodName, String::class.java)) {
                val address = args[0] as? String ?: return@hookAfter
                if (!isOppoAddress(address)) return@hookAfter
                this.result = result()
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName(String) skipped", it) }
    }

    private fun hookAncCommand(className: String, methodName: String, oppoAnc: Int, result: Int) {
        runCatching {
            hookBefore(findMethod(className, methodName, BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isOppoPod(device)) return@hookBefore
                currentAnc = oppoAnc
                sendOppoAnc(oppoAnc)
                this.result = result
            }
        }.onFailure { Log.w(TAG, "hook $className.$methodName command skipped", it) }
    }

    private fun hookAncStateBlock() {
        runCatching {
            hookBefore(findMethod("com.miui.headset.runtime.AncBatteryController", "setAncStateBlock", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isOppoPod(device)) return@hookBefore
                val miLinkMode = args[1] as? Int ?: return@hookBefore
                val oppoAnc = oppoAncFromMiLink(miLinkMode)
                val instanceContext = runCatching { getObjectField(instance, "context") as? Context }.getOrNull()
                if (instanceContext != null) {
                    context = instanceContext.applicationContext ?: instanceContext
                }
                currentAnc = oppoAnc
                sendOppoAnc(oppoAnc, instanceContext)
                sendMiLinkAncChanged(oppoAnc, instanceContext)
                notifyHeadsetPropertyChanged(instance, device, 8)
                notifyHeadsetPropertyChanged(instance, device, 4)
                this.result = miLinkAncState()
            }
        }.onFailure { Log.w(TAG, "hook AncBatteryController.setAncStateBlock skipped", it) }
    }

    private fun hookFindRingCommand() {
        runCatching {
            hookBefore(findMethod("com.miui.headset.runtime.AncBatteryController", "setFindRing", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isOppoPod(device)) return@hookBefore
                val state = args[1] as? Int ?: return@hookBefore
                val instanceContext = runCatching { getObjectField(instance, "context") as? Context }.getOrNull()
                if (instanceContext != null) {
                    context = instanceContext.applicationContext ?: instanceContext
                }
                rememberHeadsetController("com.miui.headset.runtime.AncBatteryController", instance, device)
                if (state == FIND_RING_IDLE && shouldIgnoreFindRingStop()) {
                    this.result = FIND_RING_RESULT_SUCCESS
                    return@hookBefore
                }
                val enabled = state != FIND_RING_IDLE
                if (enabled == currentGameMode) {
                    notifyFindRingChanged(instance, device)
                    this.result = FIND_RING_RESULT_SUCCESS
                    return@hookBefore
                }
                currentGameMode = enabled
                sendOppoGameMode(enabled, instanceContext)
                saveState(instanceContext)
                notifyFindRingChanged(instance, device)
                this.result = FIND_RING_RESULT_SUCCESS
            }
        }.onFailure { Log.w(TAG, "hook AncBatteryController.setFindRing skipped", it) }
    }

    private fun hookFindRingControllerCommand() {
        runCatching {
            val headsetInfoClass = findClass("com.miui.circulate.api.service.CirculateServiceInfo")
            val detailClass = findHeadSetsDetailClass()
            val controllerClass = detailClass ?.let { findHeadsetControllerClass(it, headsetInfoClass) } ?: findClass("com.miui.circulate.api.protocol.headset.C4652c0")
            val methods = controllerCommandMethods(controllerClass, headsetInfoClass)
            if (methods.isEmpty()) return
            methods.forEach { method ->
                hookBefore(method) {
                    val state = args[1] as? Int ?: return@hookBefore
                    if (state == FIND_RING_IDLE && isHeadSetsDetailDetachCall()) {
                        this.result = CompletableFuture.completedFuture(FIND_RING_RESULT_SUCCESS)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "hook headset controller command skipped", it) }
    }

    private fun hookFindRingTitle() {
        val synergyViewClass = listOf(
            "com.miui.circulate.world.sticker.ui.SynergyView",
            "com.miui.circulate.world.sticker.p067ui.SynergyView"
        ).firstNotNullOfOrNull { className -> runCatching { findClass(className) }.getOrNull() } ?: return
        runCatching {
            hookBefore(synergyViewClass.getDeclaredMethod("setTitle", Int::class.javaPrimitiveType!!).apply { isAccessible = true }) {
                val view = instance as? View ?: return@hookBefore
                val resId = args[0] as? Int ?: return@hookBefore
                val title = gameModeTitleReplacement(view, resId) ?: return@hookBefore
                if (!setSynergyTitle(view, title)) return@hookBefore
                this.result = null
            }
        }.onFailure { Log.w(TAG, "hook SynergyView.setTitle skipped", it) }
    }

    private fun hookHeadsetInfoNoArg(methodName: String, reconnectOnRead: Boolean = false, result: () -> Any) {
        runCatching {
            hookAfter(findMethodByParamCount("com.miui.headset.api.HeadsetInfo", methodName, 0)) {
                if (!isTargetHeadsetInfo(instance)) return@hookAfter
                if (reconnectOnRead) {
                    requestPanelBluetoothStatus("HeadsetInfo.$methodName")
                }
                this.result = result()
            }
        }.onFailure { Log.w(TAG, "hook HeadsetInfo.$methodName skipped", it) }
    }

    private fun registerStatusReceiver(ctx: Context?) {
        if (ctx == null || receiverRegistered) return
        context = ctx.applicationContext ?: ctx
        val filter = IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_CONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_DISCONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED)
        }
        context?.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    OppoPodsAction.ACTION_PODS_CONNECTED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentName = intent.getStringExtra("device_name") ?: currentName
                        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
                    }
                    OppoPodsAction.ACTION_PODS_DISCONNECTED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                    }
                    OppoPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentBattery = intent.parcelableStatus() ?: currentBattery
                        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
                        saveState(context)
                    }
                    OppoPodsAction.ACTION_PODS_ANC_CHANGED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentAnc = intent.getIntExtra("status", currentAnc)
                        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
                        saveState(context)
                    }
                    OppoPodsAction.ACTION_PODS_GAME_MODE_CHANGED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentGameMode = intent.getBooleanExtra("enabled", currentGameMode)
                        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
                        saveState(context)
                        notifyFindRingChanged()
                    }
                }
            }
        }, filter, Context.RECEIVER_EXPORTED)
        receiverRegistered = true
        requestBluetoothStatus("receiver-register")
    }

    private fun requestPanelBluetoothStatus(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPanelRefreshMs < PANEL_REFRESH_THROTTLE_MS) return
        lastPanelRefreshMs = now
        requestBluetoothStatus("panel-$reason", allowReconnect = true)
    }

    private fun requestBluetoothStatus(reason: String, allowReconnect: Boolean = false) {
        val ctx = context ?: return
        ctx.sendBroadcast(Intent(OppoPodsAction.ACTION_REFRESH_STATUS).apply {
            putExtra(OppoPodsAction.EXTRA_ALLOW_RFCOMM_RECONNECT, allowReconnect)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
    }

    private fun isOppoPod(device: BluetoothDevice): Boolean {
        val address = runCatching { device.address }.getOrNull()
        if (address != null && isOppoAddress(address)) return true
        val name = runCatching { device.name ?: device.alias }.getOrNull().orEmpty()
        // 🎯 全盘变更拦截前缀为华为 FreeBuds facts
        val result = name.contains("FreeBuds", ignoreCase = true)
        if (result && address != null) {
            knownOppoAddresses.add(address.uppercase())
            currentAddress = address
            currentName = name
        }
        return result
    }

    private fun isOppoAddress(address: String): Boolean {
        val normalized = address.uppercase()
        return normalized == currentAddress?.uppercase() || normalized in knownOppoAddresses
    }

    private fun isTargetHeadsetInfo(info: Any?): Boolean {
        if (info == null) return false
        listOf("getAddress", "component1").forEach { method ->
            val address = runCatching { callMethod(info, method) as? String }.getOrNull()
            if (address != null && isOppoAddress(address)) return true
        }
        return false
    }

    private fun miLinkAncState(): Int {
        loadState()
        return when (currentAnc) {
            2 -> 1
            3 -> 2
            else -> 0
        }
    }

    private fun oppoAncFromMiLink(mode: Int): Int {
        return when (mode) {
            1 -> 2
            2 -> 3
            else -> 1
        }
    }

    private fun miLinkFindRingState(): Int {
        loadState()
        return if (currentGameMode) FIND_RING_ACTIVE else FIND_RING_IDLE
    }

    private fun miLinkFindRingActive(): Boolean {
        loadState()
        return currentGameMode
    }

    private fun miLinkBatteryLevels(): List<Int> {
        loadState()
        val left = batteryValue(currentBattery.left)
        val right = batteryValue(currentBattery.right)
        val box = batteryValue(currentBattery.case)
        return listOf(box, left, right, chargingValue(currentBattery.case), chargingValue(currentBattery.left), chargingValue(currentBattery.right))
    }

    private fun batteryPercentForMiLink(): Int {
        loadState()
        val values = listOfNotNull(currentBattery.left, currentBattery.right)
            .filter { it.isConnected }
            .map { it.battery.coerceIn(0, 100) }
        return values.minOrNull() ?: 0
    }

    private fun batteryValue(params: PodParams?): Int {
        if (params?.isConnected != true) return 255
        return params.battery.coerceIn(0, 100)
    }

    private fun chargingValue(params: PodParams?): Int {
        return if (params?.isConnected == true && params.isCharging) 1 else 0
    }

    private fun sendOppoAnc(mode: Int, fallbackContext: Context? = null) {
        val ctx = fallbackContext ?: context ?: return
        Intent(OppoPodsAction.ACTION_ANC_SELECT).apply {
            putExtra("status", mode)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            ctx.sendBroadcast(this)
        }
    }

    private fun sendOppoGameMode(enabled: Boolean, fallbackContext: Context? = null) {
        val ctx = fallbackContext ?: context ?: return
        Intent(OppoPodsAction.ACTION_GAME_MODE_SET).apply {
            putExtra("enabled", enabled)
            setPackage("com.android.bluetooth")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            ctx.sendBroadcast(this)
        }
    }

    private fun sendMiLinkAncChanged(mode: Int, fallbackContext: Context? = null) {
        val ctx = fallbackContext ?: context ?: return
        ctx.sendBroadcast(Intent(OppoPodsAction.ACTION_PODS_ANC_CHANGED).apply {
            putExtra("status", mode)
            setPackage("com.milink.service")
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
    }

    private fun notifyFindRingChanged(controller: Any? = lastHeadsetController, device: BluetoothDevice? = lastHeadsetDevice) {
        if (controller == null || device == null) return
        notifyHeadsetPropertyChanged(controller, device, HEADSET_FIND_RING_CHANGED)
    }

    private fun rememberHeadsetController(className: String, controller: Any?, device: BluetoothDevice) {
        if (className != "com.miui.headset.runtime.AncBatteryController") return
        lastHeadsetController = controller
        lastHeadsetDevice = device
    }

    private fun shouldIgnoreFindRingStop(): Boolean = isHeadSetsDetailDetachCall()

    private fun findHeadSetsDetailClass(): Class<*>? {
        return listOf(
            "com.miui.circulateplus.world.headset.HeadSetsDetail",
            "com.miui.circulate.world.headset.HeadSetsDetail",
            "com.miui.circulate.world.detail.HeadSetsDetail"
        ).firstNotNullOfOrNull { className -> runCatching { findClass(className) }.getOrNull() }
    }

    private fun findHeadsetControllerClass(detailClass: Class<*>, headsetInfoClass: Class<*>): Class<*>? {
        runCatching { detailClass.getDeclaredMethod("getHeadsetController").returnType }.getOrNull()
            ?.takeIf { hasHeadsetControllerCommandSignature(it, headsetInfoClass) }?.let { return it }
        return detailClass.declaredFields.map { it.type }.firstOrNull { hasHeadsetControllerCommandSignature(it, headsetInfoClass) }
    }

    private fun hasHeadsetControllerCommandSignature(controllerClass: Class<*>, headsetInfoClass: Class<*>): Boolean {
        return controllerCommandMethods(controllerClass, headsetInfoClass).isNotEmpty()
    }

    private fun controllerCommandMethods(controllerClass: Class<*>, headsetInfoClass: Class<*>): List<java.lang.reflect.Method> {
        val intType = Int::class.javaPrimitiveType!!
        return controllerClass.declaredMethods.filter { method ->
            CompletableFuture::class.java.isAssignableFrom(method.returnType) &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == headsetInfoClass &&
                method.parameterTypes[1] == intType
        }.onEach { it.isAccessible = true }
    }

    private fun isHeadSetsDetailDetachCall(): Boolean {
        return Throwable().stackTrace.any { it.className.endsWith(".HeadSetsDetail") && it.methodName == "onDetachedFromWindow" }
    }

    private fun gameModeTitleReplacement(view: View, resId: Int): CharSequence? {
        val viewName = resourceEntryName(view, view.id)
        if (viewName != "mi_audio_ringing_view" && viewName != "audio_ringing_view") return null
        return when (resourceEntryName(view, resId)) {
            "circulate_headset_control_audio_find_earphone" -> "打开空间音频"
            "circulate_headset_control_audio_stop_find_earphone" -> "关闭空间音频"
            else -> null
        }
    }

    private fun resourceEntryName(view: View, resId: Int): String? {
        if (resId == View.NO_ID) return null
        return runCatching { view.resources.getResourceEntryName(resId) }.getOrNull()
    }

    private fun setSynergyTitle(view: View, title: CharSequence): Boolean {
        return runCatching {
            view.javaClass.getDeclaredMethod("setTitle", CharSequence::class.java).apply { isAccessible = true }.invoke(view, title)
            true
        }.getOrDefault(false)
    }

    private fun notifyHeadsetPropertyChanged(controller: Any?, device: BluetoothDevice, updateType: Int) {
        val listener = runCatching { getObjectField(controller, "headsetPropertyChangeListener") }.getOrNull() ?: return
        runCatching { callMethod(listener, "invoke", device, updateType) }
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableStatus(): BatteryParams? {
        return runCatching { getParcelableExtra("status", BatteryParams::class.java) }.getOrNull()
            ?: runCatching { getParcelableExtra<BatteryParams>("status") }.getOrNull()
    }

    private fun saveState(ctx: Context?) {
        val prefs = (ctx ?: context)?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        prefs.edit()
            .putString("address", currentAddress)
            .putString("name", currentName)
            .putInt("anc", currentAnc)
            .putBoolean("game_mode", currentGameMode)
            .putInt("left_battery", currentBattery.left?.battery ?: 0)
            .putBoolean("left_connected", currentBattery.left?.isConnected == true)
            .putBoolean("left_charging", currentBattery.left?.isCharging == true)
            .putInt("right_battery", currentBattery.right?.battery ?: 0)
            .putBoolean("right_connected", currentBattery.right?.isConnected == true)
            .putBoolean("right_charging", currentBattery.right?.isCharging == true)
            .putInt("case_battery", currentBattery.case?.battery ?: 0)
            .putBoolean("case_connected", currentBattery.case?.isConnected == true)
            .putBoolean("case_charging", currentBattery.case?.isCharging == true)
            .apply()
    }

    private fun loadState() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        currentAddress = prefs.getString("address", currentAddress)
        currentName = prefs.getString("name", currentName)
        currentAnc = prefs.getInt("anc", currentAnc)
        currentGameMode = prefs.getBoolean("game_mode", currentGameMode)
        currentAddress?.let { knownOppoAddresses.add(it.uppercase()) }
        currentBattery = BatteryParams(
            left = PodParams(prefs.getInt("left_battery", 0), prefs.getBoolean("left_charging", false), prefs.getBoolean("left_connected", false), 0),
            right = PodParams(prefs.getInt("right_battery", 0), prefs.getBoolean("right_charging", false), prefs.getBoolean("right_connected", false), 0),
            case = PodParams(prefs.getInt("case_battery", 0), prefs.getBoolean("case_charging", false), prefs.getBoolean("case_connected", false), 0)
        )
    }
}