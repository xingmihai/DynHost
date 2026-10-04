package com.example.dynhost;

import java.util.Collections;
import java.util.Map;

/**
 * 插件（投放到模块目录的 APK）可使用的宿主 API。
 *
 * 插件工程用 compileOnly project(':api') 依赖本模块即可；
 * 运行期由宿主的 ClassLoader 提供真实实现（宿主会在 zygote 阶段注入 Sink）。
 */
public final class DynHostApi {

    /** 实际被加载的模块目录（root 私有） */
    public static final String MODULE_DIR = "/data/adb/dynmodules";

    /** 用户便于投放 APK 的目录，宿主 App 会同步到这里 */
    public static final String DROP_DIR = "/sdcard/DynModules";

    /** 宿主持有的真实实现，由 HookEntry#initZygote 注入 */
    public interface Sink {
        void log(String msg);
        byte[] asset(String pluginId, String name);
        Map<String, String> plugins();
    }

    private static volatile Sink sink;

    private DynHostApi() {}

    public static void setSink(Sink s) {
        sink = s;
    }

    /** 打日志，tag 为 DynHost/Plugin */
    public static void log(String msg) {
        Sink s = sink;
        if (s != null) s.log(msg);
    }

    /** 读取插件自身 APK 内 assets/<name> 的内容（宿主在 zygote 阶段已缓存进内存） */
    public static byte[] getPluginAsset(String pluginId, String name) {
        Sink s = sink;
        return s == null ? null : s.asset(pluginId, name);
    }

    /** 当前已加载插件：id -> 显示名 */
    public static Map<String, String> allPlugins() {
        Sink s = sink;
        return s == null ? Collections.emptyMap() : s.plugins();
    }
}
