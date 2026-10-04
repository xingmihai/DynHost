package com.example.plugin.sample;

import android.app.Application;
import android.content.Context;
import android.widget.Toast;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.example.dynhost.DynHostApi;

/** 一个普通 Xposed 模块，写法与在 LSPosed 里独立使用时完全一致。 */
public class SampleEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 只有 scope 命中的包才会被 DynHost 调用到这里
        if (lpparam.packageName.equals("android") && !lpparam.processName.equals("android")) return;

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context ctx = (Context) param.thisObject;
                DynHostApi.log("[sample] injected into " + ctx.getPackageName());
                Toast.makeText(ctx, "DynHost 插件已生效：" + ctx.getPackageName(), Toast.LENGTH_SHORT).show();
            }
        });
    }
}
