package com.ikang.carwithany;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 不带界面的配置写入（供广播调用，方便命令行/脚本修正配置，界面也用同一套语义）。
 *
 * prefs 结构（与 ConfigActivity 一致）：
 *  cat_other / cat_music / cat_radio / cat_map：用户显式加进该分类的包名
 *  rm：不在白名单里的 CarWith 自带包名
 *  recent：选择先后顺序
 */
public final class ConfigStore {

    public static final String PREFS = "cwwl_extra";
    static final String[] CATS = {"other", "music", "radio", "map"};

    private ConfigStore() {
    }

    /** 给某个包名设定分类；cat 为 null 或 "none" 表示移出白名单 */
    public static void setCategory(Context ctx, String pkg, String cat) {
        if (TextUtils.isEmpty(pkg)) {
            return;
        }
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String assigned = assignedCat(sp, pkg);
        boolean builtin = isBuiltin(ctx, pkg);
        String builtinCat = builtin ? builtinCat(ctx, pkg) : null;

        Set<String> removed = readSet(sp, "rm");
        Set<String> recent = readSet(sp, "recent");

        if (cat == null || "none".equals(cat) || TextUtils.isEmpty(cat)) {
            if (assigned == null) {
                return;
            }
            removeFromAll(sp, pkg);
            recent.remove(pkg);
            if (builtin) {
                removed.add(pkg);
            }
        } else {
            if (cat.equals(assigned)) {
                return;
            }
            removeFromAll(sp, pkg);
            if (builtin && cat.equals(builtinCat)) {
                removed.remove(pkg);
            } else {
                if (builtin) {
                    removed.add(pkg);
                }
                Set<String> target = readSet(sp, "cat_" + cat);
                target.add(pkg);
                writeSet(sp, "cat_" + cat, target);
            }
            recent.remove(pkg);
            LinkedHashSet<String> ordered = new LinkedHashSet<String>();
            ordered.add(pkg);
            ordered.addAll(recent);
            recent = ordered;
        }
        writeSet(sp, "rm", removed);
        writeSet(sp, "recent", recent);
    }

    /** clear = "rm" 只清空移除记录（自带全部恢复默认）；"all" 分类和移除一起清空 */
    public static void clear(Context ctx, String what) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sp.edit();
        if ("all".equals(what)) {
            for (String c : CATS) {
                e.remove("cat_" + c);
            }
        }
        if ("all".equals(what) || "rm".equals(what)) {
            e.remove("rm");
        }
        if ("builtin".equals(what)) {
            e.remove("builtin_json").remove("builtin_at");
        }
        if ("all".equals(what)) {
            e.remove("builtin_json").remove("builtin_at");
        }
        if ("all".equals(what)) {
            e.remove("recent");
        }
        e.commit();
    }

    private static String assignedCat(SharedPreferences sp, String pkg) {
        for (String c : CATS) {
            if (readSet(sp, "cat_" + c).contains(pkg)) {
                return c;
            }
        }
        if (isBuiltinCtx(sp, pkg) && !readSet(sp, "rm").contains(pkg)) {
            return builtinCatCtx(sp, pkg);
        }
        return null;
    }

    private static void removeFromAll(SharedPreferences sp, String pkg) {
        for (String c : CATS) {
            Set<String> s = readSet(sp, "cat_" + c);
            if (s.remove(pkg)) {
                writeSet(sp, "cat_" + c, s);
            }
        }
    }

    static Set<String> readSet(SharedPreferences sp, String key) {
        Set<String> out = new LinkedHashSet<String>();
        String raw = sp.getString(key, "");
        if (!TextUtils.isEmpty(raw)) {
            for (String p : raw.split(",")) {
                if (!TextUtils.isEmpty(p.trim())) {
                    out.add(p.trim());
                }
            }
        }
        return out;
    }

    static void writeSet(SharedPreferences sp, String key, Set<String> set) {
        sp.edit().putString(key, TextUtils.join(",", set)).commit();
    }

    // ---- 自带清单：离线表 + hook 回传的真实清单
    private static boolean isBuiltinCtx(SharedPreferences sp, String pkg) {
        return builtinCatCtx(sp, pkg) != null;
    }

    private static String builtinCatCtx(SharedPreferences sp, String pkg) {
        String raw = sp.getString("builtin_json", "");
        if (!TextUtils.isEmpty(raw)) {
            String cat = BuiltinJson.catOf(raw, pkg);
            if (cat != null) {
                return cat;
            }
        }
        return BuiltinWhitelist.map().get(pkg);
    }

    private static boolean isBuiltin(Context ctx, String pkg) {
        return builtinCat(ctx, pkg) != null;
    }

    private static String builtinCat(Context ctx, String pkg) {
        return builtinCatCtx(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE), pkg);
    }
}
