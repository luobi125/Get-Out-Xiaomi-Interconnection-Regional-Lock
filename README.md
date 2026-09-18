# Get-Out-Xiaomi-Interconnection-Regional-Lock

解除**小米互联对「电脑」的跨区域限制**。

小米互联有一条「设备必须同区域(region)」的判定。电脑上的互联组件若不是国内发行版，
会自报 `us` / `global` 之类的区域值，平板把它判为「不同区域」后**静默丢弃**，于是：

- 电脑连不上平板：平板侧 `accept=3`，电脑侧 `err_code=15015 user reject`
- 下拉控制中心「融合设备中心」里看不到电脑
- 跨设备剪贴板不可用
- 共享通道建不起来 → 读不到电脑电量
- 投屏 / 协同等与这台电脑相关的功能全部不可用

**v1.0：只有两个 hook，不硬编码任何设备 ID / 型号 / 品牌，只对电脑生效。**

---

## 根因

区域值来自设备属性 `PropBuildRegion`：

| 端 | 来源 |
|---|---|
| Android | `ro.miui.build.region`（如 `cn`） |
| 电脑 | 注册表 `HKCU\Control Panel\International\Geo\Name` |

判定代码在 `LyraServiceManager`：

```java
// p2.K$a —— onServiceOnline
public void d(BusinessServiceInfo bsi, TrustedDeviceInfo tdi) {
    if (K.this.f17771i.b(bsi, tdi)) return;   // ← 不同区域：直接 return，后面什么都不做
    ...                                        // 设备表登记、通道协商、设备中心注册全在这里之后
}

// p2.K$e 默认实现 → p2.K.D() → p2.K.F()
public static boolean F(String deviceId, boolean needConvert) {
    String remote = A(deviceId);              // 远端设备属性 PropBuildRegion
    String local  = x();                      // ro.miui.build.region
    return TextUtils.equals(local, remote);
}
```

实测（平板 `cn`）：

| 设备 | `PropBuildRegion` | `p2.K.D()` | 结果 |
|---|---|---|---|
| 手机 `2880C81E` | `cn` | `true` | 正常放行 |
| 电脑 `E3DF9D08` | `us` | `false` | 被丢弃 |

### 为什么手机不受影响 —— 是**版本**差异，不是形态差异

手机 `com.xiaomi.mirror` = **17.01.30**，平板 = **18.01.07**。两边区域判断的代码结构完全一样，
差别在**谁覆盖了它、豁免哪些设备类型**（`d`/`c` 两个入口）：

| 版本 | 类 | 区域豁免的设备类型 | 含电脑(4)? |
|---|---|---|---|
| 17.01.30（手机） | `O2.a$b` | `i()` = `{21, 4}` | **✅** |
| 17.01.30（手机） | `R2.a$b` | `j()`={11..17} ∪ `n()`=`{21, 4}` | **✅** |
| 18.01.07（平板） | `P2.a$b` | **无覆盖** → 所有类型都判定 | ❌ |
| 18.01.07（平板） | `S2.a$b` | `j()`={11..17} ∪ `k()`=`{21}` | ❌ |

```java
// 17.x（手机）
public static boolean i(int i7) { return i7 == 21 || i7 == 4; }
// 18.x（平板），同一位置变成
public static boolean k(int i7) { return i7 == 21; }        // ← 4 被去掉
```

也就是说 **18.x 收紧了这个限制**。一台升级到 18.x 的手机同样会连不上非国行电脑。

### 被丢弃之后，「全都用不了」

区域判断在**最前面**，一旦返回，下面这些统统不会发生：

```
p2.E$b.d()   DeviceManager.onServiceOnline          → 设备表里没有电脑
  └─ p2.E$a.onChannelConfirm → E.n(deviceId) 等 5 秒 → null
       → q2.q.m(ctx).f(channelId, 3, null)          // accept=3 = 拒绝

O2.d$b.d()   CastBusinessWrapper.onServiceOnline
  └─ y3.z.h(tdi,true) → y3.z.i(tdi,true)            // LyraUtils 上报在线
       └─ q4.i.i(tdi) → q4.i.c(id,tdi)              // DeviceCenterUtils
            └─ ContentProvider content://com.milink.service.device
                 └─ com.milink.service 的 cache_device 库（device / export 表）
                      └─ SystemUI 插件 miui.systemui.plugin 读 /export
                           → 渲染下拉控制中心的「融合设备中心」卡片
```

实测对照：

```
关掉模块：  Trusted.ONLINE(cast, E3DF9D08) → Lyra.ONLINE → ✗ 到此为止
            cache_device.device  只有 2880C81E(手机)

打开模块：  Trusted.ONLINE(cast, E3DF9D08) → Lyra.ONLINE → DevMgr.ONLINE → Cast.ONLINE ✓
            cache_device.device  E3DF9D08 | Windows | 罗比
            cache_device.export  2880C81E, E3DF9D08
            控制中心出现电脑卡片
```

---

## 修复方式：两个 hook

区域判断散落在两个进程，但**取数点只有一个**，所以不需要逐功能打补丁。

### ① `com.xiaomi.mirror` — 设备发现 / 设备中心

```
p2.K.D(BusinessServiceInfo, deviceId)   →  本来会是 false 时，若是电脑就改成 true
```

实现要点：`p2.K.D` 跑在 Lyra 服务回调线程上，**在这条路径上绝不做 IPC**
（否则存在回调重入 / 卡死风险）。做法是两步：

1. `p2.K$a.d` / `p2.K$a.c` 的 before-hook 把「本线程正在处理的设备」记进 `ThreadLocal`
   （只读入参里的 `TrustedDeviceInfo`，纯本地判断）；
2. `p2.K.D` 的 after-hook 只读 `ThreadLocal`，命中且是电脑才改结果。

另有一个后台线程低频刷新「deviceId → 设备类型」缓存作兜底，同样不在回调线程做 IPC。

### ② `com.milink.service` — 剪贴板 / 电量 / 文件互传 …

`NetworkingManager.getStringProperty(deviceId, PropBuildRegion)` 是这个进程里
**所有区域判断的共同取数点**：

| 功能 | 调用点 |
|---|---|
| 跨设备剪贴板 | `com.xiaomi.dist.universalclipboardservice.utils.LyraUtil.isSameRegionWithLocal()` |
| 共享通道 / 电量 | `com.miui.circulate.channel.SharedChannelImpl.checkSKUwithLocal()` |
| 文件互传 | `com.xiaomi.dist.file.service.utils.LyraUtil.isSameRegionWithLocal()` |
| 虚拟摄像头等 | 其它同样读这个属性的检查 |

对这一处拦截，**对电脑返回「本机区域」**，各处的 `TextUtils.equals` / `convertSKU` 比较即恒成立。

```java
// PropBuildRegion 的 ordinal = 11
if (prop != 11) return;
if (!isPcDevice(nm, devId)) return;
param.setResult(localRegion);       // 让 equals 恒成立
```

### 覆盖范围

| 区域判断位置 | 在哪个进程 | 本模块 |
|---|---|---|
| `p2.K.D`（设备发现 / 设备中心） | `com.xiaomi.mirror` | ✅ hook ① |
| `LyraUtil.isSameRegionWithLocal`（剪贴板） | `com.milink.service` | ✅ hook ② |
| `SharedChannelImpl.checkSKUwithLocal`（共享通道 / 电量） | `com.milink.service` | ✅ hook ② |
| `file.utils.LyraUtil`（文件互传） | `com.milink.service` | ✅ hook ② |
| `NfcTransportReceiver`（NFC 接力） | `com.milink.service` | ❌ 用的是整数 SKU，另一条路径 |

### 边界

- **不修改电脑**：电脑的 `build_region` 原样不动，模块只在接收侧放行
- **只豁免电脑**：`deviceType == PC(4)` 或 `OTHER_PC(21)`；
  手机(1)、平板(2)、苹果(11~17) 仍完全按厂商原策略处理
- **不伪造设备或服务**：放行之后设备发现、通道协商、设备注册、电量同步
  等全部仍由系统原有逻辑执行，闸门的位置与语义未被改动，
  厂商自己的开关（如 `y3.z.i0()` 互联总开关、`y3.z.r0()` 可达性判断）依旧生效
- 作用域只勾 `com.xiaomi.mirror` 与 `com.milink.service`，不碰其它进程

---

## 更好的解法（推荐先试这个）

**让电脑上报 `cn` 才是根治，之后不需要任何模块。**

实测：把电脑上的区域改成中国，并开启自动设置时区后，
`build_region` 由 `us` 变为 `cn`，上述 5 条症状全部消失，模块全程关闭。

| | 换之前 | 换之后 |
|---|---|---|
| `lyra_version` | `5.1.229.10.0728136` | `5.1.174.10.0312211` |
| **`build_region`** | **`us`** | **`cn`** |
| `medium_types` | 仅 `wifi_lan` | `ble` + `wifi_lan` |

换成国行版之后的日志（模块关闭）：

```
D/LyraServiceManager: isSameRegion deviceId:E3DF9D08 needConvert:false
D/LyraServiceManager: localSku:cn remoteSku:cn                        ← 放行
（不再出现 ignoreServiceDevice not same region, ignore deviceId:E3DF9D08）

I/TrustedDeviceManager: onServiceOnline, serviceName:universalClipboard, deviceId:E3DF9D08
I/UniClipLyraUtil: isSameRegionWithLocal, localRegion = cn, targetRegion = cn
I/Cir_MDC_MDC: refresh export device sequence, size: 2
```

所以建议顺序：**先尝试更改时区并开启自动设置时区后；换不了或换了仍是 `us`，再用本模块。**

> 补充：电脑的区域值读的是注册表 `HKCU\Control Panel\International\Geo\Name`，
> 所以改这个键也可以 —— 但改完必须**完全退出并重启小米电脑管家**（它启动时读一次就缓存），
> 否则不会生效。只改 Windows 的「区域格式」无效，那个写的是另一个键。
> 想保留系统区域不动的话，社区项目
> [MiPCManager_Patch](https://github.com/Higanoneko/MiPCManager_Patch) 的 LocaleSpoof
> 补丁把 `micont_rtm.dll` 读取的值名从 `Name` 改成 `XCN`，再写 `XCN=CN` 即可。

---

## 编译

本仓库自带一个不依赖 Gradle 的构建脚本，只需要 **JDK** 和 **Android SDK build-tools**。

### 需要准备

| 项 | 说明 |
|---|---|
| JDK | 8 以上。脚本优先用 `JAVA_HOME` |
| Android SDK | 设好 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`），脚本自动找最新的 build-tools 与 `platforms/android-34/android.jar` |
| `framework-res.apk` | `aapt2 link` 需要 `-I` 指向框架资源。可以从任意同版本设备拉取：<br>`adb pull /system/framework/framework-res.apk .` |
| 签名密钥 | 不给就用 `keytool` 自动生成一个 debug keystore |

### 构建

```powershell
# 最简：自动探测 ANDROID_HOME / JAVA_HOME
.\build-apk.ps1

# 手动指定
.\build-apk.ps1 -Ver 5.0 -Out build `
    -AndroidJar "D:\Android\Sdk\platforms\android-34\android.jar" `
    -FrameworkRes ".\framework-res.apk" `
    -Keystore ".\debug.jks"
```

产物：`build\MirrorFix-<Ver>.apk`

### 编译桩

`stubs/` 里是最小化的编译期桩（Xposed API 与几个 android 类的空实现），
只为让 `javac` 通过。**运行时这些类由 LSPosed 注入的真实实现替代**，
不会被打进 APK（`d8` 只处理 `com/dsh/mirrorfix/**`）。

如果你更习惯官方 API，可以把 `stubs/de/robv/**` 删掉，改用 LSPosed releases 的
`api-93.jar`，并让 `javac -cp` / `d8 --lib` 指向它（此时仍需 `android.jar`）。

> 踩坑记录：
> - `d8` 对某些 javac 参数组合会抛内部 `NullPointerException`，实测 `-parameters` 可正常通过
> - 编译桩的 `Context.class` 与运行时不是同一个 Class 对象，判断构造器签名请用类名比对
> - 含中文的源文件必须存为 UTF-8（无 BOM）；用 PowerShell 的 `Set-Content` 重写会把编码改成 GBK 而编译报错

---

## 混淆名对照（重要）

jadx 会给重名包加数字前缀，**写模块必须用真实运行时名**：

| jadx 输出 | 真实名字 |
|---|---|
| `p123p2.K` | `p2.K`（LyraServiceManager） |
| `p123p2.E` | `p2.E`（DeviceManager） |
| `p123p2.C0915e` | `p2.e` |
| `p115o2.c` | `o2.c` |
| `p131q2.e` / `p131q2.i` | `q2.e` / `q2.i` |
| `p194y3.z` | `y3.z`（LyraUtils） |
| `p133q4.AbstractC0941i` | `q4.i`（DeviceCenterUtils） |
| `C1.y` / `C1.z` | 未改名（TrustedDeviceManager / 属性枚举） |
| `O2.d` / `P2.a` / `S2.a` | 未改名 |

**注意：混淆映射逐版本不同。** 例如 `com.xiaomi.mirror` 17.01.30 与 18.01.07 的类名就对不上
（17.x 的 `o2.F` = 18.x 的 `p2.K`）。升级后需要重新核对，模块会打
`!! p2.K not found` 之类的日志提示。

`com.milink.service` 侧用到的类都是公开 SDK，**未混淆**：
`com.xiaomi.continuity.networking.NetworkingManager`、`PropertyType`、
`com.xiaomi.continuity.netbus.DeviceType`。

关键常量：

| 名字 | 值 |
|---|---|
| `DeviceType.PC` | 4 |
| `DeviceType.OTHER_PC` | 21 |
| `PropertyType.PropBuildRegion` | 11 |

---

## 安装

1. 设备需 Root + 安装 LSPosed
2. 安装 `MirrorFix.apk`
3. LSPosed 中启用本模块，作用域勾选 **`com.xiaomi.mirror`** 和 **`com.milink.service`**
4. 重启设备

## 验证

```
adb logcat -s MirrorFix
```

期望输出：

```
MirrorFix: hooked p2.K$a.d  (thread marker)
MirrorFix: hooked p2.K.D(BusinessServiceInfo, String)
MirrorFix: hooked com.xiaomi.continuity.networking.NetworkingManager.getStringProperty(String,int)
MirrorFix: 观察到 <电脑ID> 远端区域=us 本机区域=cn（不同区域）
MirrorFix: mirror: 放行电脑 <电脑ID>
MirrorFix: milink: 放行电脑 <电脑ID>（本机区域 cn）
```

逐层自查（**都不需要装模块**）：

```bash
# 区域判定：看 remoteSku 后面那个词
adb logcat -v time -s LyraServiceManager

# 电脑自报的区域与版本
adb logcat -v time | findstr "build_region lyra_version"

# 设备库（需 Root；设备上一般没有 sqlite3，拉下来看）
adb shell su -c 'cp /data/user/0/com.milink.service/databases/cache_device* /sdcard/'
adb pull /sdcard/cache_device .
# 用任意 SQLite 工具打开，看 device 表里有没有 deviceType='Windows' 那行

# 控制中心
adb shell cmd statusbar expand-settings
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
# ui.xml 中应出现 content-desc="<你的电脑名>" 的 miui.systemui.plugin 节点
```

---

## 已知限制

- **安全 / 合规**：跨区域互联是厂商有意设置的市场限制，本模块只是不对电脑套用该限制。
  是否使用请自行判断。
- **NFC 接力**未覆盖（用的是整数 SKU，走另一条路径）。
- 电脑需要处于可被平板发现 / 连接的状态（Lyra 受信任表中可见）才会被放行。
- 电脑电量依赖 `com.milink.deviceprofile` 同步（经共享通道）。区域放行后仍需电脑端
  正常注册 `shared_channel` 才能读到。
- 本模块只改动区域判断这一个点，不修改任何 UI，不伪造设备或通道。
- 系统升级后 `com.xiaomi.mirror` 的混淆名可能变化，需按日志提示重新核对 hook 点。

---

## 目录结构

```
.
├── AndroidManifest.xml
├── assets/
│   └── xposed_init                 # 模块入口类名
├── res/values/
│   └── arrays.xml                  # 作用域：com.xiaomi.mirror + com.milink.service
├── src/com/dsh/mirrorfix/
│   └── MirrorFix.java              # 全部逻辑
├── stubs/                          # 编译期桩（不打进 APK）
├── build-apk.ps1                   # 构建脚本
├── LICENSE
└── README.md
```

## 许可

MIT 见 [LICENSE](LICENSE)
