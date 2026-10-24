#!/usr/bin/env bash
# Build PoolBilliardsPro-Mod-v1.5.0.apk (mod DIGABUNG ke APK game, tanpa root):
#   apktool decompile APK asli -> kompilasi kode mod (javac+d8) -> dex jadi
#   smali lewat APK dummy -> sisipkan sebagai smali_classes5 -> patch smali
#   (GameScene.tick + Main.onResume/onPause) -> apktool build -> zipalign ->
#   apksigner (keystore dari env POOL_KEYSTORE; password dari env
#   POOL_STORE_PASS/POOL_KEY_PASS — CI: GitHub Secrets, lokal: berkas env
#   di ~/workspace/hozin-pool-keys/, TIDAK ada kredensial tertulis di repo).
#
# Prasyarat:
#   - JDK 17 (java, javac, keytool)
#   - ANDROID_HOME menunjuk Android SDK (build-tools 34.0.0 + platforms android-34)
#   - APKTOOL_JAR menunjuk apktool.jar (2.10.0)
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$PWD"
WORK="$ROOT/work"

# Kredensial signing: dari env bila sudah di-set (CI menyuntik dari GitHub
# Secrets); kalau belum, coba berkas env lokal di luar repo.
if [ -z "${POOL_STORE_PASS:-}" ] && [ -f "$HOME/workspace/hozin-pool-keys/pool_signing.env" ]; then
  set -a; . "$HOME/workspace/hozin-pool-keys/pool_signing.env"; set +a
fi

if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -d "$HOME/android-sdk" ]; then ANDROID_HOME="$HOME/android-sdk";
  elif [ -d "$HOME/Android/Sdk" ]; then ANDROID_HOME="$HOME/Android/Sdk"; fi
fi
BT="${ANDROID_HOME:?ANDROID_HOME belum di-set}/build-tools/34.0.0"
AJ="$ANDROID_HOME/platforms/android-34/android.jar"
APKTOOL="${APKTOOL_JAR:?APKTOOL_JAR belum di-set}"
ORIG="$ROOT/original/Pool_Billiards_Pro_5.3.apk"

rm -rf "$WORK"
mkdir -p "$WORK"

echo "== 1. Decompile APK asli =="
java -jar "$APKTOOL" d -f -o "$WORK/apk" "$ORIG"

echo "== 2. Kompilasi kode mod Java -> dex (hanya framework Android) =="
mkdir -p "$WORK/java_out" "$WORK/dex_out"
javac -encoding UTF-8 -source 8 -target 8 -cp "$AJ" -d "$WORK/java_out" \
  $(find "$ROOT/mod-src" -name '*.java')
"$BT/d8" --lib "$AJ" --min-api 23 --output "$WORK/dex_out" \
  "$WORK/java_out"/com/hozin/poolmod/*.class

echo "== 3. Dex mod -> smali lewat APK dummy =="
mkdir -p "$WORK/dummy"
cat > "$WORK/dummy/AndroidManifest.xml" <<'EOF'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.hozin.poolmod" android:versionCode="1" android:versionName="1.0"><uses-sdk android:minSdkVersion="23" android:targetSdkVersion="34"/></manifest>
EOF
"$BT/aapt2" link -o "$WORK/dummy.apk" -I "$AJ" --manifest "$WORK/dummy/AndroidManifest.xml"
(cd "$WORK/dex_out" && zip -j "$WORK/dummy.apk" classes.dex > /dev/null)
java -jar "$APKTOOL" d -f -o "$WORK/dummy_out" "$WORK/dummy.apk"
# tentukan folder smali_classes berikutnya (game punya classes..classes4)
NEXT=2
for d in "$WORK/apk"/smali_classes*; do
  [ -d "$d" ] || continue
  n="${d##*smali_classes}"
  if [ "$n" -ge "$NEXT" ]; then NEXT=$((n + 1)); fi
done
DEST="$WORK/apk/smali_classes$NEXT/com/hozin/poolmod"
mkdir -p "$DEST"
cp "$WORK/dummy_out/smali/com/hozin/poolmod/"*.smali "$DEST/"
echo "   smali mod -> smali_classes$NEXT"

echo "== 4. Patch smali (hook tick/resume/pause) =="
python3 "$ROOT/apply_patches.py" "$WORK/apk"

echo "== 5. Build + zipalign + sign =="
java -jar "$APKTOOL" b "$WORK/apk" -o "$WORK/mod-unsigned.apk"
"$BT/zipalign" -p -f 4 "$WORK/mod-unsigned.apk" "$WORK/mod-aligned.apk"
KS="${POOL_KEYSTORE:-$HOME/workspace/hozin-pool-keys/mod.keystore}"
[ -f "$KS" ] || { echo "keystore tidak ditemukan: $KS (set env POOL_KEYSTORE)" >&2; exit 1; }
: "${POOL_STORE_PASS:?POOL_STORE_PASS belum di-set (env / GitHub Secrets)}"
: "${POOL_KEY_PASS:?POOL_KEY_PASS belum di-set (env / GitHub Secrets)}"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$POOL_STORE_PASS" \
  --key-pass "pass:$POOL_KEY_PASS" --ks-key-alias "${POOL_KEY_ALIAS:-modkey}" \
  --out "$ROOT/PoolBilliardsPro-Mod-v1.5.0.apk" "$WORK/mod-aligned.apk"
"$BT/apksigner" verify "$ROOT/PoolBilliardsPro-Mod-v1.5.0.apk"
"$BT/zipalign" -c 4 "$ROOT/PoolBilliardsPro-Mod-v1.5.0.apk"
ls -la "$ROOT/PoolBilliardsPro-Mod-v1.5.0.apk"
echo "BUILD OK: $ROOT/PoolBilliardsPro-Mod-v1.5.0.apk"
