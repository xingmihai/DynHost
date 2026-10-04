package com.example.dynhost.libxposed;

import android.app.AppComponentFactory;
import android.content.pm.ApplicationInfo;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

/** 新式模块生命周期回调的参数实现 */
public final class LibXposedParams {

    private LibXposedParams() {}

    public static XposedModuleInterface.ModuleLoadedParam moduleLoaded(
            boolean systemServer, String processName) {
        final boolean ss = systemServer;
        final String pn = processName;
        return new XposedModuleInterface.ModuleLoadedParam() {
            @Override public boolean isSystemServer() { return ss; }
            @Override public String getProcessName() { return pn; }
        };
    }

    public static XposedModuleInterface.PackageLoadedParam packageLoaded(
            String packageName, ApplicationInfo appInfo, boolean firstPackage,
            ClassLoader defaultClassLoader) {
        final String pkg = packageName;
        final ApplicationInfo ai = appInfo;
        final boolean first = firstPackage;
        final ClassLoader cl = defaultClassLoader;
        return new XposedModuleInterface.PackageLoadedParam() {
            @Override public String getPackageName() { return pkg; }
            @Override public ApplicationInfo getApplicationInfo() { return ai; }
            @Override public boolean isFirstPackage() { return first; }
            @Override public ClassLoader getDefaultClassLoader() { return cl; }
        };
    }

    /** PackageReady 在宿主侧与 PackageLoaded 同一时机触发，额外给出真实 ClassLoader */
    public static XposedModuleInterface.PackageReadyParam packageReady(
            String packageName, ApplicationInfo appInfo, boolean firstPackage,
            ClassLoader defaultClassLoader) {
        final String pkg = packageName;
        final ApplicationInfo ai = appInfo;
        final boolean first = firstPackage;
        final ClassLoader cl = defaultClassLoader;
        return new XposedModuleInterface.PackageReadyParam() {
            @Override public String getPackageName() { return pkg; }
            @Override public ApplicationInfo getApplicationInfo() { return ai; }
            @Override public boolean isFirstPackage() { return first; }
            @Override public ClassLoader getDefaultClassLoader() { return cl; }
            @Override public ClassLoader getClassLoader() { return cl; }
            @Override public AppComponentFactory getAppComponentFactory() { return null; }
        };
    }

    public static XposedModuleInterface.SystemServerStartingParam systemServerStarting(
            ClassLoader classLoader) {
        final ClassLoader cl = classLoader;
        return new XposedModuleInterface.SystemServerStartingParam() {
            @Override public ClassLoader getClassLoader() { return cl; }
        };
    }

    /** 反射调用入口类的某个生命周期回调；没覆写时走接口 default 实现，不会出错 */
    public static void invokeLifecycle(Object moduleInstance, Class<?> paramInterface,
                                       String methodName, Object param) {
        try {
            java.lang.reflect.Method m =
                    moduleInstance.getClass().getMethod(methodName, paramInterface);
            m.setAccessible(true);
            m.invoke(moduleInstance, param);
        } catch (NoSuchMethodException ignored) {
            // 模块没覆写这个回调，忽略
        } catch (Throwable t) {
            de.robv.android.xposed.XposedBridge.log("[DynHost] " + methodName + " 调用失败");
            de.robv.android.xposed.XposedBridge.log(t);
        }
    }

    /** 框架注入：等价于 LSPosed 在实例化模块后调用的 attachFramework */
    public static void attach(Object moduleInstance, XposedInterface base, Runnable detach) {
        try {
            Class<?> c = moduleInstance.getClass();
            java.lang.reflect.Method m = null;
            while (c != null && m == null) {
                try {
                    m = c.getDeclaredMethod("attachFramework",
                            XposedInterface.class, Runnable.class);
                } catch (NoSuchMethodException e) {
                    c = c.getSuperclass();
                }
            }
            if (m == null) {
                throw new NoSuchMethodException("attachFramework");
            }
            m.setAccessible(true);
            m.invoke(moduleInstance, base, detach);
        } catch (Throwable t) {
            de.robv.android.xposed.XposedBridge.log("[DynHost] attachFramework 失败");
            de.robv.android.xposed.XposedBridge.log(t);
        }
    }
}
