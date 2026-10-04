package com.example.dynhost.ui;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.example.dynhost.DynHostApi;
import com.example.dynhost.R;
import com.example.dynhost.util.RootShell;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 宿主的管理界面。所有文件操作都通过 root shell 完成，因此不需要任何存储权限。
 *
 * 用法：
 *   1) 把插件 APK 丢到 /sdcard/DynModules/
 *   2) 打开本 App -> 点「同步投放目录」（APK 会被复制到 /data/adb/dynmodules/）
 *   3) 点「软重启」让 zygote 重新预读模块（只有新增/删除插件才需要）
 *   4) 之后开关 / 作用域随时改，保存后「强制停止」目标 App 即可生效
 */
public class MainActivity extends Activity {

    private static final String DIR = DynHostApi.MODULE_DIR;
    private static final String DROP = DynHostApi.DROP_DIR;

    private LinearLayout list;
    private TextView status;
    private final List<Row> rows = new ArrayList<>();

    static class Row {
        String id;
        String name;
        /** 非空表示宿主无法加载（新式 libxosed 模块等），仅作提示 */
        String unsupported;
        boolean enabled;
        String scope;
        CheckBox cb;
        EditText et;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        list = findViewById(R.id.list);
        status = findViewById(R.id.status);
        Button sync = findViewById(R.id.btn_sync);
        Button save = findViewById(R.id.btn_save);
        Button reboot = findViewById(R.id.btn_reboot);
        Button rescan = findViewById(R.id.btn_rescan);

        sync.setOnClickListener(v -> run(this::syncDropDir));
        save.setOnClickListener(v -> run(this::saveConfig));
        reboot.setOnClickListener(v -> {
            RootShell.exec("setprop ctl.restart zygote");
            Toast.makeText(this, "正在软重启…", Toast.LENGTH_SHORT).show();
        });
        rescan.setOnClickListener(v -> run(this::reload));

        run(this::reload);
    }

    private void run(Runnable r) {
        new Thread(() -> {
            if (!RootShell.available()) {
                post("未获得 root 权限");
                return;
            }
            r.run();
        }).start();
    }

    private void post(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    /* ---------------- 同步用户投放目录 ---------------- */

    private void syncDropDir() {
        RootShell.exec(new String[]{
                "mkdir -p " + DIR,
                "mkdir -p " + DROP,
                "chmod 0755 " + DIR,
                "chmod 0777 " + DROP,
                "ls " + DROP + "/*.apk 2>/dev/null"
        });
        List<String> files = RootShell.lines(RootShell.exec("ls " + DROP + "/*.apk 2>/dev/null"));
        int n = 0;
        for (String f : files) {
            String name = new java.io.File(f).getName();
            RootShell.exec("cp -f '" + f + "' " + DIR + "/" + name + " && chmod 0644 " + DIR + "/" + name);
            n++;
        }
        post("同步完成：" + n + " 个 APK -> " + DIR);
        reload();
    }

    /* ---------------- 读取模块列表 ---------------- */

    private void reload() {
        RootShell.exec("mkdir -p " + DIR + " && chmod 0755 " + DIR);
        List<String> ids = new ArrayList<>();
        for (String l : RootShell.lines(RootShell.exec("ls " + DIR + "/*.apk 2>/dev/null"))) {
            ids.add(new java.io.File(l.trim()).getName());
        }

        JSONObject cfg = readConfig();
        JSONObject ov = cfg != null ? cfg.optJSONObject("modules") : null;
        final List<Row> tmp = new ArrayList<>();

        for (String id : ids) {
            Row row = new Row();
            row.id = id;
            row.name = id;
            row.enabled = true;
            row.scope = "";

            // 只有 META-INF/xposed/java_init.list、没有 assets/xposed_init 的，
            // 是新式 libxposed 模块，宿主加载不了，在界面上明确标出来
            if (assetText(DIR + "/" + id, "META-INF/xposed/java_init.list") != null
                    && assetText(DIR + "/" + id, "assets/xposed_init") == null) {
                row.unsupported = "新式 libxposed 模块，DynHost 暂不支持";
            }

            String meta = assetText(DIR + "/" + id, "assets/dynmodule.json");
            if (meta != null) {
                try {
                    JSONObject o = new JSONObject(meta);
                    row.name = o.optString("name", id);
                    row.enabled = o.optBoolean("enabled", true);
                    JSONArray arr = o.optJSONArray("scope");
                    if (arr != null) {
                        List<String> s = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) s.add(String.valueOf(arr.get(i)));
                        row.scope = TextUtils.join(",", s);
                    }
                } catch (Throwable ignored) {}
            }
            if (ov != null && ov.has(id)) {
                JSONObject o = ov.optJSONObject(id);
                if (o != null) {
                    if (o.has("enabled")) row.enabled = o.optBoolean("enabled", row.enabled);
                    if (o.has("scope")) {
                        List<String> s = new ArrayList<>();
                        try {
                            JSONArray arr = o.optJSONArray("scope");
                            if (arr != null) for (int i = 0; i < arr.length(); i++) s.add(String.valueOf(arr.get(i)));
                        } catch (Exception ignored) {}
                        row.scope = TextUtils.join(",", s);
                    }
                }
            }
            tmp.add(row);
        }

        runOnUiThread(() -> {
            rows.clear();
            rows.addAll(tmp);
            render();
            status.setText("共 " + rows.size() + " 个模块（目录 " + DIR + "）");
        });
    }

    private void render() {
        list.removeAllViews();
        for (Row r : rows) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(8, 12, 8, 12);

            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            r.cb = new CheckBox(this);
            r.cb.setEnabled(r.unsupported == null);
            r.cb.setChecked(r.enabled && r.unsupported == null);
            r.cb.setText(r.name + "  (" + r.id + ")"
                    + (r.unsupported == null ? "" : "\n⚠ " + r.unsupported));
            line.addView(r.cb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button kill = new Button(this);
            kill.setText("强停");
            kill.setOnClickListener(v -> {
                String[] pkgs = r.et.getText().toString().split("[,\\s]+");
                StringBuilder sb = new StringBuilder();
                for (String p : pkgs) {
                    if (p.trim().isEmpty() || p.contains("*")) continue;
                    sb.append("am force-stop ").append(p.trim()).append("\n");
                }
                RootShell.exec(sb.toString());
                Toast.makeText(this, "已强制停止作用域内应用", Toast.LENGTH_SHORT).show();
            });
            line.addView(kill);
            box.addView(line);

            r.et = new EditText(this);
            r.et.setEnabled(r.unsupported == null);
            r.et.setHint("作用域包名，逗号分隔，如 com.tencent.mm,com.foo.*");
            r.et.setText(r.scope);
            box.addView(r.et);

            list.addView(box);
        }
    }

    /* ---------------- 保存 config.json ---------------- */

    private void saveConfig() {
        List<String> pkgsList = new ArrayList<>();
        JSONObject root = new JSONObject();
        JSONObject modules = new JSONObject();
        String payload;
        try {
            root.put("moduleDir", DIR);
            for (Row r : rows) {
                JSONObject o = new JSONObject();
                o.put("enabled", r.cb.isChecked());
                JSONArray arr = new JSONArray();
                for (String p : r.et.getText().toString().split("[,\\s]+")) {
                    String t = p.trim();
                    if (t.isEmpty()) continue;
                    arr.put(t);
                    if (!t.contains("*")) pkgsList.add(t);
                }
                o.put("scope", arr);
                modules.put(r.id, o);
            }
            root.put("modules", modules);
            payload = root.toString(2);
        } catch (Exception e) {
            payload = "{}";
        }

        RootShell.writeFile(DIR + "/config.json", payload);
        post("已保存 config.json（开关/作用域改动强停目标 App 即可生效；新增/删除 APK 需软重启）");
    }

    private JSONObject readConfig() {
        String s = RootShell.exec("cat " + DIR + "/config.json 2>/dev/null");
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return new JSONObject(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 root 目录里的 APK 中读出某个 assets 文件的文本（借助 su cat + ZipInputStream） */
    private String assetText(String apk, String entry) {
        byte[] data = RootShell.execBytes("cat '" + apk + "' 2>/dev/null");
        if (data == null || data.length == 0) return null;
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!entry.equals(e.getName())) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
                return new String(bos.toByteArray(), "UTF-8");
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
