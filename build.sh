#!/usr/bin/env bash
# wxglass 构建脚本：Compose/Kotlin + Java 的 LSPosed 模块。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

mkdir -p libs out

if [ ! -f libs/api-82.jar ]; then
  echo "== 下载 Xposed API 82 =="
  curl -fsSL -o libs/api-82.jar https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar
fi

export WXGLASS_KEYSTORE_PASSWORD="${WXGLASS_KEYSTORE_PASSWORD:-wxglass2024}"
GRADLE_BIN="${GRADLE_BIN:-gradle}"

echo "== Gradle assembleRelease =="
"$GRADLE_BIN" --no-daemon --stacktrace assembleRelease

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
test -s "$APK"
VERSION_NAME=$(grep -m1 "versionName" app/build.gradle.kts | cut -d '"' -f2)
if [ -z "$VERSION_NAME" ]; then echo "ERROR: versionName not found"; exit 1; fi
cp "$APK" "$ROOT/out/wxglass-$VERSION_NAME.apk"
echo "== APK ready =="
ls -lh "$ROOT/out/wxglass-$VERSION_NAME.apk"
