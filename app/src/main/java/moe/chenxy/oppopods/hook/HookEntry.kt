package moe.chenxy.oppopods.hook

import android.os.Build
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class HookEntry : XposedModule() {
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (!param.isFirstPackage) return

        when (param.packageName) {
            "com.android.bluetooth" -> loadHook(HeadsetStateDispatcher, param.defaultClassLoader)
            "com.milink.service" -> loadHook(MiLinkServiceHook, param.defaultClassLoader)
            "com.xiaomi.bluetooth" -> loadHook(MiBluetoothToastHook, param.defaultClassLoader)
            "com.android.settings" -> loadHook(SettingsHeadsetHook, param.defaultClassLoader)
        }
    }

    private fun loadHook(hook: HookContext, classLoader: ClassLoader) {
        hook.module = this
        hook.appClassLoader = classLoader
        hook.prefs = getRemotePreferences("oppopods_settings")
        hook.onHook()
    }
}
