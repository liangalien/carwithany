import re, io, sys, json

path = r"C:\Users\lsq54\AppData\Local\Doubao\User Data\Default\.doubao\agent_mode\workspace\.sessions\38454901388393730\agents\m_0cwes3BLJgB\cw\consts.txt"
lines = io.open(path, encoding="utf-8", errors="replace").read().splitlines()

str_re = re.compile(r'const-string(?:/jumbo)?\s+v\d+,\s+"(.*?)"')
newinst_re = re.compile(r'new-instance.*AppWhiteItem')
init_re = re.compile(r'invoke-direct.*AppWhiteItem;\.<init>')

records = []
buf = []      # strings collected since last new-instance
active = False

for ln in lines:
    if newinst_re.search(ln):
        active = True
        buf = []
        continue
    if not active:
        continue
    m = str_re.search(ln)
    if m:
        buf.append(m.group(1))
        continue
    if init_re.search(ln):
        active = False
        # 构造顺序固定: pkg, minVersion, cat, name, mode —— 严格按顺序取，避免跨记录串味
        pkg = None; cat = None; name = None
        CATS_SET = ("other", "music", "radio", "map", "media_app", "video", "game", "navi")
        pkg_i = -1
        for i, s in enumerate(buf):
            if "." in s and " " not in s and not re.search(r'[\u4e00-\u9fff]', s):
                pkg = s
                pkg_i = i
                break
        if pkg_i >= 0:
            for s in buf[pkg_i + 1:]:
                if cat is None and s in CATS_SET:
                    cat = s
                    continue
                if cat is not None and name is None and re.search(r'[\u4e00-\u9fff]', s):
                    name = s
                    break
        if pkg and cat:
            records.append({"pkg": pkg, "cat": cat, "name": name or ""})

# 去重（同一 pkg 可能多次出现）
seen = {}
for r in records:
    seen.setdefault(r["pkg"], r)
records = list(seen.values())

from collections import Counter
print("total records:", len(records))
print("by cat:", Counter(r["cat"] for r in records))
for r in sorted(records, key=lambda x: (x["cat"], x["pkg"]))[:200]:
    print("%-45s %-10s %s" % (r["pkg"], r["cat"], r["name"]))
print("HUNANTV:", [r for r in records if "hunantv" in r["pkg"]])
io.open("builtin_extracted.json", "w", encoding="utf-8").write(json.dumps(records, ensure_ascii=False, indent=1))
