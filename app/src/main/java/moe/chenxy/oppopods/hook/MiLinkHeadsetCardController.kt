package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcelable
import android.os.SystemClock
import android.util.Log
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsPrefsKey
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method

@SuppressLint("StaticFieldLeak")
object MiLinkHeadsetCardController {
    private const val TAG = "OppoPods-MiLinkCard"
    private const val ML_CARD_ACTION = "com.miLink.card.frame.show"
    private const val ML_CARD_PACKAGE = "com.milink.service"
    private const val ML_CARD_SERVICE = "com.miui.circulate.world.MLCardViewHostService"
    private const val DEVICE_CATEGORY_NEARBY = "nearby"
    private const val DEVICE_TYPE_HEADSET_CARD = "headset"
    private const val DEVICE_TYPE_HEADSET_SERVICE = "Headset"
    private const val CONNECT_STATE_CONNECTED = 2
    private const val CONNECT_STATE_DISCONNECTED = 0
    private const val PROTOCOL_TYPE_HEADSET = 0x60000
    private const val CREATE_REMOTE_VIEW_REQUEST = 1
    private const val SHOW_THROTTLE_MS = 4_000L
    private const val PRIVATE_MARKER = "oppopods_milink_headset_card"
    private const val PRIVATE_ADDRESS = "oppopods_address"
    private const val PRIVATE_NAME = "oppopods_name"
    private const val FAKE_DEVICE_ID = "01010901"

    private lateinit var hookContext: HookContext
    private var installed = false
    @Volatile
    private var enabled = OppoPodsPrefsKey.DEFAULT_MILINK_HEADSET_CARD
    private val requestedCardAddresses = linkedSetOf<String>()
    private var currentAddress: String? = null
    private var currentName: String? = null
    private var lastShowRequestMs = 0L
    private var boundContext: Context? = null
    private var cardServiceConnection: ServiceConnection? = null
    private var cardServiceMessenger: Messenger? = null
    private var pendingDeviceInfo: Parcelable? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val clientMessenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { msg ->
            Log.d(TAG, "client callback what=${msg.what} data=${msg.data}")
            true
        })
    }

    fun install(hookContext: HookContext) {
        if (installed) return
        installed = true
        this.hookContext = hookContext
        enabled = hookContext.prefs.getBoolean(
            OppoPodsPrefsKey.MILINK_HEADSET_CARD,
            OppoPodsPrefsKey.DEFAULT_MILINK_HEADSET_CARD
        )
        hookMlCardHost()
        hookHeadsetDeviceInfoProvider()
        Log.d(TAG, "installed enabled=$enabled")
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            requestedCardAddresses.clear()
            unbindCardService()
        }
        Log.d(TAG, "enabled changed: $enabled")
    }

    fun onPodsConnected(context: Context?, address: String?, name: String?, value: Boolean) {
        setEnabled(value)
        if (!enabled || context == null || address.isNullOrBlank()) return
        rememberHeadset(address, name, context, markForCard = true)
        requestShow(context, address, name)
    }

    fun onPodsBatteryChanged(address: String?, name: String?) {
        if (address.isNullOrBlank()) return
        rememberHeadset(address, name, null, markForCard = false)
    }

    fun onPodsDisconnected(address: String?) {
        address?.normalizedAddress()?.let { requestedCardAddresses.remove(it) }
        if (address == null || address.equals(currentAddress, ignoreCase = true)) {
            currentAddress = null
        }
        unbindCardService()
        Log.d(TAG, "pods disconnected address=$address")
    }

    private fun requestShow(context: Context, address: String, name: String?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastShowRequestMs < SHOW_THROTTLE_MS) {
            Log.d(TAG, "show throttled address=$address")
            return
        }
        lastShowRequestMs = now

        val deviceInfo = buildDeviceInfo(address, name) ?: return
        pendingDeviceInfo = deviceInfo
        val appContext = context.applicationContext ?: context
        mainHandler.post {
            val messenger = cardServiceMessenger
            if (messenger != null) {
                sendCreateCardMessage(messenger, deviceInfo)
                return@post
            }
            bindCardService(appContext, deviceInfo)
        }
    }

    private fun bindCardService(context: Context, deviceInfo: Parcelable) {
        if (cardServiceConnection != null) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                cardServiceMessenger = Messenger(service)
                Log.d(TAG, "MLCard service connected name=$name")
                val targetDeviceInfo = pendingDeviceInfo ?: deviceInfo
                sendCreateCardMessage(cardServiceMessenger ?: return, targetDeviceInfo)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.d(TAG, "MLCard service disconnected name=$name")
                cardServiceMessenger = null
                cardServiceConnection = null
                boundContext = null
            }
        }

        val explicitIntent = Intent(ML_CARD_ACTION).apply {
            component = ComponentName(ML_CARD_PACKAGE, ML_CARD_SERVICE)
        }
        val bound = runCatching {
            context.bindService(explicitIntent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse {
            Log.w(TAG, "bind explicit MLCard service failed", it)
            false
        } || runCatching {
            context.bindService(
                Intent(ML_CARD_ACTION).setPackage(ML_CARD_PACKAGE),
                connection,
                Context.BIND_AUTO_CREATE
            )
        }.getOrElse {
            Log.w(TAG, "bind action MLCard service failed", it)
            false
        }

        if (bound) {
            cardServiceConnection = connection
            boundContext = context
            Log.d(TAG, "MLCard service bind requested")
        } else {
            pendingDeviceInfo = null
            Log.w(TAG, "MLCard service bind rejected")
        }
    }

    private fun sendCreateCardMessage(messenger: Messenger, deviceInfo: Parcelable) {
        runCatching {
            val data = Bundle().apply {
                classLoader = deviceInfo.javaClass.classLoader
                putParcelable("deviceInfo", deviceInfo)
                putString("keyDeviceToSpecifyPanel", "")
            }
            Message.obtain(null, CREATE_REMOTE_VIEW_REQUEST).apply {
                this.data = data
                replyTo = clientMessenger
                messenger.send(this)
            }
            Log.d(TAG, "create card message sent deviceInfo=$deviceInfo")
        }.onFailure { Log.w(TAG, "send create card message failed", it) }
    }

    private fun unbindCardService() {
        val connection = cardServiceConnection ?: return
        val context = boundContext
        runCatching {
            context?.unbindService(connection)
        }.onFailure { Log.w(TAG, "unbind MLCard service failed", it) }
        cardServiceConnection = null
        cardServiceMessenger = null
        boundContext = null
        pendingDeviceInfo = null
    }

    private fun hookMlCardHost() {
        val hostClass = findClassOrNull(ML_CARD_SERVICE) ?: run {
            Log.w(TAG, "MLCard host hook skipped: service class not found")
            return
        }
        val deviceInfoClass = findClassOrNull("com.miui.circulate.device.api.DeviceInfo") ?: run {
            Log.w(TAG, "MLCard host hook skipped: DeviceInfo class not found")
            return
        }
        findMethodByNames(hostClass, listOf("L", "mo16254L"), deviceInfoClass)?.let { method ->
            hookContext.hookBefore(method) {
                val deviceInfo = args[0] ?: return@hookBefore
                if (!isOppoCardDeviceInfo(deviceInfo)) return@hookBefore
                rememberFromDeviceInfo(deviceInfo, instance as? Context)
                result = false
                Log.d(TAG, "MLCard precheck bypassed for OPPO headset card")
            }
            Log.d(TAG, "hooked ${hostClass.name}.${method.name}")
        } ?: Log.w(TAG, "MLCard precheck hook skipped: method not found")

        findMethodByNames(
            hostClass,
            listOf("v", "mo16192v"),
            deviceInfoClass,
            Int::class.javaPrimitiveType!!
        )?.let { method ->
            hookContext.hookAfter(method) {
                val deviceInfo = args[0] ?: return@hookAfter
                val cardId = args[1] as? Int ?: return@hookAfter
                if (!isOppoCardDeviceInfo(deviceInfo)) return@hookAfter
                rememberFromDeviceInfo(deviceInfo, instance as? Context)
                injectHeadsetStrategy(instance ?: return@hookAfter, deviceInfo, cardId)
            }
            Log.d(TAG, "hooked ${hostClass.name}.${method.name}")
        } ?: Log.w(TAG, "MLCard create-view hook skipped: method not found")
    }

    private fun hookHeadsetDeviceInfoProvider() {
        val controllerClass = findClassOrNull(
            "com.miui.circulate.api.protocol.headset.c0",
            "com.miui.circulate.api.protocol.headset.C4652c0"
        ) ?: run {
            Log.w(TAG, "HeadsetDeviceInfo hook skipped: controller class not found")
            return
        }
        val serviceInfoClass = findClassOrNull("com.miui.circulate.api.service.CirculateServiceInfo") ?: run {
            Log.w(TAG, "HeadsetDeviceInfo hook skipped: service info class not found")
            return
        }
        val method = findMethodByNames(controllerClass, listOf("E", "m19736E"), serviceInfoClass) ?: run {
            Log.w(TAG, "HeadsetDeviceInfo hook skipped: method not found")
            return
        }
        hookContext.hookAfter(method) {
            val serviceInfo = args[0] ?: return@hookAfter
            val address = getStringField(serviceInfo, "deviceId") ?: return@hookAfter
            if (!isRequestedCardAddress(address)) return@hookAfter
            result = buildHeadsetDeviceInfo(address, currentName)
            Log.d(TAG, "HeadsetDeviceInfo supplied for OPPO headset address=$address")
        }
        Log.d(TAG, "hooked ${controllerClass.name}.${method.name}")
    }

    private fun injectHeadsetStrategy(service: Any, deviceInfo: Any, cardId: Int) {
        val address = extractDeviceAddress(deviceInfo) ?: return
        val name = extractDeviceName(deviceInfo)
        rememberHeadset(address, name, service as? Context, markForCard = true)

        val strategy = ensureHeadsetStrategy(service) ?: return
        val serviceInfo = buildCirculateServiceInfo(address) ?: return
        val attachDeviceInfo = buildCirculateDeviceInfo(address, name, serviceInfo) ?: return

        setObjectField(service, listOf("I", "mCardId"), cardId)
        setObjectField(service, listOf("J", "mDeviceInfo"), deviceInfo)
        setObjectField(service, listOf("R", "mDeviceStrategy"), strategy)

        val method = findMethodByNames(
            strategy.javaClass,
            listOf("d", "mo21544d"),
            Int::class.javaPrimitiveType!!,
            attachDeviceInfo.javaClass,
            serviceInfo.javaClass
        ) ?: run {
            Log.w(TAG, "inject skipped: headset strategy connect method not found")
            return
        }
        runCatching {
            method.invoke(strategy, CONNECT_STATE_CONNECTED, attachDeviceInfo, serviceInfo)
            Log.d(TAG, "headset strategy injected address=$address cardId=$cardId")
        }.onFailure { Log.w(TAG, "headset strategy inject failed", it) }
    }

    private fun dismissInjectedCard(service: Any, address: String) {
        val strategy = getObjectField(service, listOf("R", "mDeviceStrategy")) ?: return
        val serviceInfo = buildCirculateServiceInfo(address) ?: return
        val attachDeviceInfo = buildCirculateDeviceInfo(address, currentName, serviceInfo) ?: return
        val method = findMethodByNames(
            strategy.javaClass,
            listOf("d", "mo21544d"),
            Int::class.javaPrimitiveType!!,
            attachDeviceInfo.javaClass,
            serviceInfo.javaClass
        ) ?: return
        runCatching {
            method.invoke(strategy, CONNECT_STATE_DISCONNECTED, attachDeviceInfo, serviceInfo)
        }.onFailure { Log.w(TAG, "dismiss card failed", it) }
    }

    private fun ensureHeadsetStrategy(service: Any): Any? {
        val current = getObjectField(service, listOf("R", "mDeviceStrategy"))
        if (current?.javaClass?.name in setOf("j9.c", "p224j9.C12254c")) return current
        val strategyClass = findClassOrNull("j9.c", "p224j9.C12254c") ?: run {
            Log.w(TAG, "headset strategy class not found")
            return null
        }
        val constructor = strategyClass.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(service.javaClass)
        } ?: strategyClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 1 }
        return runCatching {
            constructor?.apply { isAccessible = true }?.newInstance(service)
        }.getOrElse {
            Log.w(TAG, "create headset strategy failed", it)
            null
        }
    }

    private fun buildDeviceInfo(address: String, name: String?): Parcelable? {
        val builderClass = findClassOrNull("com.miui.circulate.device.api.DeviceInfo\$Builder") ?: return null
        return runCatching {
            val displayName = name?.takeIf { it.isNotBlank() } ?: "OPPO Earphones"
            val builder = builderClass.getDeclaredConstructor().newInstance()
            invokeByName(builder, "setId", address)
            invokeByName(builder, "setCategory", DEVICE_CATEGORY_NEARBY)
            invokeByName(builder, "setDeviceType", DEVICE_TYPE_HEADSET_CARD)
            invokeByName(builder, "setTitle", displayName)
            invokeByName(builder, "setSubtitle", "OPPOPods")
            invokeByName(builder, "setMac", address)
            invokeByName(builder, "setState", CONNECT_STATE_CONNECTED)
            invokeByName(builder, "putPrivateData", PRIVATE_MARKER, true)
            invokeByName(builder, "putPrivateData", PRIVATE_ADDRESS, address)
            invokeByName(builder, "putPrivateData", PRIVATE_NAME, displayName)
            invokeByName(builder, "build") as? Parcelable
        }.getOrElse {
            Log.w(TAG, "build DeviceInfo failed", it)
            null
        }
    }

    private fun buildCirculateServiceInfo(address: String): Any? {
        val serviceInfoClass = findClassOrNull("com.miui.circulate.api.service.CirculateServiceInfo") ?: return null
        return runCatching {
            val serviceInfo = serviceInfoClass.getDeclaredConstructor().newInstance()
            setObjectField(serviceInfo, listOf("deviceId"), address)
            setObjectField(serviceInfo, listOf("deviceType"), DEVICE_TYPE_HEADSET_SERVICE)
            setObjectField(serviceInfo, listOf("serviceId"), "oppopods-$address")
            setObjectField(serviceInfo, listOf("protocolType"), PROTOCOL_TYPE_HEADSET)
            setObjectField(serviceInfo, listOf("connectState"), CONNECT_STATE_CONNECTED)
            invokeByName(serviceInfo, "setHeadsetId", FAKE_DEVICE_ID, 1)
            invokeByName(serviceInfo, "setHeadsetType", 0)
            serviceInfo
        }.getOrElse {
            Log.w(TAG, "build CirculateServiceInfo failed", it)
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildCirculateDeviceInfo(address: String, name: String?, serviceInfo: Any): Any? {
        val deviceInfoClass = findClassOrNull("com.miui.circulate.api.service.CirculateDeviceInfo") ?: return null
        return runCatching {
            val deviceInfo = deviceInfoClass.getDeclaredConstructor().newInstance()
            val hostId = "oppopods-host-${address.normalizedAddress()}"
            val displayName = name?.takeIf { it.isNotBlank() } ?: "OPPO Earphones"
            setObjectField(deviceInfo, listOf("id", "f16845id"), hostId)
            setObjectField(deviceInfo, listOf("idHash"), hostId)
            setObjectField(deviceInfo, listOf("devicesName"), displayName)
            setObjectField(deviceInfo, listOf("devicesType"), "AndroidPhone")
            setObjectField(deviceInfo, listOf("deviceCategory"), DEVICE_CATEGORY_NEARBY)
            setObjectField(deviceInfo, listOf("deviceProperties"), emptyExtraBundle())
            (getObjectField(deviceInfo, listOf("circulateServices")) as? MutableSet<Any>)?.add(serviceInfo)
            deviceInfo
        }.getOrElse {
            Log.w(TAG, "build CirculateDeviceInfo failed", it)
            null
        }
    }

    private fun buildHeadsetDeviceInfo(address: String, name: String?): Any? {
        val headsetInfoClass = findClassOrNull("com.miui.circulate.api.protocol.headset.HeadsetDeviceInfo") ?: return null
        return runCatching {
            val displayName = name?.takeIf { it.isNotBlank() } ?: currentName ?: "OPPO Earphones"
            val headsetInfo = headsetInfoClass.getDeclaredConstructor().newInstance()
            setObjectField(headsetInfo, listOf("mac"), address)
            setObjectField(headsetInfo, listOf("deviceId"), address)
            setObjectField(headsetInfo, listOf("name"), displayName)
            setObjectField(headsetInfo, listOf("vidPid"), FAKE_DEVICE_ID)
            setObjectField(headsetInfo, listOf("headsetVolume"), 100)
            setObjectField(headsetInfo, listOf("type"), 0)
            setObjectField(headsetInfo, listOf("wiredState"), 0)
            setObjectField(headsetInfo, listOf("audioEffectState"), 0)
            setObjectField(headsetInfo, listOf("findRingState"), MiLinkServiceHook.headsetCardFindRingState())
            setObjectField(headsetInfo, listOf("isOutput"), true)
            setObjectField(headsetInfo, listOf("mode"), MiLinkServiceHook.headsetCardAncState())
            setObjectField(headsetInfo, listOf("noNeedBackBox"), true)
            setObjectField(headsetInfo, listOf("power"), MiLinkServiceHook.headsetCardBatteryLevels())
            headsetInfo
        }.getOrElse {
            Log.w(TAG, "build HeadsetDeviceInfo failed", it)
            null
        }
    }

    private fun emptyExtraBundle(): Any? {
        return runCatching {
            findClassOrNull("com.miui.circulate.api.bean.ExtraBundle")
                ?.getDeclaredMethod("empty")
                ?.invoke(null)
        }.getOrNull()
    }

    private fun rememberFromDeviceInfo(deviceInfo: Any, context: Context?) {
        val address = extractDeviceAddress(deviceInfo) ?: return
        rememberHeadset(address, extractDeviceName(deviceInfo), context, markForCard = true)
    }

    private fun rememberHeadset(address: String, name: String?, context: Context?, markForCard: Boolean) {
        currentAddress = address
        if (!name.isNullOrBlank()) currentName = name
        if (markForCard) requestedCardAddresses.add(address.normalizedAddress())
        MiLinkServiceHook.rememberOppoHeadset(address, name, context)
    }

    private fun isOppoCardDeviceInfo(deviceInfo: Any): Boolean {
        enabled = hookContext.prefs.getBoolean(
            OppoPodsPrefsKey.MILINK_HEADSET_CARD,
            OppoPodsPrefsKey.DEFAULT_MILINK_HEADSET_CARD
        )
        if (!enabled) return false
        val privateData = invokeByName(deviceInfo, "getPrivateData") as? String
        if (!privateData.isNullOrBlank()) {
            val marked = runCatching { JSONObject(privateData).optBoolean(PRIVATE_MARKER, false) }
                .getOrDefault(privateData.contains(PRIVATE_MARKER))
            if (marked) return true
        }
        val address = extractDeviceAddress(deviceInfo) ?: return false
        return isRequestedCardAddress(address)
    }

    private fun extractDeviceAddress(deviceInfo: Any): String? {
        val privateData = invokeByName(deviceInfo, "getPrivateData") as? String
        if (!privateData.isNullOrBlank()) {
            runCatching { JSONObject(privateData).optString(PRIVATE_ADDRESS) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return (invokeByName(deviceInfo, "getMac") as? String)?.takeIf { it.isNotBlank() }
            ?: (invokeByName(deviceInfo, "getId") as? String)?.takeIf { it.isNotBlank() }
    }

    private fun extractDeviceName(deviceInfo: Any): String? {
        val privateData = invokeByName(deviceInfo, "getPrivateData") as? String
        if (!privateData.isNullOrBlank()) {
            runCatching { JSONObject(privateData).optString(PRIVATE_NAME) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return (invokeByName(deviceInfo, "getTitle") as? String)?.takeIf { it.isNotBlank() }
            ?: currentName
    }

    private fun isRequestedCardAddress(address: String): Boolean {
        return address.normalizedAddress() in requestedCardAddresses
    }

    private fun findClassOrNull(vararg names: String): Class<*>? {
        return names.firstNotNullOfOrNull { name ->
            runCatching { hookContext.findClass(name) }.getOrNull()
        }
    }

    private fun findMethodByNames(
        cls: Class<*>,
        names: List<String>,
        vararg parameterTypes: Class<*>
    ): Method? {
        var current: Class<*>? = cls
        while (current != null) {
            names.forEach { name ->
                runCatching {
                    return current.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun findMethodByNameAndParamCount(cls: Class<*>, name: String, paramCount: Int): Method? {
        var current: Class<*>? = cls
        while (current != null) {
            current.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == paramCount }
                ?.let { return it.apply { isAccessible = true } }
            current = current.superclass
        }
        return null
    }

    private fun invokeByName(instance: Any, name: String, vararg args: Any?): Any? {
        return findMethodByNameAndParamCount(instance.javaClass, name, args.size)
            ?.invoke(instance, *args)
    }

    private fun findField(instance: Any, names: List<String>): Field? {
        var current: Class<*>? = instance.javaClass
        while (current != null) {
            names.forEach { name ->
                runCatching {
                    return current.getDeclaredField(name).apply { isAccessible = true }
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun getObjectField(instance: Any?, names: List<String>): Any? {
        if (instance == null) return null
        return findField(instance, names)?.get(instance)
    }

    private fun getStringField(instance: Any?, name: String): String? {
        return getObjectField(instance, listOf(name)) as? String
    }

    private fun setObjectField(instance: Any?, names: List<String>, value: Any?): Boolean {
        if (instance == null) return false
        val field = findField(instance, names) ?: return false
        return runCatching {
            field.set(instance, value)
            true
        }.getOrDefault(false)
    }

    private fun String.normalizedAddress(): String = uppercase()
}
