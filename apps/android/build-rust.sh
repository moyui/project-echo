#!/usr/bin/env bash
# 构建安卓侧 Rust 核心（echo-bindings）并更新 jniLibs + 重新生成 Kotlin 绑定
# 用法：在仓库根目录执行  bash apps/android/build-rust.sh [arm64-v8a]
set -euo pipefail

# 以脚本位置为锚点解析仓库根（绝对路径，不依赖调用时的 cwd）
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
NDKBIN="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/windows-x86_64/bin"
TARGET="${1:-arm64-v8a}"

RUST_TARGET="$([ "$TARGET" = "arm64-v8a" ] && echo aarch64-linux-android || echo armv7-linux-androideabi)"

export CC_aarch64_linux_android="$NDKBIN\\aarch64-linux-android26-clang.cmd"
export AR_aarch64_linux_android="$NDKBIN\\llvm-ar.exe"

cargo build --release -p echo-bindings --target "$RUST_TARGET"
mkdir -p "apps/android/app/src/main/jniLibs/$TARGET"
SO="target/$RUST_TARGET/release/libecho_bindings.so"
if [ ! -f "$SO" ]; then
  echo "错误：$SO 未生成，请检查 NDK 路径（$NDKBIN）" >&2
  exit 1
fi
cp "$SO" "apps/android/app/src/main/jniLibs/$TARGET/"

cargo run -q -p echo-bindings --bin uniffi-bindgen -- generate \
  --library "target/$RUST_TARGET/release/libecho_bindings.so" \
  --language kotlin \
  --out-dir apps/android/app/src/main/java/echo

echo "完成：so 已更新，Kotlin 绑定已重新生成"
