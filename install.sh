#!/system/bin/sh
# 在 root 终端（或 Magisk/KernelSU 的 root shell）里执行：
#   sh install.sh
set -e
APK="$1"
[ -z "$APK" ] && APK="/sdcard/DynHost.apk"

echo "[*] 安装宿主 APK: $APK"
pm install -r --user 0 "$APK" || pm install -r "$APK"

echo "[*] 准备模块目录"
mkdir -p /data/adb/dynmodules
chmod 0755 /data/adb/dynmodules
mkdir -p /sdcard/DynModules
chmod 0777 /sdcard/DynModules

echo "[*] 完成。请到 LSPosed 管理器启用 DynHost 模块，并勾选作用域（建议至少勾 android）。"
echo "[*] 然后把插件 APK 放进 /sdcard/DynModules/，打开 DynHost -> 同步投放目录 -> 软重启。"
