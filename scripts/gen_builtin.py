import io, json

recs = json.load(io.open("builtin_extracted.json", encoding="utf-8"))
recs.sort(key=lambda r: (r["cat"], r["pkg"]))

pkg_java = r"""package com.ikang.carwithany;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CarWith 4.0.21 自带白名单的离线兜底表（从 com.carwith.common.Constants 的
 * AppWhiteItem 构造反汇编里结构化抽取，64 条）。
 *
 * 注意：运行期以 RedirectHook 广播回来的真实清单为准（它还会累积 CarWith 云控/本地配置里的条目），
 * 这里只是没收到广播时的兜底，也用于给「自带」标签提供分类。
 * 分类除 other/music/radio/map 外还有 media_app（影音，芒果TV/斗鱼/西瓜视频等）。
 */
public final class BuiltinWhitelist {

    public static final String[][] ENTRIES = {
"""

lines_java = []
for r in recs:
    lines_java.append('        {"%s", "%s"},' % (r["pkg"], r["cat"]))

tail_java = r"""    };

    private BuiltinWhitelist() {
    }

    public static Map<String, String> map() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (String[] e : ENTRIES) {
            m.put(e[0], e[1]);
        }
        return m;
    }
}
"""

out = pkg_java + "\n".join(lines_java) + "\n" + tail_java
io.open(r"D:\work\git\CarWithWhiteList\src\com\ikang\carwithany\BuiltinWhitelist.java", "w", encoding="utf-8", newline="\n").write(out)
io.open(r"D:\work\git\CarWithWhiteList\builtin_whitelist.txt", "w", encoding="utf-8", newline="\n").write(
    "\n".join("%s|%s" % (r["pkg"], r["cat"]) for r in recs) + "\n")
print("entries:", len(recs))
