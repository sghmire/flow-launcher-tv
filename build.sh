#!/bin/bash
# Targets SDK 33 on purpose: from 34, killBackgroundProcesses() can no longer close other
# apps, which the Recent apps screen relies on.
# Builds Flow without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Usage: ./build.sh [install]   (install pushes to $ANDROID_SERIAL, default the TV)
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-34/android.jar"
JAVA_HOME="${JAVA_HOME:-$HOME/Library/Java/JavaVirtualMachines/jbr-21.0.8/Contents/Home}"
OUT=build
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 28 --target-sdk-version 33 \
  --java "$OUT/gen" -o "$OUT/flow.unaligned.apk" "$OUT/res.zip"

"$JAVA_HOME/bin/javac" -nowarn -Xlint:-options -source 8 -target 8 \
  -bootclasspath "$JAR:$SDK/build-tools/34.0.0/core-lambda-stubs.jar" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')

"$BT/d8" --release --min-api 28 --lib "$JAR" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

(cd "$OUT/dex" && zip -q -j ../flow.unaligned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/flow.unaligned.apk" "$OUT/flow.aligned.apk"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey \
  --out "$OUT/flow.apk" "$OUT/flow.aligned.apk"
echo "Built $OUT/flow.apk ($(du -h "$OUT/flow.apk" | cut -f1))"

if [ "${1:-}" = "install" ]; then
  export ANDROID_SERIAL="${ANDROID_SERIAL:-192.168.1.98:38163}"
  adb install -r "$OUT/flow.apk"
  adb shell pm grant com.sghmire.flow android.permission.READ_TV_LISTINGS || true
  adb shell appops set com.sghmire.flow GET_USAGE_STATS allow || true
fi
