#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PJSIP_CHECKOUT="${OPENCALLER_PJSIP_SOURCE:-$ROOT_DIR/third_party/pjproject}"
MBEDTLS_CHECKOUT="${OPENCALLER_MBEDTLS_SOURCE:-$ROOT_DIR/third_party/mbedtls}"
GENERATED_DIR="${OPENCALLER_PJSIP_GENERATED_DIR:-$ROOT_DIR/app/build/generated/pjsip}"
WORK_DIR="$GENERATED_DIR/native"
SOURCE_DIR="$WORK_DIR/source"
BUILD_DIR="$WORK_DIR/build"
ABI="${OPENCALLER_ANDROID_ABI:-arm64-v8a}"
ANDROID_API="${OPENCALLER_ANDROID_API:-26}"
BUILD_JOBS="${OPENCALLER_BUILD_JOBS:-2}"

fail() {
    printf 'OpenCaller native build: %s\n' "$1" >&2
    exit 1
}

to_posix_path() {
    local path="$1"
    if command -v cygpath >/dev/null 2>&1 && [[ "$path" =~ ^[A-Za-z]:[\\/] ]]; then
        cygpath -u "$path"
    else
        printf '%s\n' "$path"
    fi
}

to_msys_drive_path() {
    local path="$1"
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$path"
    else
        printf '%s\n' "$path"
    fi
}

for path in "$PJSIP_CHECKOUT" "$MBEDTLS_CHECKOUT"; do
    [[ -d "$path" ]] || fail "missing pinned source at $path; clone with --recurse-submodules"
done
[[ -f "$MBEDTLS_CHECKOUT/framework/CMakeLists.txt" ]] || fail "Mbed TLS framework submodule is missing"
command -v cmake >/dev/null 2>&1 || fail "CMake is required (Android SDK CMake 3.22.1 or newer)"
command -v ninja >/dev/null 2>&1 || fail "Ninja is required (included with Android SDK CMake)"
command -v swig >/dev/null 2>&1 || fail "SWIG 4.0 or newer is required on PATH"
command -v make >/dev/null 2>&1 || fail "GNU Make is required"
PYTHON_BIN="${PYTHON:-python3}"
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
    PYTHON_BIN=python
fi
command -v "$PYTHON_BIN" >/dev/null 2>&1 || fail "Python 3 is required to configure Mbed TLS threading"

NDK_RAW="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
[[ -n "$NDK_RAW" ]] || fail "set ANDROID_NDK_ROOT to Android NDK 28.2.13676358"
NDK_ROOT="$(to_posix_path "$NDK_RAW")"
NDK_PJSIP_ROOT="$(to_msys_drive_path "$NDK_ROOT")"
NDK_TOOLCHAIN="$NDK_ROOT/build/cmake/android.toolchain.cmake"
[[ -f "$NDK_TOOLCHAIN" ]] || fail "Android NDK toolchain not found under $NDK_ROOT"

JAVA_HOME_RAW="${JAVA_HOME:-}"
[[ -n "$JAVA_HOME_RAW" ]] || fail "JAVA_HOME must point to the JDK used by Gradle"
JAVA_HOME="$(to_posix_path "$JAVA_HOME_RAW")"
[[ -x "$JAVA_HOME/bin/javac" || -x "$JAVA_HOME/bin/javac.exe" ]] || fail "javac was not found under JAVA_HOME"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

SWIG_BIN="$(command -v swig)"
export SWIG_LIB="${SWIG_LIB:-$($SWIG_BIN -swiglib)}"

PJSIP_REV="$(git -C "$PJSIP_CHECKOUT" rev-parse HEAD)"
MBEDTLS_REV="$(git -C "$MBEDTLS_CHECKOUT" rev-parse HEAD)"
FRAMEWORK_REV="$(git -C "$MBEDTLS_CHECKOUT/framework" rev-parse HEAD)"
SOURCE_MARKER="$WORK_DIR/source-revisions.txt"
SOURCE_ID="$PJSIP_REV\n$MBEDTLS_REV\n$FRAMEWORK_REV"
if [[ ! -f "$SOURCE_MARKER" ]] || [[ "$(cat "$SOURCE_MARKER")" != "$(printf '%b' "$SOURCE_ID")" ]]; then
    rm -rf "$SOURCE_DIR"
    mkdir -p "$SOURCE_DIR"
    cp -a "$PJSIP_CHECKOUT" "$SOURCE_DIR/pjproject"
    cp -a "$MBEDTLS_CHECKOUT" "$SOURCE_DIR/mbedtls"
    rm -rf "$SOURCE_DIR/pjproject/.git" "$SOURCE_DIR/mbedtls/.git"
    rm -rf "$SOURCE_DIR/mbedtls/framework/.git"
    printf '%b\n' "$SOURCE_ID" > "$SOURCE_MARKER"
fi

PJSIP_SOURCE="$SOURCE_DIR/pjproject"
MBEDTLS_SOURCE="$SOURCE_DIR/mbedtls"
MBEDTLS_BUILD="$BUILD_DIR/mbedtls-$ABI"
MBEDTLS_INSTALL="$BUILD_DIR/mbedtls-install-$ABI"
PJSIP_OUTPUT="$PJSIP_SOURCE/pjsip-apps/src/swig/java/android/pjsua2/src/main"
JAVA_OUTPUT="$GENERATED_DIR/src/main/java/org/pjsip"
JNI_OUTPUT="$GENERATED_DIR/src/main/jniLibs/$ABI"

mkdir -p "$BUILD_DIR" "$JAVA_OUTPUT" "$JNI_OUTPUT"

"$PYTHON_BIN" "$MBEDTLS_SOURCE/scripts/config.py" set MBEDTLS_THREADING_C
"$PYTHON_BIN" "$MBEDTLS_SOURCE/scripts/config.py" set MBEDTLS_THREADING_PTHREAD

cmake -S "$MBEDTLS_SOURCE" -B "$MBEDTLS_BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_TOOLCHAIN" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$ANDROID_API" \
    -DCMAKE_BUILD_TYPE=MinSizeRel \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DENABLE_TESTING=OFF \
    -DENABLE_PROGRAMS=OFF \
    -DUSE_SHARED_MBEDTLS_LIBRARY=OFF \
    -DCMAKE_INSTALL_PREFIX="$MBEDTLS_INSTALL"
cmake --build "$MBEDTLS_BUILD" --parallel "$BUILD_JOBS"
cmake --install "$MBEDTLS_BUILD"

cd "$PJSIP_SOURCE"
printf '%s\n' \
    '#define PJ_CONFIG_ANDROID 1' \
    '#include <pj/config_site_sample.h>' \
    '#define PJMEDIA_HAS_VIDEO 0' > pjlib/include/pj/config_site.h

if [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]]; then
    export ANDROID_NDK_ROOT="$NDK_PJSIP_ROOT"
else
    export ANDROID_NDK_ROOT="$NDK_ROOT"
fi
export APP_PLATFORM="$ANDROID_API"
export TARGET_ABI="$ABI"
export PKG_CONFIG_PATH="$MBEDTLS_INSTALL/lib/pkgconfig"
export PKG_CONFIG_LIBDIR="$PKG_CONFIG_PATH"
export CFLAGS="${CFLAGS:-} -D__BIONIC_NO_PAGE_SIZE_MACRO"
export LDFLAGS="${LDFLAGS:-} -Wl,-z,max-page-size=16384"

make distclean >/dev/null 2>&1 || true
./configure-android --use-ndk-cflags --with-mbedtls="$MBEDTLS_INSTALL"
make dep -j"$BUILD_JOBS"
make clean
make -j"$BUILD_JOBS"
make -C pjsip-apps/src/swig

JAVA_SOURCE="$PJSIP_OUTPUT/java/org/pjsip/pjsua2"
JNI_SOURCE="$PJSIP_OUTPUT/jniLibs/$ABI"
[[ -d "$JAVA_SOURCE" ]] || fail "SWIG did not generate the PJSUA2 Java bindings"
[[ -s "$JNI_SOURCE/libpjsua2.so" ]] || fail "PJSUA2 native library was not built for $ABI"
[[ -s "$JNI_SOURCE/libc++_shared.so" ]] || fail "Android C++ runtime was not staged for $ABI"

rm -rf "$JAVA_OUTPUT/pjsua2"
cp -a "$JAVA_SOURCE" "$JAVA_OUTPUT/pjsua2"
rm -f "$JNI_OUTPUT"/*.so
cp -a "$JNI_SOURCE"/*.so "$JNI_OUTPUT/"

printf 'Built stable PJSIP %s with Mbed TLS %s for %s (Android API %s).\n' \
    "$PJSIP_REV" "$MBEDTLS_REV" "$ABI" "$ANDROID_API"
