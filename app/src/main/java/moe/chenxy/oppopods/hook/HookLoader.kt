package moe.chenxy.oppopods.hook

import android.bluetooth.BluetoothDevice
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
        // 🎯 斩首行动：只注入红米系统的核心蓝牙进程，彻底脱离所有第三方类和App依赖
        if (lpparam.packageName != "com.android.bluetooth") return

        Log.e(TAG, "⚡ [中央总线已接管] 成功强行并网 HyperOS 系统蓝牙堆栈！")

        try {
            // ========================================================
            // 🚀 正向流量拦截：把华为耳机欺骗改写为小米原生白名单设备
            // ========================================================
            XposedHelpers.findAndHookMethod(
                "com.android.bluetooth.btservice.RemoteDevices", 
                lpparam.classLoader,
                "getDeviceType", // 系统/设备互联在挂载卡片前索要设备类型的核心方法
                BluetoothDevice::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val device = param.args[0] as? BluetoothDevice ?: return
                        if (device.address == TARGET_MAC) {
                            // 强行把华为外设属性擦除，在内存里重写为小米原生智能音频耳机的识别码 2！
                            param.result = 2 
                            Log.e(TAG, "🧬 [伪装成功] 成功在总线入口将华为耳机改写为小米原生音频设备(Type=2)")
                        }
                    }
                }
            )

            // ========================================================
            // 🚀 正向无感广播：原地拦截特征值，用纯原生字节操作，彻底杜绝编译引用缺失！
            // ========================================================
            XposedHelpers.findAndHookMethod(
                "com.android.bluetooth.gatt.GattService", 
                lpparam.classLoader,
                "onCharacteristicChanged", // 华为耳机高频通过 BLE 吐电量的大闸口
                String::class.java, 
                Int::class.java,    
                ByteArray::class.java, // 原始变长字节流（5A 00 A0...）
                object : XC_MethodHook() {
                    private var cachedLeft = -1
                    private var cachedRight = -1
                    private var cachedCase = -1
                    private var cachedAnc = -1

                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val mac = param.args[0] as? String ?: return
                        if (mac != TARGET_MAC) return

                        val rawData = param.args[2] as? ByteArray ?: return
                        if (rawData.size < 15 || rawData[0] != 0x5A.toByte()) return

                        // 🎯 降维打击：直接用纯原生底层字节切片提取电量，不调用任何未定义的外部解析类
                        val left = rawData[12].toInt() and 0xFF
                        val right = rawData[13].toInt() and 0xFF
                        val case = rawData[14].toInt() and 0xFF
                        val ancByte = rawData[15].toInt() and 0xFF

                        // 过滤未佩戴干扰信号
                        if (left > 100 && left != 127) return 

                        // 状态对比节流阀：如果电量和降噪没有发生翻转改变，直接拦截，绝不向 Binder 发送冗余信息！
                        if (left == cachedLeft && right == cachedRight && case == cachedCase && ancByte == cachedAnc) {
                            return
                        }

                        cachedLeft = left
                        cachedRight = right
                        cachedCase = case
                        cachedAnc = ancByte

                        // 映射小米原生的降噪模式值（1为关闭，2为降噪，3为通透）
                        val xiaomiAncMode = if (ancByte == 2) 2 else if (ancByte == 3) 3 else 1
                        
                        Log.e(TAG, "🔋 [总线捞取成功] 放行干净状态 -> 左: $left, 右: $right, 仓: $case, 降噪: $xiaomiAncMode")

                        // 动态拿当前系统蓝牙进程的 Context，安全级极高
                        val context = XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                            "currentApplication"
                        ) as? Context ?: return

                        // 🚨 发送高仿小米原生 Milink 插件（world.headset）的并网广播，两路合一，彻底断绝 Binder 压力！
                        val intent = Intent("com.xiaomi.milink.action.DEVICE_STATUS_CHANGED").apply {
                            putExtra("device_address", TARGET_MAC)
                            putExtra("device_type", 2) // 强注册为小米原生智能耳机
                            putExtra("left_battery", if (left == 127) 0 else left)
                            putExtra("right_battery", if (right == 127) 0 else right)
                            putExtra("case_battery", if (case == 127) 0 else case)
                            putExtra("anc_mode", xiaomiAncMode)
                            putExtra("is_connected", true)
                            setPackage("com.milink.service") // 直接注入设备互联迈凌中枢
                        }
                        context.sendBroadcast(intent)
                    }
                }
            )

            // ========================================================
            // 🚀 反向控制链调包：拦截迈凌控制面板的下发动作，在出闸前原地调包
            // ========================================================
            XposedHelpers.findAndHookMethod(
                "com.android.bluetooth.gatt.GattService",
                lpparam.classLoader,
                "writeCharacteristic", // 当你在迈凌面板点切换降噪时，系统最终会通过此方法下发特征值
                String::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java,
                ByteArray::class.java, // 小米的原生指令包
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val mac = param.args[0] as? String ?: return
                        if (mac != TARGET_MAC) return

                        val xiaomiCmd = param.args[5] as? ByteArray ?: return
                        if (xiaomiCmd.isEmpty()) return
                        
                        Log.e(TAG, "🎯 [总线拦截] 逮到小米设备互联面板的降噪手势控制，准备原地执行华为调包...")
                        
                        // 拦截小米的控制指令（1:关闭, 2:降噪, 3:通透），在射向空气前原地重写为华为原厂物理字节
                        val targetMode = xiaomiCmd[0].toInt() 
                        val huaweiPayload = when (targetMode) {
                            2 -> byteArrayOf(0x5A, 0x01, 0x02) // 降噪
                            3 -> byteArrayOf(0x5A, 0x01, 0x03) // 通透
                            else -> byteArrayOf(0x5A, 0x01, 0x01) // 关闭
                        }
                        
                        // 内存替换：让底层蓝牙栈误以为系统发出的就是华为原厂控制指令！
                        param.args[5] = huaweiPayload
                        Log.e(TAG, "🔥 [调包成功] 降噪字节已被完美重写为华为原厂指令，射向硬件！")
                    }
                }
            )

        } catch (e: Throwable) {
            Log.e(TAG, "❌ 核心总线拦截引擎初始化失败", e)
        }
    }
}