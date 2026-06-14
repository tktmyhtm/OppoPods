package moe.chenxy.oppopods.hook

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.content.Intent
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class HookLoader : IXposedHookLoadPackage {
    private const val TAG = "OppoPods-CoreBus"
    private val TARGET_MAC = "14:51:*:*:*:69" // 锁定老哥你的华为耳机真实MAC

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // 🎯 斩首行动：只注入红米系统的核心蓝牙进程
        if (lpparam.packageName != "com.android.bluetooth") return

        Log.e(TAG, "⚡ [中央总线已接管] 成功强行并网 HyperOS 系统蓝牙堆栈！")

        try {
            // ========================================================
            // 🚀 正向流量拦截：把华为耳机欺骗改写为小米原生白名单设备
            // ========================================================
            XposedHelpers.findAndHookMethod(
                "com.android.bluetooth.btservice.RemoteDevices", 
                lpparam.classLoader,
                "getDeviceType", 
                BluetoothDevice::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val device = param.args[0] as? BluetoothDevice ?: return
                        if (device.address == TARGET_MAC) {
                            param.result = 2 // 强注册为小米原生智能耳机识别码 2
                            Log.e(TAG, "🧬 [伪装成功] 成功在总线入口将华为耳机改写为小米原生音频设备(Type=2)")
                        }
                    }
                }
            )

            // ========================================================
            // 🚀 正向无感广播：原地拦截特征值变化
            // ========================================================
            XposedHelpers.findAndHookMethod(
                "com.android.bluetooth.gatt.GattService", 
                lpparam.classLoader,
                "onCharacteristicChanged", 
                BluetoothDevice::class.java, 
                BluetoothGattCharacteristic::class.java, 
                object : XC_MethodHook() {
                    private var cachedLeft = -1
                    private var cachedRight = -1
                    private var cachedCase = -1
                    private var cachedAnc = -1

                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val device = param.args[0] as? BluetoothDevice ?: return
                        if (device.address != TARGET_MAC) return

                        val characteristic = param.args[1] as? BluetoothGattCharacteristic ?: return
                        @Suppress("DEPRECATION")
                        val rawData = characteristic.value ?: return
                        if (rawData.size < 15 || rawData[0] != 0x5A.toByte()) return

                        // 直接用纯原生底层字节切片提取电量
                        val left = rawData[12].toInt() and 0xFF
                        val right = rawData[13].toInt() and 0xFF
                        val case = rawData[14].toInt() and 0xFF
                        val ancByte = rawData[15].toInt() and 0xFF

                        if (left > 100 && left != 127) return 

                        // 状态对比节流阀，绝不向 Binder 发送冗余信息，杜绝爆栈
                        if (left == cachedLeft && right == cachedRight && case == cachedCase && ancByte == cachedAnc) {
                            return
                        }

                        cachedLeft = left
                        cachedRight = right
                        cachedCase = case
                        cachedAnc = ancByte

                        val xiaomiAncMode = if (ancByte == 2) 2 else if (ancByte == 3) 3 else 1
                        Log.e(TAG, "🔋 [总线捞取成功] 放行干净状态 -> 左: $left, 右: $right, 仓: $case, 降噪: $xiaomiAncMode")

                        val context = XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                            "currentApplication"
                        ) as? Context ?: return

                        // 发送高仿小米原生 Milink 插件的并网广播
                        val intent = Intent("com.xiaomi.milink.action.DEVICE_STATUS_CHANGED").apply {
                            putExtra("device_address", TARGET_MAC)
                            putExtra("device_type", 2) 
                            putExtra("left_battery", if (left == 127) 0 else left)
                            putExtra("right_battery", if (right == 127) 0 else right)
                            putExtra("case_battery", if (case == 127) 0 else case)
                            putExtra("anc_mode", xiaomiAncMode)
                            putExtra("is_connected", true)
                            setPackage("com.milink.service") 
                        }
                        context.sendBroadcast(intent)
                    }
                }
            )

            // ========================================================
            // 🚀 反向控制链调包
            // ========================================================
            XposedHelpers.findAndHookMethod(
                "com.android.bluetooth.gatt.GattService",
                lpparam.classLoader,
                "writeCharacteristic", 
                BluetoothDevice::class.java, 
                BluetoothGattCharacteristic::class.java, 
                Int::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val device = param.args[0] as? BluetoothDevice ?: return
                        if (device.address != TARGET_MAC) return

                        val characteristic = param.args[1] as? BluetoothGattCharacteristic ?: return
                        @Suppress("DEPRECATION")
                        val xiaomiCmd = characteristic.value ?: return
                        if (xiaomiCmd.isEmpty()) return
                        
                        Log.e(TAG, "🎯 [总线拦截] 逮到小米设备互联面板的降噪手势控制，准备原地执行华为调包...")
                        
                        val targetMode = xiaomiCmd[0].toInt() 
                        val huaweiPayload = when (targetMode) {
                            2 -> byteArrayOf(0x5A, 0x01, 0x02) // 降噪
                            3 -> byteArrayOf(0x5A, 0x01, 0x03) // 通透
                            else -> byteArrayOf(0x5A, 0x01, 0x01) // 关闭
                        }
                        
                        // 🛡️ 核心修复点：弃用 .value 赋值属性，改用标准原生显式方法调用，彻底粉碎 Kotlin 编译陷阱！
                        @Suppress("DEPRECATION")
                        characteristic.setValue(huaweiPayload)
                        Log.e(TAG, "🔥 [调包成功] 降噪字节已被完美重写为华为原厂指令！")
                    }
                }
            )

        } catch (e: Throwable) {
            Log.e(TAG, "❌ 核心总线拦截引擎初始化失败", e)
        }
    }
}