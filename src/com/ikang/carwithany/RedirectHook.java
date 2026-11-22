package com.ikang.carwithany;

import android.app.ActivityOptions;
import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * 在 CarWith 进程里做两件事：
 *
 * 1) 放行投屏内的跨应用跳转
 *    CarWith 4.0.21 在投屏里点开"非白名单应用"会弹「投屏应用不支持跳转」。
 *    两个拦截点都读 Settings.Global 的 ucar_ae_projection_debug_mode：
 *      com.carwith.launcher.surface.FloatingWindCastWrapper#t(Intent, int)
 *      com.carwith.launcher.activity.PhoneBlockTipsActivity#onCreate(Bundle)
 *    0 = 拦并提示，2 = 用 ActivityOptions.setLaunchDisplayId 在投屏屏拉起。
 *
 * 2) 替抽屉应用（包名冒名 com.zhihu.android）在车机虚拟屏里拉起任意应用
 *    车机那块屏是 CarWith 自己 createVirtualDisplay 出来的 private display，
 *    第三方进程往上面 startActivity 会被拒（或退回手机屏）。所以由本模块在
 *    CarWith 进程内（display 属主）代为拉起：
 *      抽屉 --广播(dev.local.cwwl.LAUNCH{pkg,display})--> CarWith 进程 receiver
 *      -> getLaunchIntentForPackage + ActivityOptions.setLaunchDisplayId(display)
 */
public class RedirectHook implements IXposedHookLoadPackage {

    private static final String TAG = "CarWithRedirect";
    private static final String TARGET_PKG = "com.miui.carlink";
    private static final String DEBUG_KEY = "ucar_ae_projection_debug_mode";

    /** 抽屉发过来的拉起请求 */
    private static final String ACTION_LAUNCH = "dev.local.cwwl.LAUNCH";
    private static final String EXTRA_PKG = "pkg";
    private static final String EXTRA_DISPLAY = "display";

    /** 从 CarWith 自己的 setLaunchDisplayId 调用里捕获到的投屏屏 id */
    private static volatile int sCastDisplay = -1;
    private static volatile Context sAppContext;
    private static volatile boolean sReceiverReady = false;

    /** 把 CarWith 真实内置白名单回传给模块 App 界面，每个进程只发一次 */
    private static final String ACTION_BUILTIN = "com.ikang.carwithany.BUILTIN";


    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }
        log("module loaded in " + lpparam.processName);

        hookDebugMode(lpparam);
        hookDisplayCapture(lpparam);
        hookAppOnCreate(lpparam);
        hookWhitelist(lpparam);
    }

    // -------------------------------------------------- 4. 白名单注入（可选）
    /**
     * 配置界面（ConfigActivity）把选择写到 /data/data/com.miui.carlink/files/cwwl_extra.json，
     * 结构是 {"music":["pkg1","pkg2"], "other":[...]}。
     * 这里在 CarWith 汇总白名单的两个入口上做合并：
     *   CloudControlBean#getAppWhiteList() 返回值
     *   com.carwith.common.utils.c0#s0(List) 入参
     * 注入的条目 minVersion 一律 0（不触发"版本过低"），category 用界面里选的那个。
     */
    private static final String EXTRA_FILE = "/data/data/" + TARGET_PKG + "/files/cwwl_extra.json";

    private void hookWhitelist(LoadPackageParam lpparam) {
        try {
            Class<?> c0 = XposedHelpers.findClass("com.carwith.common.utils.c0", lpparam.classLoader);
            XposedBridge.hookAllMethods(c0, "s0", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof List)) {
                            return;
                        }
                        List merged = mergeExtras((List) param.args[0], lpparam.classLoader);
                        if (merged != null) {
                            param.args[0] = merged;
                        }
                    } catch (Throwable t) {
                        log("s0 inject failed");
                        logThrowable(t);
                    }
                }
            });
            log("hook c0#s0 ok");
        } catch (Throwable t) {
            log("hook c0#s0 failed");
            logThrowable(t);
        }

        try {
            Class<?> bean = XposedHelpers.findClass(
                    "com.carwith.common.bean.CloudControlBean", lpparam.classLoader);
            XposedBridge.hookAllMethods(bean, "getAppWhiteList", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.getResult() instanceof List)) {
                            return;
                        }
                        List merged = mergeExtras((List) param.getResult(), lpparam.classLoader);
                        if (merged != null) {
                            param.setResult(merged);
                        }
                    } catch (Throwable t) {
                        log("getAppWhiteList inject failed");
                        logThrowable(t);
                    }
                }
            });
            log("hook CloudControlBean#getAppWhiteList ok");
        } catch (Throwable t) {
            log("hook getAppWhiteList skipped: " + t);
        }
    }

    private static List mergeExtras(List origin, ClassLoader cl) {
        List<String[]> extras = readExtras();

        // 把设备上真实的内置清单发回模块 App，界面里就能标出「CarWith 自带」
        broadcastBuiltin(origin);

        if (extras.isEmpty()) {
            return null;
        }

        // 界面上被取消勾选的自带应用 = 要从白名单里删掉
        Set<String> removals = new HashSet<String>();
        Set<String> userPkgs = new HashSet<String>();
        for (String[] e : extras) {
            boolean remove = e.length > 3 && "0".equals(e[3]);
            if (remove) {
                removals.add(e[0]);
            } else {
                userPkgs.add(e[0]);
            }
        }

        List out = new ArrayList();
        Set<String> present = new HashSet<String>();
        for (Object o : origin) {
            String[] pc = itemPkgCat(o);
            if (pc[0] != null && removals.contains(pc[0])) {
                continue; // 界面里没选分类（或改了分类）的自带条目，先删掉原条目
            }
            out.add(o);
            if (pc[0] != null) {
                present.add(pc[0]);
            }
        }
        boolean changed = out.size() != origin.size();

        Class<?> itemCls = XposedHelpers.findClass(
                "com.carwith.common.bean.AppWhiteItem", cl);
        for (String[] e : extras) {
            if (e.length > 3 && "0".equals(e[3])) {
                continue; // 删除指令，不是新增
            }
            if (present.contains(e[0])) {
                continue;
            }
            try {
                String label = (e.length > 2 && e[2] != null && !e[2].isEmpty()) ? e[2] : labelOf(e[0]);
                out.add(XposedHelpers.newInstance(itemCls, e[0], 0L, e[1], label, 1));
                present.add(e[0]);
                changed = true;
                log("whitelist += [" + e[1] + "] " + e[0] + " as " + label);
            } catch (Throwable t) {
                log("new AppWhiteItem(" + e[0] + ") failed: " + t);
            }
        }
        if (!removals.isEmpty()) {
            log("whitelist removed builtin: " + removals.size() + ", " + origin.size()
                    + " -> " + out.size());
        }
        return changed ? out : null;
    }

    /** 从 AppWhiteItem 里反射出 {包名, 分类} */
    private static String[] itemPkgCat(Object o) {
        String pkg = null;
        String cat = null;
        try {
            for (Field f : o.getClass().getDeclaredFields()) {
                if (f.getType() != String.class) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(o);
                if (v == null) {
                    continue;
                }
                String s = (String) v;
                if ("other".equals(s) || "music".equals(s) || "radio".equals(s) || "map".equals(s)) {
                    cat = s;
                } else if (pkg == null && s.indexOf('.') > 0 && s.indexOf(' ') < 0) {
                    pkg = s;
                }
            }
        } catch (Throwable ignored) {
        }
        return new String[]{pkg, cat};
    }

    /** 已见过的内置条目（pkg -> cat），不同调用点给到的清单会有差异，累积成并集 */
    private static final Map<String, String> sBuiltinSeen = new LinkedHashMap<String, String>();
    private static int sBuiltinBroadcasts = 0;

    /**
     * 把 CarWith 真实白名单广播给模块 App，界面用来标注「自带」。
     * CarWith 内部有多份清单（内置常量、云控、本地配置），不同入口给到的条数不一样，
     * 所以每次调用都把新见到的条目并进累积表，再整体广播，避免漏掉芒果TV 这类条目。
     */
    private static void broadcastBuiltin(List origin) {
        // 我们自己注入的条目也会出现在 origin 里，不能当成 CarWith 自带，先排除
        Set<String> injected = new HashSet<String>();
        for (String[] e : readExtras()) {
            boolean remove = e.length > 3 && "0".equals(e[3]);
            if (!remove) {
                injected.add(e[0]);
            }
        }
        for (Object o : origin) {
            String[] pc = itemPkgCat(o);
            if (pc[0] == null || injected.contains(pc[0])) {
                continue;
            }
            String cat = pc[1] == null ? "other" : pc[1];
            if (!sBuiltinSeen.containsKey(pc[0])) {
                sBuiltinSeen.put(pc[0], cat);
            }
        }
        if (sBuiltinBroadcasts > 20) {
            return; // 每次注入都会调到这里，广播前 20 次足够
        }
        sBuiltinBroadcasts++;
        try {
            Context ctx = sAppContext != null ? sAppContext : AndroidAppHelper.currentApplication();
            if (ctx == null) {
                return;
            }
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, String> e : sBuiltinSeen.entrySet()) {
                JSONObject j = new JSONObject();
                j.put("pkg", e.getKey());
                j.put("cat", e.getValue());
                arr.put(j);
            }
            Intent it = new Intent(ACTION_BUILTIN);
            it.setClassName("com.ikang.carwithany", "com.ikang.carwithany.BuiltinReceiver");
            it.putExtra("json", arr.toString());
            ctx.sendBroadcast(it);
            log("broadcast builtin list: " + arr.length() + " (origin " + origin.size() + ")");
            if (sBuiltinBroadcasts == 1) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> e : sBuiltinSeen.entrySet()) {
                    sb.append(e.getKey()).append('|').append(e.getValue()).append(' ');
                }
                log("builtin dump: " + sb);
            }
        } catch (Throwable t) {
            log("broadcast builtin failed: " + t);
        }
    }

    /** 把对象里所有 String 字段的值收集起来，用来判断某包名是否已在白名单里 */
    private static void collectStrings(Object obj, Set<String> out) {
        if (obj == null) {
            return;
        }
        try {
            Field[] fields = obj.getClass().getDeclaredFields();
            for (Field f : fields) {
                if (f.getType() != String.class) {
                    continue;
                }
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v != null) {
                    out.add(String.valueOf(v));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String labelOf(String pkg) {
        try {
            Context ctx = sAppContext != null ? sAppContext : AndroidAppHelper.currentApplication();
            if (ctx != null) {
                PackageManager pm = ctx.getPackageManager();
                return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            }
        } catch (Throwable ignored) {
        }
        return pkg;
    }

    private static List<String[]> readExtras() {
        List<String[]> out = new ArrayList<String[]>();
        Set<String> seen = new HashSet<String>();

        // 1) 首选：查模块 App 导出的只读 Provider（无需 root）
        Context ctx = sAppContext != null ? sAppContext : AndroidAppHelper.currentApplication();
        if (ctx != null) {
            try {
                android.database.Cursor c = ctx.getContentResolver().query(
                        android.net.Uri.parse("content://com.ikang.carwithany.config/extra"),
                        null, null, null, null);
                if (c != null) {
                    int ip = c.getColumnIndex("pkg");
                    int ic = c.getColumnIndex("cat");
                    int in = c.getColumnIndex("name");
                    int im = c.getColumnIndex("mode");
                    while (c.moveToNext()) {
                        String pkg = ip >= 0 ? c.getString(ip) : null;
                        String cat = ic >= 0 ? c.getString(ic) : "other";
                        String name = in >= 0 ? c.getString(in) : null;
                        String mode = im >= 0 && !c.isNull(im) ? String.valueOf(c.getInt(im)) : "1";
                        // 同一个包可能先在「勾选」里再在「移除」里，以移除为准
                        if (pkg != null && !pkg.trim().isEmpty()) {
                            String key = pkg.trim() + "|" + mode;
                            if (seen.add(key)) {
                                out.add(new String[]{pkg.trim(), cat, name, mode});
                            }
                        }
                    }
                    c.close();
                }
            } catch (Throwable t) {
                log("query config provider failed: " + t);
            }
        }

        // 2) 兜底：老版本用 root 同步过来的文件
        File f = new File(EXTRA_FILE);
        if (f.exists()) {
            try {
                StringBuilder sb = new StringBuilder();
                FileInputStream in = new FileInputStream(f);
                BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                br.close();
                JSONObject json = new JSONObject(sb.toString());
                Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String cat = keys.next();
                    JSONArray arr = json.optJSONArray(cat);
                    if (arr == null) {
                        continue;
                    }
                    for (int i = 0; i < arr.length(); i++) {
                        Object el = arr.opt(i);
                        String pkg = null;
                        String name = null;
                        if (el instanceof String) {
                            pkg = ((String) el).trim();
                        } else if (el instanceof JSONObject) {
                            JSONObject o = (JSONObject) el;
                            pkg = o.optString("pkg", "").trim();
                            name = o.optString("name", "").trim();
                        }
                        if (pkg != null && !pkg.isEmpty() && seen.add(pkg + "|1")) {
                            out.add(new String[]{pkg, cat, name, "1"});
                        }
                    }
                }
            } catch (Throwable t) {
                log("read extra file failed: " + t);
            }
        }

        log("extra whitelist entries: " + out.size());
        return out;
    }

    // ---------------------------------------------------------------- 1. 放行跳转
    private void hookDebugMode(LoadPackageParam lpparam) {
        try {
            Class<?> global = XposedHelpers.findClass(
                    "android.provider.Settings$Global", lpparam.classLoader);
            XposedBridge.hookAllMethods(global, "getInt", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length >= 2
                            && DEBUG_KEY.equals(param.args[1])) {
                        Object original = param.getResult();
                        param.setResult(2);
                        log("projection debug mode " + original + " -> 2");
                    }
                }
            });
            log("hook Settings$Global#getInt(" + DEBUG_KEY + ") ok");
        } catch (Throwable t) {
            log("hook debug mode failed");
            logThrowable(t);
        }
    }

    // ------------------------------------------- 2. 记录 CarWith 用的投屏 display id
    private void hookDisplayCapture(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("android.app.ActivityOptions", lpparam.classLoader,
                    "setLaunchDisplayId", int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int id = (Integer) param.args[0];
                            if (id > 0 && id != sCastDisplay) {
                                sCastDisplay = id;
                                log("cast display captured: " + id);
                            }
                        }
                    });
            log("hook ActivityOptions#setLaunchDisplayId ok");
        } catch (Throwable t) {
            log("hook display capture failed");
            logThrowable(t);
        }

        // 只做观测，不参与判定（这个 int 也可能是别的语义）
        try {
            Class<?> wrapper = XposedHelpers.findClass(
                    "com.carwith.launcher.surface.FloatingWindCastWrapper", lpparam.classLoader);
            XposedBridge.hookAllMethods(wrapper, "t", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    log("FloatingWindCastWrapper#t args=" + param.args);
                }
            });
        } catch (Throwable t) {
            log("observe wrapper#t skipped: " + t);
        }
    }

    // ------------------------------------------------------- 3. 注册拉起请求接收器
    private void hookAppOnCreate(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Context ctx = (Context) param.thisObject;
                    sAppContext = ctx;
                    registerLaunchReceiver(ctx);
                }
            });
            log("hook Application#onCreate ok");
        } catch (Throwable t) {
            log("hook Application#onCreate failed");
            logThrowable(t);
        }
    }

    private static synchronized void registerLaunchReceiver(Context ctx) {
        if (sReceiverReady) {
            return;
        }
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    handleLaunch(context, intent);
                } catch (Throwable t) {
                    log("launch from drawer failed");
                    logThrowable(t);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_LAUNCH);
        try {
            // API 33+ 必须显式声明导出属性；用反射避免老系统上没有这个重载
            Method m = Context.class.getMethod("registerReceiver",
                    BroadcastReceiver.class, IntentFilter.class, int.class);
            m.invoke(ctx, receiver, filter, 2 /* Context.RECEIVER_EXPORTED */);
            sReceiverReady = true;
            log("launch receiver registered (flags)");
        } catch (Throwable t) {
            try {
                ctx.registerReceiver(receiver, filter);
                sReceiverReady = true;
                log("launch receiver registered (legacy)");
            } catch (Throwable t2) {
                log("register launch receiver failed");
                logThrowable(t2);
            }
        }
    }

    private static void handleLaunch(Context context, Intent intent) {
        String pkg = intent.getStringExtra(EXTRA_PKG);
        if (pkg == null || pkg.trim().isEmpty()) {
            log("launch request without pkg");
            return;
        }
        pkg = pkg.trim();
        int display = pickCastDisplay(context);
        if (display <= 0) {
            display = intent.getIntExtra(EXTRA_DISPLAY, -1);
        }
        if (display <= 0) {
            display = sCastDisplay;
        }

        PackageManager pm = context.getPackageManager();
        Intent launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) {
            log("no launch intent for " + pkg);
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (display > 0) {
            try {
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(display);
                Bundle bundle = options.toBundle();
                context.startActivity(launch, bundle);
                log("launched " + pkg + " on display " + display);
                return;
            } catch (Throwable t) {
                log("launch with display " + display + " failed, falling back");
                logThrowable(t);
            }
        }
        try {
            context.startActivity(launch);
            log("launched " + pkg + " on default display");
        } catch (Throwable t) {
            log("launch " + pkg + " failed");
            logThrowable(t);
        }
    }

    /**
     * 选出 CarWith 的「投屏主画面」display。
     *
     * 实测 CarWith 会同时持有好几块 virtual display：
     *   com.miui.carlink       1024x576 @220   ← 车机画面（launcher/投屏主画面），要这块
     *   com.miui.carlink       2968x1800 @400  ← 应用大画布（离屏），不是车机可见画面
     *   com.xiaomi.ucar.minimap 614x552 @220    ← 某个导航应用自己建的屏，跟投屏无关
     * 更坑的是 CarWith 自己 setLaunchDisplayId 传的往往是最后创建的那块（minimap 那种），
     * 直接用它会把画面扔到看不见的地方。
     *
     * 规则：名字里带 com.miui.carlink、横屏、宽度最小的那块（车机真实分辨率通常明显小于
     * 手机分辨率撑出来的画布），取不到再退到捕获值。
     */
    private static int pickCastDisplay(Context context) {
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                            context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                return -1;
            }
            android.view.Display[] displays = dm.getDisplays();
            int best = -1;
            int bestW = Integer.MAX_VALUE;
            for (android.view.Display d : displays) {
                if (d == null) {
                    continue;
                }
                String name = d.getName();
                android.graphics.Point size = new android.graphics.Point();
                d.getRealSize(size);
                boolean isCarlink = name != null && name.contains(TARGET_PKG);
                if (isCarlink) {
                    log("display " + d.getDisplayId() + " name=" + name
                            + " size=" + size.x + "x" + size.y + " state=" + d.getState());
                }
                if (!isCarlink) {
                    continue;
                }
                boolean landscape = size.x > size.y;
                if (!landscape || size.x <= 0) {
                    continue;
                }
                if (size.x < bestW) {
                    bestW = size.x;
                    best = d.getDisplayId();
                }
            }
            if (best > 0) {
                log("cast display selected: " + best + " (" + bestW + "px wide)");
            }
            return best;
        } catch (Throwable t) {
            log("pick cast display failed");
            logThrowable(t);
            return -1;
        }
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    private static void logThrowable(Throwable t) {
        XposedBridge.log(TAG + ": " + t);
    }
}


