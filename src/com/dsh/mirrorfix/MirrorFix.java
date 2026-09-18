package com.dsh.mirrorfix;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * MirrorFix — 解除小米互联对「电脑」的跨区域限制。
 *
 * =====================================================================
 * 背景
 * =====================================================================
 *  小米互联有一条「设备必须同区域(region)」的限制。区域值来自设备属性
 *  PropBuildRegion：
 *      Android 端 = ro.miui.build.region（如 cn）
 *      电脑端     = 注册表 HKCU\Control Panel\International\Geo\Name
 *
 *  电脑上的互联组件若不是国内发行版，就会自报 us / global 之类，
 *  于是平板会把它判定为「不同区域」并**静默丢弃**，表现为：
 *      1) 电脑连不上平板：平板侧 accept=3，电脑侧 err_code=15015 user reject
 *      2) 下拉控制中心「融合设备中心」里看不到电脑
 *      3) 跨设备剪贴板不可用
 *      4) 共享通道(shared_channel)建不起来 → 读不到电脑电量
 *      5) 投屏 / 协同等与这台电脑相关的功能全部不可用
 *
 *  实测（平板 cn）：手机 2880C81E = cn → 放行；电脑 E3DF9D08 = us → 被丢弃。
 *
 * =====================================================================
 * 为什么手机不受影响：是版本差异，不是形态差异
 * =====================================================================
 *  com.xiaomi.mirror 17.x（手机）的两个 CastBusiness 注册表都把 deviceType
 *  4(PC) 与 21(OTHER_PC) 放进区域豁免名单：
 *      O2.a$b : i(i7) { return i7 == 21 || i7 == 4; }
 *      R2.a$b : n(i7) { return i7 == 21 || i7 == 4; }
 *  而 18.x（平板）把它们去掉了：
 *      S2.a$b : k(i7) { return i7 == 21; }        // 4 没了
 *      P2.a$b : 连 b()/c() 覆盖都没有 → 所有类型都判定
 *  所以一台升级到 18.x 的手机同样会连不上非国行电脑。这就是本模块的意义。
 *
 * =====================================================================
 * 实现：两个 hook
 * =====================================================================
 *  区域判断散落在两个进程，但取数点很集中。
 *
 *  ① com.xiaomi.mirror
 *      p2.K(LyraServiceManager).D(BusinessServiceInfo, deviceId)
 *        └─ 设备发现 / 设备表 / synergy 通道协商 / 设备中心注册
 *             └─ 决定 accept=3 与下拉控制中心的卡片
 *      ※ 这一处不走 NetworkingManager，用的是自己的 C1.y 包装，需单独 hook
 *
 *  ② com.milink.service
 *      NetworkingManager.getStringProperty(deviceId, PropBuildRegion)
 *        ├─ 跨设备剪贴板  LyraUtil.isSameRegionWithLocal()
 *        ├─ 共享通道/电量 SharedChannelImpl.checkSKUwithLocal()
 *        ├─ 文件互传      file.utils.LyraUtil.isSameRegionWithLocal()
 *        └─ 虚拟摄像头等  其它同样读这个属性的检查
 *      ※ 只在这一处拦截，上述所有检查一并通过
 *
 *  两个 hook 都**不伪造设备、不伪造通道**。放行之后设备发现、通道协商、
 *  设备注册、电量同步等全部仍由系统原有逻辑执行，闸门位置与语义未被改动，
 *  厂商自己的开关（如 y3.z.i0() 互联总开关）依旧生效。
 *  且只对设备类型为 PC / OTHER_PC 的设备生效，手机、平板、苹果设备不受影响。
 *
 * =====================================================================
 * 更好的解法
 * =====================================================================
 *  让电脑上报 cn 才是根治：把电脑上的小米电脑管家换成国内发行版。
 *  之后**不需要任何模块**，全部功能原生恢复。本模块适合换不了电脑端的情况。
 *
 * ---------------------------------------------------------------------
 * 说明：com.xiaomi.mirror 的类名/字段名均为混淆后的真实运行时名，且逐版本可能变化。
 *      jadx 会给重名包加数字前缀（p123p2.K 实为 p2.K），写模块时务必用真名。
 *      com.milink.service 侧用到的都是公开 SDK，未混淆。
 */
public class MirrorFix implements IXposedHookLoadPackage {

    private static final String TAG = "MirrorFix";

    private static final String PKG_MIRROR = "com.xiaomi.mirror";
    private static final String PKG_MILINK = "com.milink.service";

    /** com.xiaomi.continuity.netbus.DeviceType：PC = 4，OTHER_PC = 21 */
    private static final int DEVICE_TYPE_PC = 4;
    private static final int DEVICE_TYPE_OTHER_PC = 21;

    /** com.xiaomi.continuity.networking.PropertyType.PropBuildRegion.ordinal() */
    private static final int PROP_BUILD_REGION = 11;

    // ---------------- com.xiaomi.mirror ----------------
    private static final String CLS_LYRA_SERVICE_MGR  = "p2.K";      // LyraServiceManager
    private static final String CLS_LYRA_SVC_LISTENER = "p2.K$a";    // 其 C1.H 监听器
    private static final String CLS_TRUSTED_MGR       = "C1.y";      // TrustedDeviceManager
    private static final String CLS_MIRROR            = "com.xiaomi.mirror.Mirror";

    /** 当前线程正在处理的设备 ID（由 p2.K$a.d / p2.K$a.c 的 before-hook 写入）。 */
    private static final ThreadLocal<String> sCurrentPc = new ThreadLocal<String>();

    /** deviceId -> TrustedDeviceInfo.g()，后台线程刷新，仅作兜底。 */
    private static final Map<String, Integer> sTypeCache = new HashMap<String, Integer>();

    // ---------------- com.milink.service ----------------
    private static final String CLS_NET_MGR = "com.xiaomi.continuity.networking.NetworkingManager";

    /** deviceId -> 是否电脑。只缓存查询成功的记录（设备类型不会变）。 */
    private static final Map<String, Boolean> sMilinkPcCache = new HashMap<String, Boolean>();

    /** 防止读本机区域时递归进入自己的 hook。 */
    private static final ThreadLocal<Boolean> sInMilinkHook = new ThreadLocal<Boolean>();

    /** 已打过一次「观察到区域」日志的设备，避免刷屏。 */
    private static final java.util.Set<String> sRegionSeen = new java.util.HashSet<String>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (PKG_MIRROR.equals(pkg)) {
            final ClassLoader cl = lpparam.classLoader;
            log("attach to " + lpparam.processName);
            hookDeviceCallback(cl);
            hookMirrorRegionGate(cl);
            startTypeCacheRefresher(cl);
        } else if (PKG_MILINK.equals(pkg)) {
            log("attach to " + lpparam.processName);
            hookMilinkRegionProperty(lpparam.classLoader);
        }
    }

    // ==================================================================
    // ① com.xiaomi.mirror —— 设备发现 / 设备中心
    // ==================================================================

    /**
     * 在设备回调入口记下「本线程正在处理这台设备」。
     *
     * p2.K.D 是在 Lyra 服务回调线程上被调用的。如果在这条路径上再做同步 IPC
     * （例如去 com.xiaomi.mi_connect_service 查设备类型），存在回调重入/卡死
     * 风险。所以拆成两步：入口记标记，D() 里只读标记，不做任何 IPC。
     */
    private void hookDeviceCallback(ClassLoader cl) {
        Class<?> clsA = XposedHelpers.findClassIfExists(CLS_LYRA_SVC_LISTENER, cl);
        if (clsA == null) {
            log("!! " + CLS_LYRA_SVC_LISTENER + " not found");
            return;
        }
        hookTdiArg(clsA, "d", 1);   // d(BusinessServiceInfo, TrustedDeviceInfo) onServiceOnline
        hookTdiArg(clsA, "c", 0);   // c(TrustedDeviceInfo)                      onDeviceChanged
    }

    private void hookTdiArg(Class<?> clsA, String method, final int tdiIndex) {
        try {
            XposedBridge.hookAllMethods(clsA, method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sCurrentPc.remove();
                    try {
                        if (param.args == null || param.args.length <= tdiIndex) return;
                        Object tdi = param.args[tdiIndex];
                        if (tdi == null) return;
                        int type = deviceTypeOf(tdi);
                        if (type != DEVICE_TYPE_PC && type != DEVICE_TYPE_OTHER_PC) return;
                        Object id = XposedHelpers.callMethod(tdi, "e");
                        if (id != null) sCurrentPc.set(String.valueOf(id));
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sCurrentPc.remove();
                }
            });
            log("hooked " + clsA.getName() + "." + method + "  (thread marker)");
        } catch (Throwable t) {
            log("hook " + method + "() failed: " + t);
        }
    }

    /** 跨区域闸门：p2.K.D(BusinessServiceInfo, String) 对电脑放行。 */
    private void hookMirrorRegionGate(ClassLoader cl) {
        Class<?> clsK = XposedHelpers.findClassIfExists(CLS_LYRA_SERVICE_MGR, cl);
        if (clsK == null) {
            log("!! " + CLS_LYRA_SERVICE_MGR + " not found");
            return;
        }
        try {
            XposedBridge.hookAllMethods(clsK, "D", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!Boolean.FALSE.equals(param.getResult())) return;
                        if (param.args == null || param.args.length < 2) return;
                        if (!(param.args[1] instanceof String)) return;
                        String devId = (String) param.args[1];
                        if (devId == null || devId.isEmpty()) return;
                        if (!isLocalPc(devId)) return;

                        param.setResult(Boolean.TRUE);
                        log("mirror: 放行电脑 " + devId);
                    } catch (Throwable t) {
                        log("mirror region gate error: " + t);
                    }
                }
            });
            log("hooked " + CLS_LYRA_SERVICE_MGR + ".D(BusinessServiceInfo, String)");
        } catch (Throwable t) {
            log("hook D() failed: " + t);
        }
    }

    /** 纯本地判断，绝不做 IPC：优先用回调线程标记，其次用后台刷新的类型缓存。 */
    private static boolean isLocalPc(String devId) {
        String cur = sCurrentPc.get();
        if (devId.equals(cur)) return true;
        synchronized (sTypeCache) {
            Integer t = sTypeCache.get(devId);
            return t != null && (t.intValue() == DEVICE_TYPE_PC
                    || t.intValue() == DEVICE_TYPE_OTHER_PC);
        }
    }

    // ==================================================================
    // ② com.milink.service —— 剪贴板 / 共享通道(电量) / 文件互传 …
    // ==================================================================

    /**
     * NetworkingManager.getStringProperty(deviceId, PropBuildRegion) 是
     * com.milink.service 里所有区域判断的共同取数点。对电脑返回「本机区域」，
     * 各处 TextUtils.equals / convertSKU 比较就恒成立，一次覆盖全部检查。
     */
    private void hookMilinkRegionProperty(ClassLoader cl) {
        Class<?> clsNm = XposedHelpers.findClassIfExists(CLS_NET_MGR, cl);
        if (clsNm == null) {
            log("!! " + CLS_NET_MGR + " not found");
            return;
        }
        try {
            XposedBridge.hookAllMethods(clsNm, "getStringProperty", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(sInMilinkHook.get())) return;
                    try {
                        if (param.args == null || param.args.length < 2) return;
                        if (!(param.args[0] instanceof String)) return;
                        if (!(param.args[1] instanceof Integer)) return;
                        if (((Integer) param.args[1]).intValue() != PROP_BUILD_REGION) return;

                        String devId = (String) param.args[0];
                        Object nm = param.thisObject;
                        if (nm == null || devId == null || devId.isEmpty()) return;

                        Object raw = param.getResult();
                        if (!isPcDevice(nm, devId)) return;

                        String localId = localDeviceId(nm);
                        if (localId == null || localId.equals(devId)) return;

                        String localRegion = readRegion(nm, localId);
                        if (localRegion == null || localRegion.isEmpty()) return;

                        noteRegion(devId, raw, localRegion);
                        if (localRegion.equals(raw)) return;

                        param.setResult(localRegion);
                        log("milink: 放行电脑 " + devId + "（本机区域 " + localRegion + "）");
                    } catch (Throwable t) {
                        log("milink region hook error: " + t);
                    }
                }
            });
            log("hooked " + CLS_NET_MGR + ".getStringProperty(String,int)");
        } catch (Throwable t) {
            log("hook getStringProperty() failed: " + t);
        }
    }

    /** 读本机区域；带递归保护，hook 自身会再次触发但会被 sInMilinkHook 挡住。 */
    private static String readRegion(Object nm, String localId) {
        sInMilinkHook.set(Boolean.TRUE);
        try {
            Object v = XposedHelpers.callMethod(nm, "getStringProperty",
                    localId, PROP_BUILD_REGION);
            return (v instanceof String) ? (String) v : null;
        } catch (Throwable t) {
            return null;
        } finally {
            sInMilinkHook.set(Boolean.FALSE);
        }
    }

    private static String localDeviceId(Object nm) {
        try {
            Object local = XposedHelpers.callMethod(nm, "getLocalDeviceInfo");
            if (local == null) return null;
            Object id = XposedHelpers.callMethod(local, "getDeviceId");
            return (id == null) ? null : String.valueOf(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只缓存查询成功的记录；查不到时不缓存，避免临时失败被永久记住。 */
    private static boolean isPcDevice(Object nm, String devId) {
        synchronized (sMilinkPcCache) {
            Boolean v = sMilinkPcCache.get(devId);
            if (v != null) return v.booleanValue();
        }
        try {
            Object tdi = XposedHelpers.callMethod(nm, "getTrustedDeviceInfo", devId);
            if (tdi == null) return false;
            int type = ((Number) XposedHelpers.callMethod(tdi, "getDeviceType")).intValue();
            boolean pc = (type == DEVICE_TYPE_PC || type == DEVICE_TYPE_OTHER_PC);
            synchronized (sMilinkPcCache) {
                sMilinkPcCache.put(devId, Boolean.valueOf(pc));
            }
            return pc;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 每台设备只打一次「观察到区域」日志，便于用户自查。 */
    private static void noteRegion(String devId, Object remote, String local) {
        String key = devId + "|" + remote + "|" + local;
        synchronized (sRegionSeen) {
            if (!sRegionSeen.add(key)) return;
        }
        log("观察到 " + devId + " 远端区域=" + remote + " 本机区域=" + local
                + (local.equals(remote) ? "（同区域）" : "（不同区域）"));
    }

    // ==================================================================
    // com.xiaomi.mirror 兜底：后台刷新设备类型缓存（不在回调线程做 IPC）
    // ==================================================================
    private void startTypeCacheRefresher(final ClassLoader cl) {
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(8000);
                } catch (Throwable ignored) {
                }
                while (true) {
                    try {
                        refreshTypeCache(cl);
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(30000);
                    } catch (Throwable ignored) {
                        return;
                    }
                }
            }
        }, "MirrorFix-type-cache");
        th.setDaemon(true);
        th.start();
    }

    private static void refreshTypeCache(ClassLoader cl) {
        Context ctx = currentContext(cl);
        if (ctx == null) return;
        Class<?> clsY = XposedHelpers.findClassIfExists(CLS_TRUSTED_MGR, cl);
        if (clsY == null) return;
        Object mgr = XposedHelpers.callStaticMethod(clsY, "y", ctx);
        if (mgr == null) return;
        Object list = XposedHelpers.callMethod(mgr, "F");
        if (!(list instanceof List)) return;

        HashMap<String, Integer> fresh = new HashMap<String, Integer>();
        for (Object tdi : (List<?>) list) {
            try {
                String id = String.valueOf(XposedHelpers.callMethod(tdi, "e"));
                fresh.put(id, Integer.valueOf(deviceTypeOf(tdi)));
            } catch (Throwable ignored) {
            }
        }
        synchronized (sTypeCache) {
            sTypeCache.clear();
            sTypeCache.putAll(fresh);
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================
    private static Context currentContext(ClassLoader cl) {
        try {
            Context ctx = AndroidAppHelper.currentApplication();
            if (ctx != null) return ctx;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> clsMirror = XposedHelpers.findClassIfExists(CLS_MIRROR, cl);
            if (clsMirror != null) {
                return (Context) XposedHelpers.callStaticMethod(clsMirror, "C");
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int deviceTypeOf(Object tdi) {
        try {
            return ((Number) XposedHelpers.callMethod(tdi, "g")).intValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log(TAG + ": " + msg);
    }
}
