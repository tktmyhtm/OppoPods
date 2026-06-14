package moe.chenxy.oppopods.hook

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 华为耳机 HyperOS 系统蓝牙总线拦截引擎
 *
 * 在系统蓝牙进程（com.android.bluetooth）内部直接拦截 GATT 数据流：
 * 1. 正向：拦截 BLE Notify，原地提取电量/降噪，发送小米原生广播
 * 2. 反向：拦截 writeCharacteristic，将小米降噪指令调包为华为原厂指令
 * 3. 伪装：将华为耳机的设备类型篡改为小米原生耳机（Type=2）
 */
object SystemBusDispatcher : HookContext() {
    private const val TAG = "OppoPods-SystemBus"
    // +++ 修改为你的 FreeBuds 真实 MAC 地址！+++
    private const val TARGET_MAC = "14:51:20:CB:2F:69"

    override fun onHook() {
        Log.e(TAG, "⚡ [系统总线拦截引擎] 初始化...")

        // 1. 伪装设备类型
        hookGetDeviceType()

        // 2. 正向拦截 BLE Notify 电量/降噪
        hookOnCharacteristicChanged()

        // 3. 反向调包降噪指令
        hookWriteCharacteristic()

        Log.e(TAG, "✅ [系统总线拦截引擎] 初始化完成")
    }

    /**
     * 拦截 RemoteDevices.getDeviceType()，把华为耳机伪装成小米 Type=2
     */
    private fun hookGetDeviceType() {
        runCatching {
            val method = findMethod(
                "com.android.bluetooth.btservice.RemoteDevices",
                "getDeviceType",
                BluetoothDevice::class.java
            )
            hookAfter(method) {
                val device = args[0] as? BluetoothDevice ?: return@hookAfter
                if (device.address == TARGET_MAC) {
                    result = 2
                    Log.e(TAG, "🧬 [伪装] 设备类型已篡改为 Type=2")
                }
            }
        }.onFailure {
            Log.w(TAG, "⚠️ hook getDeviceType 跳过: ")
        }
    }

    /**
     * 拦截 GattService.onCharacteristicChanged，提取电量/降噪
     */
    private fun hookOnCharacteristicChanged() {
        val cachedLeft = intArrayOf(-1)
        val cachedRight = intArrayOf(-1)
        val cachedCase = intArrayOf(-1)
        val cachedAnc = intArrayOf(-1)

        // 先尝试 3 参数版本，失败则试 2 参数版本
        val tried3 = runCatching {
            val method = findMethodByParamCount(
                "com.android.bluetooth.gatt.GattService",
                "onCharacteristicChanged",
                3
            )
            hookBefore(method) {
                val device = args.getOrNull(0) as? BluetoothDevice ?: return@hookBefore
                val characteristic = args.getOrNull(1) as? BluetoothGattCharacteristic ?: return@hookBefore
                if (device.address != TARGET_MAC) return@hookBefore

                @Suppress("DEPRECATION")
                val rawData = characteristic.value ?: return@hookBefore
                if (rawData.size < 16 || rawData[0] != 0x5A.toByte()) return@hookBefore

                extractAndBroadcast(rawData, cachedLeft, cachedRight, cachedCase, cachedAnc)
            }
        }.isSuccess

        if (!tried3) {
            runCatching {
                val method = findMethod(
                    "com.android.bluetooth.gatt.GattService",
                    "onCharacteristicChanged",
                    BluetoothDevice::class.java,
                    BluetoothGattCharacteristic::class.java
                )
hookBefore(method) {
    val device = args[0] as? BluetoothDevice ?: return@hookBefore
    val characteristic = args[1] as? BluetoothGattCharacteristic ?: return@hookBefore
                    if (device.address != TARGET_MAC) return@hookBefore

                    @Suppress("DEPRECATION")
                    val rawData = characteristic.value ?: return@hookBefore
                    if (rawData.size < 16 || rawData[0] != 0x5A.toByte()) return@hookBefore

                    extractAndBroadcast(rawData, cachedLeft, cachedRight, cachedCase, cachedAnc)
                }
            }.onFailure {
                Log.w(TAG, "⚠️ hook onCharacteristicChanged 全部跳过: ")
            }
        }
    }

    /**
     * 从原始字节中提取电量并发送广播
     */
    private fun extractAndBroadcast(
        rawData: ByteArray,
        cachedLeft: IntArray, cachedRight: IntArray,
        cachedCase: IntArray, cachedAnc: IntArray
    ) {
        val left = rawData[12].toInt() and 0xFF
        val right = rawData[13].toInt() and 0xFF
        val case = rawData[14].toInt() and 0xFF
        val ancByte = rawData[15].toInt() and 0xFF

        if (left > 100 && left != 127) return
        if (left == cachedLeft[0] && right == cachedRight[0] &&
            case == cachedCase[0] && ancByte == cachedAnc[0]) return

        cachedLeft[0] = left
        cachedRight[0] = right
        cachedCase[0] = case
        cachedAnc[0] = ancByte

        val xiaomiAncMode = when (ancByte) { 2 -> 2; 3 -> 3; else -> 1 }
        Log.e(TAG, "🔋 [总线捞取] 左: 右: 仓: 降噪:")

        val ctx = getContext() ?: return
        ctx.sendBroadcast(
            Intent("com.xiaomi.milink.action.DEVICE_STATUS_CHANGED").apply {
                putExtra("device_address", TARGET_MAC)
                putExtra("device_type", 2)
                putExtra("left_battery", if (left == 127) 0 else left)
                putExtra("right_battery", if (right == 127) 0 else right)
                putExtra("case_battery", if (case == 127) 0 else case)
                putExtra("anc_mode", xiaomiAncMode)
                putExtra("is_connected", true)
                setPackage("com.milink.service")
            }
        )
    }

    /**
     * 拦截 writeCharacteristic，将小米降噪指令调包为华为指令
     */
    private fun hookWriteCharacteristic() {
        runCatching {
            val method = findMethodByParamCount(
                "com.android.bluetooth.gatt.GattService",
                "writeCharacteristic",
                4
            )
            hookBefore(method) {
                val device = args.getOrNull(0) as? BluetoothDevice ?: return@hookBefore
                val characteristic = args.getOrNull(1) as? BluetoothGattCharacteristic ?: return@hookBefore
                if (device.address != TARGET_MAC) return@hookBefore

                @Suppress("DEPRECATION")
                val xiaomiCmd = characteristic.value ?: return@hookBefore
                if (xiaomiCmd.isEmpty()) return@hookBefore

                val targetMode = xiaomiCmd[0].toInt() and 0xFF
                Log.e(TAG, "🎯 [总线拦截] 小米降噪指令: ")

                val huaweiPayload = when (targetMode) {
                    2 -> byteArrayOf(0x5A, 0x01, 0x02)
                    3 -> byteArrayOf(0x5A, 0x01, 0x03)
                    else -> byteArrayOf(0x5A, 0x01, 0x01)
                }
                characteristic.setValue(huaweiPayload)
                Log.e(TAG, "🔥 [调包成功] 已替换为华为原厂指令")
            }
        }.onFailure {
            Log.w(TAG, "⚠️ hook writeCharacteristic 跳过: ")
        }
    }

    /**
     * 从 ActivityThread 获取系统 Context
     */
    private fun getContext(): Context? {
        return runCatching {
            val activityThread = findClass("android.app.ActivityThread")
            val method = activityThread.getDeclaredMethod("currentApplication")
            method.isAccessible = true
            method.invoke(null) as? Context
        }.getOrNull()
    }
}