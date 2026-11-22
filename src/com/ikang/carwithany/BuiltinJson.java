package com.ikang.carwithany;

import org.json.JSONArray;
import org.json.JSONObject;

/** builtin_json 的轻量解析：给 ConfigStore 查某包名在 CarWith 侧的分类 */
public final class BuiltinJson {

    private BuiltinJson() {
    }

    public static String catOf(String raw, String pkg) {
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                if (pkg.equals(o.optString("pkg", "").trim())) {
                    return o.optString("cat", "other");
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
