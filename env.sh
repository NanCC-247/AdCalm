#!/usr/bin/env bash
# AdCalm 工具链环境
#
# 用法： source env.sh   （或直接用 ./build.sh）
#
# 这个脚本**不含任何写死的绝对路径**，克隆到任何机器都能用：
#   - 项目根目录由脚本自身位置推导
#   - 若项目路径含非 ASCII 字符（中文目录），自动切到同级的 ASCII 目录联接上构建
#   - 工具链全部在项目内的 .toolchain/ 下，不碰系统目录
#
# 非 ASCII 路径为什么要绕：
#   JVM 的类加载器无法加载含中文的类路径条目，单元测试 worker 会直接报
#   ClassNotFoundException；Windows 上还有一批 native 工具（aapt2 等）
#   对非 ASCII 路径处理不干净。

set -o pipefail 2>/dev/null || true

_AD_CALM_HERE="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"

# 项目路径含非 ASCII 时，找一个同级的纯 ASCII 联接作为构建入口。
# 例如 <父目录>/含中文的工程名  →  <父目录>/adcalm（junction）
if printf '%s' "$_AD_CALM_HERE" | LC_ALL=C grep -q '[^ -~]'; then
  _AD_CALM_ASCII="$(dirname "$_AD_CALM_HERE")/adcalm"
  if [ -d "$_AD_CALM_ASCII" ]; then
    export AD_CALM_ROOT="$_AD_CALM_ASCII"
    export AD_CALM_NEEDS_ASCII_WORKAROUND=1
  else
    echo "⚠ 项目路径含非 ASCII 字符，但没找到 ASCII 联接 $_AD_CALM_ASCII" >&2
    echo "  请先创建：cmd //c \"mklink /J $(cygpath -w "$_AD_CALM_ASCII" 2>/dev/null || echo "$_AD_CALM_ASCII") $(cygpath -w "$_AD_CALM_HERE" 2>/dev/null || echo "$_AD_CALM_HERE")\"" >&2
    export AD_CALM_ROOT="$_AD_CALM_HERE"
  fi
else
  export AD_CALM_ROOT="$_AD_CALM_HERE"
fi

# SDK：项目内的 .toolchain/sdk 优先，没有就用外部已经设好的 ANDROID_HOME。
#
# **这里不能无条件覆盖。** 仓库里不含 .toolchain/（约 3.4GB，已 gitignore），
# 别人克隆下来即使自己设好了 ANDROID_HOME、或者写了 local.properties，
# 一被这里覆盖就变成指向克隆目录下一个不存在的路径，
# 构建直接报 "SDK location not found"。这个 bug 真出现过一次。
if [ -d "$AD_CALM_ROOT/.toolchain/sdk" ]; then
  export ANDROID_HOME="$AD_CALM_ROOT/.toolchain/sdk"
fi
if [ -n "$ANDROID_HOME" ]; then
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

# Gradle 用户目录：和 SDK 同样的规矩——项目内的存在就用它，
# 否则保留外部已经设好的（有些人全局设了 GRADLE_USER_HOME，覆盖掉会让他白下几百 MB），
# 两边都没有才落到项目内。
if [ -d "$AD_CALM_ROOT/.toolchain/gradle-home" ] || [ -z "$GRADLE_USER_HOME" ]; then
  export GRADLE_USER_HOME="$AD_CALM_ROOT/.toolchain/gradle-home"
fi

# PATH：只加确实存在的目录，免得往 PATH 里塞一串不存在的路径
for _ad_calm_dir in \
  "$ANDROID_HOME/platform-tools" \
  "$ANDROID_HOME/cmdline-tools/latest/bin" \
  "$AD_CALM_ROOT/.toolchain/gradle-8.9/bin"
do
  [ -d "$_ad_calm_dir" ] && PATH="$_ad_calm_dir:$PATH"
done
export PATH
unset _ad_calm_dir

# JDK：优先用项目自带的，没有就退回 PATH 里的
if [ -x "$AD_CALM_ROOT/.toolchain/jdk/bin/java" ]; then
  export JAVA_HOME="$AD_CALM_ROOT/.toolchain/jdk"
elif [ -x "$AD_CALM_ROOT/.toolchain/jdk/bin/java.exe" ]; then
  export JAVA_HOME="$AD_CALM_ROOT/.toolchain/jdk"
else
  _java_bin="$(command -v java 2>/dev/null)"
  if [ -n "$_java_bin" ]; then
    export JAVA_HOME="$(cd "$(dirname "$_java_bin")/.." && pwd)"
  fi
  unset _java_bin
fi

if [ ! -d "$ANDROID_HOME" ]; then
  echo "⚠ 找不到 Android SDK：${ANDROID_HOME:-（未设置）}" >&2
  echo "  仓库里不含 .toolchain/。要么按 README「克隆下来之后」一节把 SDK 放进去，" >&2
  echo "  要么自己设 ANDROID_HOME，或者在项目根目录写 local.properties 的 sdk.dir。" >&2
fi

echo "项目根目录   = $AD_CALM_ROOT"
if [ -n "$AD_CALM_NEEDS_ASCII_WORKAROUND" ]; then
  echo "（真实目录含非 ASCII 字符，已切到 ASCII 联接构建）"
fi
echo "ANDROID_HOME = $ANDROID_HOME"
echo "JAVA_HOME    = $JAVA_HOME"

cd "$AD_CALM_ROOT" 2>/dev/null || true
