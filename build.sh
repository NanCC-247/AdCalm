#!/usr/bin/env bash
# 构建入口。所有构建都走这个脚本，它会自动定位项目根目录并配好工具链。
#
#   ./build.sh                      # 编译 Debug APK
#   ./build.sh testDebugUnitTest    # 只跑单元测试
#   ./build.sh connectedDebugAndroidTest   # 在已连接的真机/模拟器上跑仪器测试
#   ./build.sh clean                # 清理
#
# ⚠ connectedDebugAndroidTest 跑完会把它自己装的 APK **卸载**，连应用数据一起清掉。
#   在装了正式配置的手机上跑之前，先备份 shared_prefs 和日志。
set -e

cd "$(dirname "$0")"
# shellcheck source=env.sh
source ./env.sh
echo

TASK="${1:-assembleDebug}"
shift 2>/dev/null || true

./gradlew "$TASK" "$@"

if [ "$TASK" = "assembleDebug" ]; then
  # 路径推导必须和 app/build.gradle.kts 里的规则一致：
  # 项目路径含非 ASCII 时产物被重定向到同级目录，否则走标准的 build/。
  if [ -n "$AD_CALM_NEEDS_ASCII_WORKAROUND" ]; then
    APK="$(dirname "$AD_CALM_ROOT")/adcalm-build/app/outputs/apk/debug/app-debug.apk"
  else
    APK="$AD_CALM_ROOT/app/build/outputs/apk/debug/app-debug.apk"
  fi
  if [ -f "$APK" ]; then
    echo
    echo "APK: $APK"
    ls -la "$APK"
  else
    echo
    echo "未找到 APK：$APK" >&2
  fi
fi
