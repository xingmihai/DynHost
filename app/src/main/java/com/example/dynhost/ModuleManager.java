package com.example.dynhost;

import android.os.Build;

import com.example.dynhost.libxposed.LibXposedHost;
import com.example.dynhost.libxposed.LibXposedParams;
import com.example.dynhost.util.ScopeMatcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.InMemoryDexClassLoader;
import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import io.github.libxposed.api.XposedModuleInterface;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 核心：扫描模块目录 -> 预读 dex/assets 进内存 -> 按作用域动态加载并转发回调。
 *
 * 关键设计：
 *   1. initZygote() 运行在 zygote 进程（uid 0），因此能读取任何 root-only 目录，
 *      把 dex 与 assets 一次性读成 byte[]，fork 后所有进程以 COW 方式共享，几乎不额外占内存。
 *   2. 真正的 ClassLoader 只在"命中作用域"时才创建（懒加载），
 *      使用 InMemoryDexClassLoader，绕开 app 进程对文件权限 / odex 目录的限制。
 *   3. 每个插件独立 try-catch + 独立 ClassLoader，一个插件崩溃不影响宿主与其他插件。
 */
public final class ModuleManager {

    private static final String TAG = "DynHost";
    private static final String INIT = "xposed_init";
    private static final String MODERN_INIT = "META-INF/xposed/java_init.list";
    private static final String MODERN_PROP = "META-INF/xposed/module.prop";
    private static final String META = "dynmodule.json";
    private static final String CONFIG = "config.json";
    private static final int MAX_ASSET = 2 * 1024 * 1024;

    private static final Map<String, PluginInfo> plugins = new LinkedHashMap<>();
    private static volatile boolean scanned = false;

    static String currentPackage = null;
    private static IXposedHookZygoteInit.StartupParam startupParam;

    private ModuleManager() {}

    /* ---------------- zygote 阶段 ---------------- */

    public static void initZygote(IXposedHookZygoteInit.StartupParam sp) {
        startupParam = sp;
        scan();
    }

    /** 重新扫描（例如收到刷新广播后调用，默认只在开机时做一次） */
    public static synchronized void scan() {
        plugins.clear();
        File dir = new File(DynHostApi.MODULE_DIR);
        File[] files = dir.listFiles();
        if (files == null) {
            XposedBridge.log("[DynHost] module dir not accessible: " + dir);
            return;
        }
        Arrays.sort(files); // 保证加载顺序稳定

        JSONObject cfg = readJson(new File(dir, CONFIG));
        JSONObject overrides = cfg != null ? cfg.optJSONObject("modules") : null;

        for (File f : files) {
            if (!f.isFile() || !f.getName().toLowerCase().endsWith(".apk")) continue;
            try {
                PluginInfo p = parse(f);
                if (p == null) continue;
                if (overrides != null) applyOverride(p, overrides.optJSONObject(p.id));
                if (!p.enabled) {
                    XposedBridge.log("[DynHost] skip disabled: " + p.id);
                    continue;
                }
                // 新式模块的实例必须每进程独立（要 attach 当前进程的 XposedInterface），
                // 不能在 zygote 阶段就创建
                if (p.loadInZygote && !p.modern) ensureInstances(p, ModuleManager.class.getClassLoader());
                plugins.put(p.id, p);
                XposedBridge.log("[DynHost] loaded " + p);
            } catch (Throwable t) {
                XposedBridge.log("[DynHost] failed to prepare " + f.getName());
                XposedBridge.log(t);
            }
        }
        scanned = true;
        XposedBridge.log("[DynHost] scan done, " + plugins.size() + " plugin(s)");
    }

    /** 读一个 zip 条目，按行拆出非空、非注释的内容 */
    private static void readEntryLines(ZipFile zip, ZipEntry entry, List<String> out)
            throws Exception {
        for (String line : new String(readAll(zip.getInputStream(entry)), "UTF-8").split("\n")) {
            String c = line.trim();
            if (!c.isEmpty() && !c.startsWith("#")) out.add(c);
        }
    }

    private static PluginInfo parse(File apk) throws Exception {
        PluginInfo p = new PluginInfo(apk.getName());
        try (ZipFile zip = new ZipFile(apk)) {
            // 1) 元信息
            ZipEntry meta = zip.getEntry("assets/" + META);
            if (meta != null) {
                JSONObject o = new JSONObject(new String(readAll(zip.getInputStream(meta)), "UTF-8"));
                p.name = o.optString("name", p.id);
                p.enabled = o.optBoolean("enabled", true);
                p.loadInZygote = o.optBoolean("loadInZygote", o.optBoolean("zygote", false));
                JSONArray arr = o.optJSONArray("scope");
                if (arr != null) {
                    try {
                        for (int i = 0; i < arr.length(); i++) p.scope.add(String.valueOf(arr.get(i)));
                    } catch (Exception ignored) {}
                }
            }
            // 2) 入口类
            ZipEntry init = zip.getEntry("assets/" + INIT);
            if (init != null) {
                readEntryLines(zip, init, p.entryClasses);
            } else {
                // 新式 libxposed 模块：入口声明在 META-INF/xposed/java_init.list，
                // 入口类 extends io.github.libxposed.api.XposedModule，
                // 由宿主实现 XposedInterface 并通过 attachFramework 注入。
                ZipEntry modern = zip.getEntry(MODERN_INIT);
                if (modern == null) {
                    XposedBridge.log("[DynHost] SKIP " + apk.getName() + ": 既没有 assets/"
                            + INIT + " 也没有 " + MODERN_INIT + "，已忽略");
                    return null;
                }
                p.modern = true;
                readEntryLines(zip, modern, p.entryClasses);

                ZipEntry prop = zip.getEntry(MODERN_PROP);
                if (prop != null) {
                    String txt = new String(readAll(zip.getInputStream(prop)), "UTF-8");
                    for (String line : txt.split("\n")) {
                        String t = line.trim();
                        if (t.startsWith("minApiVersion=")) {
                            XposedBridge.log("[DynHost] " + apk.getName()
                                    + " 是 libxposed 模块，minApiVersion=" + t.substring(14));
                            break;
                        }
                    }
                }
                // 没写 dynmodule.json 时，用模块自带的 scope.list / module.scope 作默认作用域
                if (p.scope.isEmpty()) {
                    ZipEntry scope = zip.getEntry("META-INF/xposed/scope.list");
                    if (scope == null) scope = zip.getEntry("META-INF/xposed/module.scope");
                    if (scope != null) {
                        List<String> tmp = new ArrayList<>();
                        readEntryLines(zip, scope, tmp);
                        p.scope.addAll(tmp);
                    }
                }
            }
            if (p.entryClasses.isEmpty()) return null;

            // 3) dex + assets 预读进内存
            List<byte[]> dexes = new ArrayList<>();
            for (int i = 1; ; i++) {
                String n = i == 1 ? "classes.dex" : "classes" + i + ".dex";
                ZipEntry e = zip.getEntry(n);
                if (e == null) break;
                dexes.add(readAll(zip.getInputStream(e)));
            }
            if (dexes.isEmpty()) return null;
            p.dexBuffers = dexes.toArray(new byte[0][]);

            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || !e.getName().startsWith("assets/")) continue;
                if (e.getSize() > MAX_ASSET) continue;
                String n = e.getName().substring("assets/".length());
                p.assets.put(n, readAll(zip.getInputStream(e)));
            }
        }
        return p;
    }

    private static void applyOverride(PluginInfo p, JSONObject o) {
        if (o == null) return;
        if (o.has("enabled")) p.enabled = o.optBoolean("enabled", p.enabled);
        if (o.has("name")) p.name = o.optString("name", p.name);
        JSONArray arr = o.optJSONArray("scope");
        if (arr != null) {
            p.scope.clear();
            try {
                for (int i = 0; i < arr.length(); i++) p.scope.add(String.valueOf(arr.get(i)));
            } catch (Exception ignored) {}
        }
    }

    /* ---------------- 转发回调 ---------------- */

    public static void onLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!scanned && plugins.isEmpty()) scan();
        String pkg = lpparam.packageName;
        currentPackage = pkg;

        if ("com.example.dynhost".equals(pkg)) return;      // 不注入宿主自身
        if ("android".equals(pkg) && !isSystemServer(lpparam)) { /* 仍允许 */ }

        for (PluginInfo p : new ArrayList<>(plugins.values())) {
            if (!ScopeMatcher.matches(p.scope, pkg)) continue;
            try {
                ensureInstances(p, ModuleManager.class.getClassLoader());
                LibXposedHost.sAppInfo = lpparam.appInfo;
                refreshContext();
                if (p.modern) {
                    // 新式 libxposed 模块：每进程一次 onModuleLoaded，随后 onPackageLoaded
                    Object loaded = LibXposedParams.moduleLoaded(
                            isSystemServer(lpparam), lpparam.processName);
                    Object pkgLoaded = LibXposedParams.packageLoaded(
                            pkg, safeAppInfo(), lpparam.isFirstApplication, lpparam.classLoader);
                    for (Object inst : p.instances) {
                        if (!p.moduleLoaded) {
                            LibXposedParams.invokeLifecycle(inst,
                                    XposedModuleInterface.ModuleLoadedParam.class,
                                    "onModuleLoaded", loaded);
                        }
                        LibXposedParams.invokeLifecycle(inst,
                                XposedModuleInterface.PackageLoadedParam.class,
                                "onPackageLoaded", pkgLoaded);
                    }
                    p.moduleLoaded = true;
                    continue;
                }
                for (Object inst : p.instances) {
                    if (inst instanceof IXposedHookLoadPackage) {
                        ((IXposedHookLoadPackage) inst).handleLoadPackage(lpparam);
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("[DynHost] plugin " + p.id + " error in " + pkg);
                XposedBridge.log(t);
            }
        }
    }

    private static android.content.pm.ApplicationInfo safeAppInfo() {
        return LibXposedHost.sAppInfo;
    }

    /** 取当前进程的 Application，供 getRemotePreferences 使用 */
    private static void refreshContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            LibXposedHost.sContext = (android.content.Context) app;
        } catch (Throwable ignored) {
            // 早期调用点还没有 Application，保持上一次的值即可
        }
    }

    public static void onInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam) {
        String pkg = resparam.packageName;
        for (PluginInfo p : new ArrayList<>(plugins.values())) {
            if (!ScopeMatcher.matches(p.scope, pkg)) continue;
            try {
                ensureInstances(p, ModuleManager.class.getClassLoader());
                for (Object inst : p.instances) {
                    if (inst instanceof IXposedHookInitPackageResources) {
                        ((IXposedHookInitPackageResources) inst).handleInitPackageResources(resparam);
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("[DynHost] plugin " + p.id + " resource hook error in " + pkg);
                XposedBridge.log(t);
            }
        }
    }

    private static boolean isSystemServer(XC_LoadPackage.LoadPackageParam lp) {
        return lp.processName != null && lp.processName.contains("system_server");
    }

    /* ---------------- 类加载 ---------------- */

    private static void ensureInstances(PluginInfo p, ClassLoader parent) throws Exception {
        if (p.instances != null) return;
        ClassLoader loader = createLoader(p, parent);
        List<Object> list = new ArrayList<>();
        for (String cls : p.entryClasses) {
            Class<?> c = Class.forName(cls, false, loader);
            if (p.modern) {
                // 新式模块：先 new 出来（构造函数里不能碰 API），
                // 再把宿主实现的 XposedInterface 注入进去
                Constructor<?> ctor = c.getDeclaredConstructor();
                ctor.setAccessible(true);
                Object inst = ctor.newInstance();
                LibXposedParams.attach(inst, new LibXposedHost(p.id), null);
                list.add(inst);
                continue;
            }
            Constructor<?> ctor = c.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object inst = ctor.newInstance();
            if (inst instanceof IXposedHookZygoteInit && startupParam != null) {
                try {
                    ((IXposedHookZygoteInit) inst).initZygote(startupParam);
                } catch (Throwable t) {
                    XposedBridge.log("[DynHost] initZygote failed for " + cls);
                    XposedBridge.log(t);
                }
            }
            list.add(inst);
        }
        p.loader = loader;
        p.instances = list;
    }

    private static ClassLoader createLoader(PluginInfo p, ClassLoader parent) throws Exception {
        if (p.loader != null) return p.loader;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ByteBuffer[] bufs = new ByteBuffer[p.dexBuffers.length];
            for (int i = 0; i < bufs.length; i++) bufs[i] = ByteBuffer.wrap(p.dexBuffers[i]);
            return new InMemoryDexClassLoader(bufs, parent);
        }
        // Android 7 及以下：落盘到当前进程的 cache 目录再加载
        File oat = new File(System.getProperty("java.io.tmpdir", "/data/local/tmp"), "dynhost-" + p.id);
        oat.mkdirs();
        List<String> outs = new ArrayList<>();
        for (int i = 0; i < p.dexBuffers.length; i++) {
            File f = new File(oat, (i == 0 ? "classes.dex" : "classes" + (i + 1) + ".dex"));
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            fos.write(p.dexBuffers[i]);
            fos.close();
            outs.add(f.getAbsolutePath());
        }
        return new dalvik.system.DexClassLoader(
                android.text.TextUtils.join(":", outs), oat.getAbsolutePath(), null, parent);
    }

    /* ---------------- 工具 ---------------- */

    public static PluginInfo getPlugin(String id) {
        return plugins.get(id);
    }

    public static Map<String, PluginInfo> pluginsSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(plugins));
    }

    private static JSONObject readJson(File f) {
        try (FileInputStream is = new FileInputStream(f)) {
            return new JSONObject(new String(readAll(is), "UTF-8"));
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }
}
