package com.example.dynhost.util;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 root shell 封装。宿主 App 的所有文件操作都走 su，
 * 因此不需要任何存储权限，也不受分区存储限制。
 */
public final class RootShell {

    private static final String TAG = "DynHost";

    public static boolean available() {
        String out = exec("id");
        return out != null && out.contains("uid=0");
    }

    /** 执行一条 shell 命令（root），返回标准输出；失败返回 null */
    public static String exec(String cmd) {
        return exec(new String[]{cmd});
    }

    public static String exec(String[] cmds) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "sh"});
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            for (String c : cmds) os.writeBytes(c + "\n");
            os.writeBytes("exit\n");
            os.flush();
            InputStream is = p.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            Log.e(TAG, "root exec failed", t);
            return null;
        }
    }

    /** 执行并返回原始字节（用于把 APK 通过 stdout 读回内存解析） */
    public static byte[] execBytes(String cmd) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            InputStream is = p.getInputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            p.waitFor();
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.e(TAG, "root execBytes failed", t);
            return null;
        }
    }

    /** 以 root 身份把内容写入文件（通过 shell stdin） */
    public static boolean writeFile(String path, String content) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "sh"});
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes("cat > '" + path + "' <<'DYNHOST_EOF'\n");
            os.write(content.getBytes("UTF-8"));
            os.writeBytes("\nDYNHOST_EOF\n");
            os.writeBytes("chmod 0644 '" + path + "'\n");
            os.writeBytes("exit\n");
            os.flush();
            p.waitFor();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "writeFile failed", t);
            return false;
        }
    }

    /** 按行拆分并去掉空行 */
    public static List<String> lines(String s) {
        List<String> r = new ArrayList<>();
        if (s == null) return r;
        for (String l : s.split("\n")) {
            String t = l.trim();
            if (!t.isEmpty()) r.add(t);
        }
        return r;
    }
}
