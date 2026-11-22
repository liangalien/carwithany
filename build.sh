#!/usr/bin/env bash
# 纯命令行构建：javac -> jar -> d8 -> aapt2 -> 合并 classes.dex -> zipalign -> apksigner
# 依赖：JDK 11+、Android SDK（build-tools + platforms;android-36）、python3、zip 工具（aapt2/zipalign/apksigner 都在 build-tools 里）
#
# 用法：
#   ./build.sh                    # 用 version.properties 里的版本号
#   VERSION_NAME=1.4.0 VERSION_CODE=10400 ./build.sh
#   KEYSTORE=/path/my.jks KS_PASS=xxx KEY_PASS=xxx KEY_ALIAS=mykey ./build.sh
#
# 产物：out/CarWithAny-<versionName>.apk、out/AllAppsDrawer-<versionName>.apk
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

# ---------- 版本号 ----------
read_prop() { grep -E "^$1=" version.properties 2>/dev/null | head -n1 | cut -d= -f2- | tr -d ' \r'; }
VERSION_NAME="${VERSION_NAME:-$(read_prop VERSION_NAME)}"
VERSION_CODE="${VERSION_CODE:-$(read_prop VERSION_CODE)}"
VERSION_NAME="${VERSION_NAME:-1.0}"
VERSION_CODE="${VERSION_CODE:-1}"

# ---------- SDK / JDK ----------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  echo "!! 请设置 ANDROID_HOME 指向 Android SDK" >&2
  exit 1
fi

BT="${BUILD_TOOLS_DIR:-}"
if [ -z "$BT" ]; then
  BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -n1)"
fi
[ -x "$BT/aapt2" ] || { echo "!! build-tools 不完整：$BT" >&2; exit 1; }

ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-36/android.jar}"
if [ ! -f "$ANDROID_JAR" ]; then
  ANDROID_JAR="$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -n1)/android.jar"
fi
[ -f "$ANDROID_JAR" ] || { echo "!! 找不到 android.jar（需要 platforms;android-36）" >&2; exit 1; }

JAVAC="${JAVAC:-$(command -v javac || true)}"
[ -n "$JAVAC" ] || { echo "!! 找不到 javac（需要 JDK 11+）" >&2; exit 1; }
JAR_TOOL="${JAR_TOOL:-$(command -v jar || true)}"
PYTHON="${PYTHON:-$(command -v python3 || command -v python)}"
[ -n "$PYTHON" ] || { echo "!! 找不到 python3" >&2; exit 1; }

# ---------- 签名 ----------
KEYSTORE="${KEYSTORE:-$ROOT/keystore/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KEY_PASS="${KEY_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"

BUILD="$ROOT/build"
OUT="$ROOT/out"
LIB="$ROOT/libs/api-82.jar"

rm -rf "$BUILD"
mkdir -p "$BUILD" "$OUT"

if [ ! -f "$LIB" ]; then
  mkdir -p "$(dirname "$LIB")"
  echo "[*] 下载 Xposed api-82.jar"
  curl -fsSL -o "$LIB" 'https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar'
fi

# 合并 classes.dex 到 aapt2 打出来的 base apk（避免 full rebuild 需要 android.jar 全量资源）
cat > "$BUILD/merge_dex.py" <<'PY'
import sys, zipfile
src, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
zin = zipfile.ZipFile(src)
zout = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)
for ins in zin.infolist():
    if ins.filename == 'classes.dex':
        continue
    zout.writestr(ins, zin.read(ins.filename))
zin.close()
zout.write(dex, 'classes.dex')
zout.close()
PY

build_apk() {
  local name="$1" manifest="$2" srcDir="$3" resDir="$4" assetsDir="$5" extraCp="$6" outFile="$7"
  local work="$BUILD/$name"
  local classes="$work/classes" dexDir="$work/dex"

  rm -rf "$work"
  mkdir -p "$classes" "$dexDir"

  echo "[$name] javac"
  local sources
  sources=$(find "$srcDir" -name '*.java' -type f)
  [ -n "$sources" ] || { echo "[$name] 没有 java 源码" >&2; exit 1; }
  local cp="$ANDROID_JAR"
  [ -n "$extraCp" ] && [ -f "$extraCp" ] && cp="$ANDROID_JAR:$extraCp"
  # shellcheck disable=SC2086
  "$JAVAC" -encoding UTF-8 -source 11 -target 11 -nowarn -classpath "$cp" -d "$classes" $sources

  echo "[$name] d8"
  local classJar="$work/classes.jar"
  "$JAR_TOOL" cf "$classJar" -C "$classes" .
  "$BT/d8" --release --min-api 29 --lib "$ANDROID_JAR" --output "$dexDir" "$classJar"

  echo "[$name] aapt2 link"
  local linkArgs=(link -o "$work/base.apk"
      -I "$ANDROID_JAR"
      --manifest "$manifest"
      --min-sdk-version 29
      --target-sdk-version 33
      --version-code "$VERSION_CODE"
      --version-name "$VERSION_NAME"
      --auto-add-overlay)

  if [ -d "$resDir" ] && [ -n "$(find "$resDir" -type f 2>/dev/null)" ]; then
    echo "[$name] aapt2 compile"
    "$BT/aapt2" compile --dir "$resDir" -o "$work/res.zip"
    linkArgs+=(-R "$work/res.zip")
  fi
  if [ -d "$assetsDir" ] && [ -n "$(find "$assetsDir" -type f 2>/dev/null)" ]; then
    linkArgs+=(-A "$assetsDir")
  fi
  "$BT/aapt2" "${linkArgs[@]}"

  echo "[$name] merge dex"
  "$PYTHON" "$BUILD/merge_dex.py" "$work/base.apk" "$dexDir/classes.dex" "$work/merged.apk"

  echo "[$name] zipalign + sign"
  "$BT/zipalign" -f -p 4 "$work/merged.apk" "$work/aligned.apk"
  "$BT/apksigner" sign \
      --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" --ks-key-alias "$KEY_ALIAS" \
      --out "$outFile" "$work/aligned.apk"

  echo "[+] $name -> $outFile ($(du -k "$outFile" | cut -f1) KB)"
}

build_apk 'CarWithAny' \
  "$ROOT/AndroidManifest.xml" "$ROOT/src" "$ROOT/res" "$ROOT/assets" "$LIB" \
  "$OUT/CarWithAny-$VERSION_NAME.apk"

build_apk 'AllAppsDrawer' \
  "$ROOT/drawer/AndroidManifest.xml" "$ROOT/drawer/src" "$ROOT/drawer/res" "$ROOT/drawer/assets" "" \
  "$OUT/AllAppsDrawer-$VERSION_NAME.apk"

echo "完成：$OUT"
