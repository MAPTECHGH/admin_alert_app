#!/bin/bash
# Build a signed APK for the admin-alert app WITHOUT Gradle (framework-only Kotlin app).
# aapt + kotlinc + dx (dalvik-exchange) + zipalign + apksigner.
#
# Usage:
#   SRC=/path/android-app/app/src/main PKG=com.example.adminalerts \
#   KS=/path/release.jks KS_ALIAS=adminapp KS_PASS=... VER=1.0.0 CODE=1 \
#   OUT_APK=/path/SiteAdmin.apk ./build_apk.sh
#
# One-time toolchain (see SKILL.md "Toolchain"):
#   apt-get install -y aapt apksigner zipalign dalvik-exchange openjdk-17-jdk-headless
#   kotlinc 1.9.24  -> $TOOLS/kotlinc   (github.com/JetBrains/kotlin/releases, kotlin-compiler-1.9.24.zip)
#   android-34.jar  -> $TOOLS/android-34.jar (android.jar of API 34)
set -e
: "${SRC:?SRC = android-app/app/src/main}"; : "${PKG:?PKG = Android package name}"
: "${KS:?KS = keystore path}"; : "${KS_ALIAS:?KS_ALIAS}"; : "${KS_PASS:?KS_PASS}"
TOOLS=${TOOLS:-/home/claude/tools}
J=$TOOLS/android-34.jar; K=$TOOLS/kotlinc
VER=${VER:-1.0.0}; CODE=${CODE:-1}
OUT=${OUT:-/tmp/apkbuild}; OUT_APK=${OUT_APK:-$OUT/app.apk}
PKG_PATH=$(echo "$PKG" | tr . /)
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes"

# manifest: inject package + version + sdk (the Gradle manifest has no package attr)
sed "s#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$PKG\" android:versionCode=\"$CODE\" android:versionName=\"$VER\">\n    <uses-sdk android:minSdkVersion=\"26\" android:targetSdkVersion=\"34\" />#" \
  "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"

aapt package -f -m -J "$OUT/gen" -M "$OUT/AndroidManifest.xml" -S "$SRC/res" -I "$J" -F "$OUT/res.apk"
javac -nowarn -source 8 -target 8 -bootclasspath "$J" -d "$OUT/classes" $(find "$OUT/gen" -name '*.java') 2>&1 | grep -v "Picked up\|warning\|^Note" || true

# IMPORTANT: -Xsam-conversions=class -Xlambdas=class -> no invokedynamic (dx can't desugar it; app crashes on launch otherwise)
"$K/bin/kotlinc" -nowarn -jvm-target 1.8 -Xsam-conversions=class -Xlambdas=class \
  -cp "$J:$OUT/classes" -d "$OUT/classes" $(find "$SRC/java" -name '*.kt') 2>&1 | grep -v "Picked up" || true
test -f "$OUT/classes/$PKG_PATH/MainActivity.class" || { echo "Kotlin compile failed"; exit 1; }
if grep -rlq LambdaMetafactory "$OUT/classes"; then echo "invokedynamic found - would crash on device"; exit 1; fi

cp "$K/lib/kotlin-stdlib.jar" "$OUT/stdlib.jar"; zip -q -d "$OUT/stdlib.jar" 'META-INF/versions/*' >/dev/null 2>&1 || true
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes" "$OUT/stdlib.jar" 2>&1 | grep -v "Picked up" || true
cp "$OUT/res.apk" "$OUT/unsigned.apk"; (cd "$OUT" && aapt add -0 "" unsigned.apk classes.dex >/dev/null)
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" --out "$OUT_APK" "$OUT/aligned.apk" 2>&1 | grep -v "Picked up" || true
apksigner verify "$OUT_APK" 2>&1 | grep -v "Picked up" || true
aapt dump badging "$OUT_APK" | head -1
ls -la "$OUT_APK"
