package com.ikang.carwithany;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 收 CarWith 进程里 hook 广播回来的「真实内置白名单」，存到本 App 的 prefs。
 * 这样配置界面就能准确标出哪些应用是 CarWith 自带的，而不是靠离线猜。
 *
 * 同一个 receiver 还兼一个可脚本化的配置写入入口（方便命令行修正配置）：
 *   adb shell am broadcast -a com.ikang.carwithany.SET -p com.ikang.carwithany \
 *       --es pkg <包名> --es cat other|music|radio|map|none
 *   adb shell am broadcast -a com.ikang.carwithany.SET -p com.ikang.carwithany --es clear rm|all
 */
public class BuiltinReceiver extends BroadcastReceiver {

    private static final String TAG = "cwwl-config";
    public static final String ACTION_SET = "com.ikang.carwithany.SET";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (ACTION_SET.equals(intent.getAction())) {
                handleSet(context, intent);
                return;
            }
            String json = intent.getStringExtra("json");
            if (json == null || json.isEmpty()) {
                return;
            }
            SharedPreferences sp = context.getSharedPreferences("cwwl_extra", Context.MODE_PRIVATE);
            // 累积并集：CarWith 不同入口给的清单条数不一样，只合并不覆盖，避免漏条目
            String merged = merge(sp.getString("builtin_json", ""), json);
            sp.edit().putString("builtin_json", merged)
                    .putLong("builtin_at", System.currentTimeMillis())
                    .apply();
            Log.i(TAG, "builtin list received, len=" + json.length() + " merged=" + merged.length());
        } catch (Throwable t) {
            Log.i(TAG, "builtin receive failed: " + t);
        }
    }

    private void handleSet(Context context, Intent intent) {
        String clear = intent.getStringExtra("clear");
        if (clear != null && !clear.isEmpty()) {
            ConfigStore.clear(context, clear);
            Log.i(TAG, "config cleared: " + clear);
        }
        String pkg = intent.getStringExtra("pkg");
        if (pkg != null && !pkg.isEmpty()) {
            String cat = intent.getStringExtra("cat");
            ConfigStore.setCategory(context, pkg, cat);
            Log.i(TAG, "config set: " + pkg + " -> " + cat);
        }
    }

    /** 把两段 builtin_json 合并成并集（已有优先，新来的补充） */
    private String merge(String oldJson, String newJson) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        putAll(map, newJson);
        putAll(map, oldJson);
        JSONArray arr = new JSONArray();
        for (Map.Entry<String, String> e : map.entrySet()) {
            JSONObject o = new JSONObject();
            try {
                o.put("pkg", e.getKey());
                o.put("cat", e.getValue());
            } catch (Throwable ignored) {
            }
            arr.put(o);
        }
        return arr.toString();
    }

    private void putAll(Map<String, String> target, String json) {
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String pkg = o.optString("pkg", "").trim();
                if (!pkg.isEmpty() && !target.containsKey(pkg)) {
                    target.put(pkg, o.optString("cat", "other"));
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
