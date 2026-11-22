import io, json, re

# 1) 静态抽取表（来自 Constants 的 AppWhiteItem 构造反汇编）
static = json.load(io.open("builtin_extracted.json", encoding="utf-8"))
# 2) 运行期真实清单（hook 广播的并集，71 条，以它为准）
runtime = []
for line in io.open("runtime_builtin.txt", encoding="utf-8"):
    m = re.search(r'pkg=([^,]+), cat=(\S+)', line)
    if m:
        runtime.append({"pkg": m.group(1).strip(), "cat": m.group(2).strip()})

merged = {}
for r in static:
    merged[r["pkg"]] = r["cat"]
for r in runtime:
    merged[r["pkg"]] = r["cat"]   # 运行期覆盖静态

entries = sorted(merged.items(), key=lambda kv: (kv[1], kv[0]))
print("static:", len(static), "runtime:", len(runtime), "merged:", len(entries))

head = r"""package com.ikang.carwithany;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CarWith 4.0.21 自带白名单的离线兜底表。
 *
 * 数据来源两路合并（运行期优先）：
 *  1) com.carwith.common.Constants 里 AppWhiteItem 构造的反汇编，结构化抽取 64 条；
 *  2) hook 在 CarWith 进程里抓到的真实清单并集 71 条（含微信/车机版音乐/车机导航等
 *     静态常量里没有的条目，例如 com.tencent.mm、com.tencent.qqmusiccar、com.baidu.naviauto）。
 *
 * 运行期仍以 RedirectHook 广播回来的清单为准（会随后续版本累积），这里只是没收到广播时的兜底，
 * 以及给「自带」标签提供分类。分类可能是 other/music/radio/map/media_app。
 */
public final class BuiltinWhitelist {

    public static final String[][] ENTRIES = {
"""

body = "\n".join('        {"%s", "%s"},' % (p, c) for p, c in entries)

tail = r"""    };

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

io.open(r"D:\work\git\CarWithWhiteList\src\com\ikang\carwithany\BuiltinWhitelist.java",
        "w", encoding="utf-8", newline="\n").write(head + body + "\n" + tail)
io.open(r"D:\work\git\CarWithWhiteList\builtin_whitelist.txt",
        "w", encoding="utf-8", newline="\n").write(
    "\n".join("%s|%s" % (p, c) for p, c in entries) + "\n")
print("written")
