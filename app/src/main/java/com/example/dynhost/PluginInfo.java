package com.example.dynhost;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 一个被投放到模块目录里的 APK 的描述信息 */
public final class PluginInfo {

    /** APK 文件名，同时作为插件 id，例如 "wechat-cleaner.apk" */
    public final String id;

    /** 显示名，取 dynmodule.json#name，缺省用文件名 */
    public String name;

    /** 是否启用（可被 config.json 覆盖） */
    public boolean enabled = true;

    /** 作用域包名列表，支持 "com.foo.*" 通配、"*" 全部、"!pkg" 排除 */
    public final List<String> scope = new ArrayList<>();

    /** 是否在 zygote 阶段就实例化并调用 initZygote */
    public boolean loadInZygote = false;

    /** assets/xposed_init 里声明的入口类 */
    public final List<String> entryClasses = new ArrayList<>();

    /** 预读进内存的 dex（classes.dex / classes2.dex ...） */
    public byte[][] dexBuffers = new byte[0][];

    /** 预读进内存的 assets（限 2MB 以内的文件） */
    public final Map<String, byte[]> assets = new HashMap<>();

    // 运行期状态（每个进程独立）
    ClassLoader loader;
    List<Object> instances;

    public PluginInfo(String id) {
        this.id = id;
        this.name = id;
    }

    @Override
    public String toString() {
        return name + " [" + id + "] enabled=" + enabled + " scope=" + scope;
    }
}
