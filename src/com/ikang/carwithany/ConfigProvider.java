package com.ikang.carwithany;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.text.TextUtils;

/**
 * 把界面上保存的白名单配置以只读方式暴露给 CarWith 进程里的 hook。
 *
 * 走 Provider 而不是文件的原因：CarWith 是另一个 uid，读不到本应用的私有目录；
 * 用 root 复制文件又要在运行时弹授权、还可能被拒。导出 Provider 只需要
 * content://com.ikang.carwithany.config/extra 一次 query，不涉及任何权限。
 *
 * 返回四列：pkg / cat / name / mode
 *   mode=1 表示「加进白名单」（name 可为空，空则由 hook 用应用真实名称）
 *   mode=0 表示「从白名单里移除」，也就是界面上把 CarWith 自带的条目取消勾选
 */
public class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "com.ikang.carwithany.config";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/extra");
    public static final String PREFS = "cwwl_extra";
    static final String[] CATS = {"other", "music", "radio", "map"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        SharedPreferences sp = getContext().getSharedPreferences(PREFS, 0);
        if ("builtin".equals(uri.getLastPathSegment())) {
            // 只读导出 hook 回传的 CarWith 真实清单，方便命令行核对（含 media_app 等界面未列的分类）
            MatrixCursor c2 = new MatrixCursor(new String[]{"pkg", "cat"});
            String json = sp.getString("builtin_json", "");
            if (!TextUtils.isEmpty(json)) {
                try {
                    org.json.JSONArray arr = new org.json.JSONArray(json);
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject o = arr.optJSONObject(i);
                        if (o == null) {
                            continue;
                        }
                        c2.addRow(new Object[]{o.optString("pkg", ""), o.optString("cat", "other")});
                    }
                } catch (Throwable ignored) {
                }
            }
            return c2;
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{"pkg", "cat", "name", "mode"});
        android.util.Log.i("cwwl-config", "provider query, custom=[" + sp.getString("custom", "")
                + "] cat_other=[" + sp.getString("cat_other", "")
                + "] removed=[" + sp.getString("rm", "") + "]");

        // 手动条目："包名|显示名|分类"，多条用 ; 分隔
        String custom = sp.getString("custom", "");
        if (!TextUtils.isEmpty(custom)) {
            for (String line : custom.split(";")) {
                String[] parts = line.split("\\|");
                if (parts.length < 1 || TextUtils.isEmpty(parts[0].trim())) {
                    continue;
                }
                String pkg = parts[0].trim();
                String name = parts.length > 1 ? parts[1].trim() : "";
                String cat = parts.length > 2 && !TextUtils.isEmpty(parts[2].trim())
                        ? parts[2].trim() : "other";
                cursor.addRow(new Object[]{pkg, cat, name, 1});
            }
        }

        // 各分类里用户主动勾上的应用
        for (String cat : CATS) {
            String raw = sp.getString("cat_" + cat, "");
            if (TextUtils.isEmpty(raw)) {
                continue;
            }
            for (String pkg : raw.split(",")) {
                if (!TextUtils.isEmpty(pkg.trim())) {
                    cursor.addRow(new Object[]{pkg.trim(), cat, null, 1});
                }
            }
        }

        // 用户取消勾选的 CarWith 自带应用 -> 从白名单移除
        String removed = sp.getString("rm", "");
        if (!TextUtils.isEmpty(removed)) {
            for (String pkg : removed.split(",")) {
                if (!TextUtils.isEmpty(pkg.trim())) {
                    cursor.addRow(new Object[]{pkg.trim(), "other", null, 0});
                }
            }
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/com.ikang.carwithany.extra";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
