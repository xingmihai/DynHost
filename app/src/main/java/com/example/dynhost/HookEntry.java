package com.example.dynhost;

import java.util.LinkedHashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 宿主模块入口。它本身是一个普通的 LSPosed 模块，
 * 只需要在 LSPosed 管理器里启用一次 + 勾选一次作用域（建议勾选 android），
 * 之后所有真正的 hook 逻辑都以"插件 APK"的形式从目录动态加载。
 */
public class HookEntry implements IXposedHookZygoteInit, IXposedHookLoadPackage,
        IXposedHookInitPackageResources {

    @Override
    public void initZygote(StartupParam startupParam) {
        XposedBridge.log("[DynHost] initZygote, sdk=" + android.os.Build.VERSION.SDK_INT);
        DynHostApi.setSink(new DynHostApi.Sink() {
            @Override public void log(String msg) {
                XposedBridge.log("[DynHost] " + msg);
            }
            @Override public byte[] asset(String pluginId, String name) {
                PluginInfo p = ModuleManager.getPlugin(pluginId);
                return p == null ? null : p.assets.get(name);
            }
            @Override public Map<String, String> plugins() {
                Map<String, String> m = new LinkedHashMap<>();
                for (Map.Entry<String, PluginInfo> e : ModuleManager.pluginsSnapshot().entrySet()) {
                    m.put(e.getKey(), e.getValue().name);
                }
                return m;
            }
        });
        ModuleManager.initZygote(startupParam);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        ModuleManager.onLoadPackage(lpparam);
    }

    @Override
    public void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam) {
        ModuleManager.onInitPackageResources(resparam);
    }
}
