# DynHost —— 免安装的 LSPosed 动态模块宿主

把 Xposed 模块的 **APK 直接丢进一个目录**，就能在 LSPosed 里被加载并指定作用域，
**不需要再走 `pm install`、不需要在 LSPosed 管理器里逐个勾选模块和作用域**。

---

## 1. 它解决什么问题

正常使用 LSPosed 时，启用一个新模块要四步：安装 APK → 管理器里启用 → 勾选作用域 → 重启/强停。
DynHost 把这四步压缩成一步：

1. 把插件 APK 复制到 `/sdcard/DynModules/`（或 root 复制到 `/data/adb/dynmodules/`）；
2. 打开 DynHost → 「同步投放目录」→「软重启」。

此后插件的 **开关** 与 **作用域** 在 DynHost 界面里随时改，保存后强停目标 App 即可生效，**不再需要重启**。

---

## 2. 目录与文件

| 路径 | 作用 |
| --- | --- |
| `/data/adb/dynmodules/*.apk` | 实际被加载的插件（root 私有，其他 App 读不到） |
| `/data/adb/dynmodules/config.json` | 覆盖每个插件的 `enabled` / `scope`（DynHost UI 生成） |
| `/sdcard/DynModules/*.apk` | 用户投放区，用文件管理器就能扔 APK 进来 |

## 3. 插件 APK 的写法

插件就是一个**完全标准的 Xposed 模块**，不需要改任何写法，只需在 assets 里多放两个文件：

### `assets/xposed_init`
```
com.example.plugin.sample.SampleEntry
```
（与官方写法一致，声明入口类；可多行）

### `assets/dynmodule.json`（可选但强烈建议）
```json
{
  "id": "sample.apk",
  "name": "示例插件（进入 App 弹 Toast）",
  "enabled": true,
  "loadInZygote": false,
  "scope": ["com.android.settings", "com.tencent.mm", "com.foo.*", "!com.tencent.mm:push"]
}
```

### 作用域语法
| 写法 | 含义 |
| --- | --- |
| `com.tencent.mm` | 精确命中该包名 |
| `com.foo.*` | 命中该前缀下的所有包名 |
| `*` | 命中所有 App |
| `!pkg` | 排除（优先级最高） |

不写 `dynmodule.json` 也可以：此时插件默认是**启用**的，作用域为 `*`（全局生效），入口类仍从 `xposed_init` 读取。

### 插件可用 API
```java
DynHostApi.log("hello");                 // 打日志，tag = DynHost/Plugin
byte[] dex = DynHostApi.getPluginAsset("assets/xxx.so"); // 读自己 APK 里的 assets
```
`plugin-sample/` 是一个可以直接编译的完整示例（进入作用域 App 时弹 Toast）。

---

## 4. 工作过程

1. **zygote 阶段**（`initZygote`，uid 0）：预读 `/data/adb/dynmodules/*.apk`，把 dex 与 assets 读进内存；
2. **`handleLoadPackage`**：用当前包名匹配各插件的 `scope`，命中才加载；
3. **懒加载**：Android 8+ 用 `InMemoryDexClassLoader` 直接加载内存中的 dex（不落盘、不解压）；
   低版本回退到 `java.io.tmpdir` 下的临时 dex + `DexClassLoader`（用完即删）；
4. **回调转发**：把 `IXposedHookLoadPackage` / `IXposedHookInitPackageResources` 转发给插件；
   `loadInZygote = true` 的插件会在 zygote 阶段就初始化（如需要 hook `android`）。

因为 dex 在 zygote 里一次性读入内存，每个 App 进程不再重复读盘、也不各自解压，这是选择「内存加载」而不是「解压到私有目录」的原因。

---

## 5. 安装与使用

```sh
# 1. 编译（本地）
./gradlew :app:assembleDebug :plugin-sample:assembleDebug
```

或者不装 Android SDK：打开仓库 → **Actions → Build APK → Run workflow**，
选 `debug` / `release`，跑完在 Artifacts 里下载 `DynHost-debug`（该 workflow 只手动触发，不会自动跑）。
Release 产物是**未签名**的，需自行 `zipalign` + `apksigner`。

# 2. 安装（root shell）
sh install.sh app/build/outputs/apk/debug/app-debug.apk

# 3. 在 LSPosed 管理器里启用 DynHost，勾选作用域（建议勾 android）
#    注意：这是宿主唯一一次需要你在管理器里操作的地方

# 4. 投放插件
cp plugin-sample/build/outputs/apk/debug/plugin-sample-debug.apk /sdcard/DynModules/sample.apk

# 5. 打开 DynHost -> 同步投放目录 -> 软重启(加载新插件)
```

之后：改开关 / 改作用域 → 「保存配置」→「强停」目标 App → 立即生效。

## 6. 已知限制

- **宿主自身**需要在 LSPosed 管理器里启用并勾选作用域一次（Android 8+ 勾 `android` 即可覆盖全部进程）；宿主只 hook 自己作用域内的进程，不会扩大 hook 面。
- **新增 / 删除 / 更新插件 APK** 需要软重启 zygote（`setprop ctl.restart zygote`），因为 dex 是在 zygote 启动时预读的；单纯改开关和作用域不用。
- 插件里如果 hook 了 `android` 包本身，需要在 `dynmodule.json` 里把 `loadInZygote` 设为 `true`。
- `minSdk 26`：`InMemoryDexClassLoader` 从 Android 8 才可用；更低版本会走落盘临时 dex 的回退路径（代码已实现，但建议直接以 26 为下限）。
- 这是自定义加载器，**仅用于自有模块与调试**；用第三方 APK 当插件等同于把它的代码注入你勾选的所有 App，请只放可信 APK。

## 7. 目录结构

```
DynHost/
├── api/                 插件可用的宿主 API（宿主 implementation，插件 compileOnly）
├── app/                 宿主模块（LSPosed 里唯一需要勾选的模块）
│   └── .../dynhost/{HookEntry, ModuleManager, PluginInfo, ui/MainActivity, util/*}
├── .github/workflows/build.yml   手动触发的 APK 构建工作流
├── plugin-sample/       可直接编译的示例插件
├── install.sh           安装 + 建目录
└── README.md
```
