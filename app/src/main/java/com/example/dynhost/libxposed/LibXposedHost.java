package com.example.dynhost.libxposed;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.error.HookFailedError;

/**
 * DynHost 自己实现的 libxposed XposedInterface。
 *
 * 新式模块（extends io.github.libxposed.api.XposedModule）的 hook() 会委托到
 * 这里注入的实例，本类把这些调用翻译成 XposedBridge（LSPosed 旧 API）的操作：
 *
 *   hook(m).intercept(hooker)  ->  XposedBridge.hookMethod(m, XC_MethodHook)
 *   chain.proceed()            ->  XposedBridge.invokeOriginalMethod(...)
 *
 * 由于 XposedBridge 的回调是 before/after 两段式，而 libxposed 是单个可嵌套的
 * Chain，这里统一在 beforeHookedMethod 里执行 hooker.intercept(chain) 并把返回值
 * setResult —— 这样 "proceed 后改结果" 和 "不 proceed 直接替换" 两种写法都成立。
 */
public class LibXposedHost implements XposedInterface {

    /** 由 ModuleManager 在每次 handleLoadPackage 时填入当前进程的信息 */
    public static volatile android.content.pm.ApplicationInfo sAppInfo;
    public static volatile android.content.Context sContext;

    private final String pluginId;

    public LibXposedHost(String pluginId) {
        this.pluginId = pluginId;
    }

    /* ---------------- 框架信息 ---------------- */

    @Override public int getApiVersion() { return API_102; }
    @Override public String getFrameworkName() { return "DynHost"; }
    @Override public String getFrameworkVersion() { return "1.0"; }
    @Override public long getFrameworkVersionCode() { return 1; }
    @Override public long getFrameworkProperties() { return 0; }

    /* ---------------- hook ---------------- */

    @Override
    public HookBuilder hook(Executable executable) {
        return new HookBuilderImpl(executable);
    }

    @Override
    public HookBuilder hookClassInitializer(Class<?> clazz) {
        // XposedBridge 没有对应的公开 API，只能在真正 intercept 时报错
        return new HookBuilderImpl(null) {
            @Override
            public HookHandle intercept(Hooker hooker) {
                throw new HookFailedError("DynHost: hookClassInitializer 暂不支持（" + clazz + "）");
            }
        };
    }

    @Override
    public boolean deoptimize(Executable executable) {
        return false; // XposedBridge 未暴露 deoptimize
    }

    /* ---------------- invoker ---------------- */

    @Override
    public Invoker<?, Method> getInvoker(Method method) {
        return new MethodInvoker(method);
    }

    @Override
    public <T> CtorInvoker<T> getInvoker(Constructor<T> ctor) {
        return new CtorInvokerImpl<>(ctor);
    }

    /* ---------------- 日志 ---------------- */

    @Override
    public void log(int level, String tag, String message) {
        XposedBridge.log("[DynHost/" + pluginId + "] " + tag + ": " + message);
    }

    @Override
    public void log(int level, String tag, String message, Throwable t) {
        XposedBridge.log("[DynHost/" + pluginId + "] " + tag + ": " + message);
        XposedBridge.log(t);
    }

    /* ---------------- 远程资源 ---------------- */

    @Override
    public ApplicationInfo getModuleApplicationInfo() {
        return sAppInfo;
    }

    @Override
    public SharedPreferences getRemotePreferences(String name) {
        android.content.Context ctx = sContext;
        if (ctx == null) return null;
        try {
            return ctx.getSharedPreferences("dynhost_" + pluginId + "_" + name, 0);
        } catch (Throwable t) {
            XposedBridge.log("[DynHost/" + pluginId + "] getRemotePreferences failed");
            XposedBridge.log(t);
            return null;
        }
    }

    @Override
    public String[] listRemoteFiles() {
        return new String[0];
    }

    @Override
    public ParcelFileDescriptor openRemoteFile(String name) throws FileNotFoundException {
        throw new FileNotFoundException("DynHost 不提供 openRemoteFile: " + name);
    }

    /* ---------------- 工具 ---------------- */

    static Object invokeOriginal(Member m, Object thisObject, Object[] args) throws Throwable {
        try {
            return XposedBridge.invokeOriginalMethod(m, thisObject, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause != null ? cause : e;
        }
    }

    /** 返回类型是原始类型时，null 结果会让 ART 崩，这里补默认值 */
    static Object fixResult(Class<?> returnType, Object result) {
        if (result != null || returnType == null || !returnType.isPrimitive()) return result;
        if (returnType == void.class) return null;
        if (returnType == boolean.class) return Boolean.FALSE;
        if (returnType == char.class) return (char) 0;
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0f;
        if (returnType == double.class) return 0d;
        return result;
    }

    static Class<?> returnTypeOf(Member m) {
        if (m instanceof Method) return ((Method) m).getReturnType();
        if (m instanceof Constructor) return ((Constructor<?>) m).getDeclaringClass();
        return null;
    }

    /* ---------------- HookBuilder ---------------- */

    class HookBuilderImpl implements HookBuilder {
        final Executable executable;
        int priority = PRIORITY_DEFAULT;
        ExceptionMode mode = ExceptionMode.DEFAULT;
        String id;

        HookBuilderImpl(Executable executable) { this.executable = executable; }

        @Override public HookBuilder setPriority(int priority) { this.priority = priority; return this; }
        @Override public HookBuilder setExceptionMode(ExceptionMode mode) { this.mode = mode; return this; }
        @Override public HookBuilder setId(String id) { this.id = id; return this; }

        @Override
        public HookHandle intercept(Hooker hooker) {
            if (executable == null) throw new HookFailedError("DynHost: 无法 hook 的目标");
            final ExceptionMode em = mode;
            XC_MethodHook hook = new XC_MethodHook(priority) {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    ChainImpl chain = new ChainImpl(param);
                    try {
                        Object result = hooker.intercept(chain);
                        param.setResult(fixResult(returnTypeOf(param.method), result));
                    } catch (Throwable t) {
                        if (em == ExceptionMode.PASSTHROUGH) {
                            param.setThrowable(t);
                        } else {
                            XposedBridge.log("[DynHost/" + pluginId + "] hooker 抛出异常（已按 "
                                    + em + " 吞掉）");
                            XposedBridge.log(t);
                        }
                    }
                }
            };
            XC_MethodHook.Unhook unhook;
            if (executable instanceof Method) {
                unhook = XposedBridge.hookMethod((Method) executable, hook);
            } else {
                unhook = XposedBridge.hookMethod((Constructor<?>) executable, hook);
            }
            return new HookHandleImpl(executable, id, hooker, em, unhook);
        }
    }

    /* ---------------- HookHandle ---------------- */

    class HookHandleImpl implements HookHandle {
        private final Executable executable;
        private final String id;
        private final Hooker hooker;
        private final ExceptionMode mode;
        private XC_MethodHook.Unhook unhook;

        HookHandleImpl(Executable executable, String id, Hooker hooker,
                       ExceptionMode mode, XC_MethodHook.Unhook unhook) {
            this.executable = executable;
            this.id = id;
            this.hooker = hooker;
            this.mode = mode;
            this.unhook = unhook;
        }

        @Override public Executable getExecutable() { return executable; }
        @Override public String getId() { return id; }
        @Override public void unhook() {
            if (unhook != null) { unhook.unhook(); unhook = null; }
        }
        @Override
        public HookHandle replaceHook(Hooker newHooker) {
            unhook();
            HookBuilderImpl b = new HookBuilderImpl(executable);
            b.setExceptionMode(mode);
            b.setId(id);
            return b.intercept(newHooker);
        }
    }

    /* ---------------- Chain ---------------- */

    static class ChainImpl implements Chain {
        private final XC_MethodHook.MethodHookParam param;

        ChainImpl(XC_MethodHook.MethodHookParam param) { this.param = param; }

        @Override public Executable getExecutable() { return (Executable) param.method; }
        @Override public Object getThisObject() { return param.thisObject; }
        @Override public List<Object> getArgs() { return Arrays.asList(param.args); }
        @Override public Object getArg(int index) { return param.args[index]; }

        @Override public Object proceed() throws Throwable {
            return invokeOriginal(param.method, param.thisObject, param.args);
        }

        @Override public Object proceed(Object[] args) throws Throwable {
            if (args != null) param.args = args;
            return invokeOriginal(param.method, param.thisObject, param.args);
        }

        @Override public Object proceedWith(Object thisObject) throws Throwable {
            return invokeOriginal(param.method, thisObject, param.args);
        }

        @Override public Object proceedWith(Object thisObject, Object[] args) throws Throwable {
            return invokeOriginal(param.method, thisObject, args);
        }
    }

    /* ---------------- Invoker ---------------- */

    static class MethodInvoker implements Invoker<MethodInvoker, Method> {
        private final Method method;

        MethodInvoker(Method method) { this.method = method; }

        @Override public MethodInvoker setType(Type type) { return this; }

        @Override
        public Object invoke(Object thisObject, Object... args)
                throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            try {
                return invokeOriginal(method, thisObject, args);
            } catch (InvocationTargetException | IllegalAccessException | IllegalArgumentException e) {
                throw e;
            } catch (Throwable t) {
                throw new InvocationTargetException(t);
            }
        }

        @Override
        public Object invokeSpecial(Object thisObject, Object... args)
                throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            return invoke(thisObject, args);
        }
    }

    static class CtorInvokerImpl<T> implements CtorInvoker<T> {
        private final Constructor<T> ctor;

        CtorInvokerImpl(Constructor<T> ctor) { this.ctor = ctor; }

        @Override public CtorInvokerImpl<T> setType(Type type) { return this; }

        @Override
        public Object invoke(Object thisObject, Object... args)
                throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            return newInstance(args);
        }

        @Override
        public Object invokeSpecial(Object thisObject, Object... args)
                throws InvocationTargetException, IllegalArgumentException, IllegalAccessException {
            return newInstance(args);
        }

        @Override
        public T newInstance(Object... args)
                throws InvocationTargetException, IllegalArgumentException,
                IllegalAccessException, InstantiationException {
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        }

        @Override
        public <U> U newInstanceSpecial(Class<U> clazz, Object... args)
                throws InvocationTargetException, IllegalArgumentException,
                IllegalAccessException, InstantiationException {
            ctor.setAccessible(true);
            return clazz.cast(ctor.newInstance(args));
        }
    }
}
