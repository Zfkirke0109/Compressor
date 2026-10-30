#!/usr/bin/env bash
set -euo pipefail
repo="$(cd "$(dirname "$0")/../.." && pwd)"
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
cc -std=c11 -O2 -g -Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer \
  -I "$repo/app/src/main/cpp" "$repo/app/src/main/cpp/yuv_packing.c" \
  "$repo/app/src/main/cpp/tests/yuv_packing_test.c" -o "$build/yuv_packing_test"
"$build/yuv_packing_test"
java_root="$(java -XshowSettings:properties -version 2>&1 | awk '$1 == "java.home" { print $3 }')"
java_include="${COMPRESSOR_JNI_INCLUDE:-$java_root/include}"
cc -std=c11 -O3 -Wall -Wextra -Werror -shared -fPIC \
  -I "$java_include" -I "$java_include/linux" -I "$repo/app/src/main/cpp" \
  "$repo/app/src/main/cpp/yuv_packing.c" "$repo/app/src/main/cpp/yuv_jni.c" -o "$build/libpacking.so"
java -Xms128m -Xmx512m "$repo/scripts/ci/I420Packing.java" "$build/libpacking.so"
