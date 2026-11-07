#!/usr/bin/env bash
# Builds TDLib with the Java interface for Android from pinned sources (SPEC §2).
#
# Follows tdlib/td example/android (build-tdlib.sh is run as is, apart from one path fix),
# with two deviations: only the ABIs from tdlib.properties are built, and OpenSSL is the
# pinned 3.5 LTS release, downloaded once and checked against its SHA-256.
#
# Inputs:  core/tdlib/tdlib.properties
# Output:  core/tdlib/dist/{libs/<abi>/libtdjni.so, java/, STAMP}
#          build/tdlib-work/tdlib-debug-<version>.zip (unstripped libraries for crash symbolication)
#
# Usage:   scripts/build-tdlib.sh [--check]
#            --check  only verify the toolchain, build nothing
#
# Env:     ANDROID_SDK_ROOT  default: sdk.dir from local.properties
#          JAVA_HOME         default: Android Studio's JBR, if installed in /opt/android-studio
#          TDLIB_WORK_DIR    default: build/tdlib-work (sources, OpenSSL, intermediates; several GB)

set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
PIN="$ROOT/core/tdlib/tdlib.properties"
DIST="$ROOT/core/tdlib/dist"
WORK=${TDLIB_WORK_DIR:-$ROOT/build/tdlib-work}
CHECK_ONLY=false
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=true

die() { echo "Error: $*" >&2; exit 1; }

# Parsed, not sourced: the file is shared with Gradle and must stay plain key=value.
pin() {
  local value
  value=$(grep -E "^$1=" "$PIN" | head -1 | cut -d= -f2-) || true
  [[ -n "$value" ]] || die "$1 is missing in $PIN"
  printf '%s' "$value"
}

TDLIB_COMMIT=$(pin TDLIB_COMMIT)
TDLIB_VERSION=$(pin TDLIB_VERSION)
OPENSSL_VERSION=$(pin OPENSSL_VERSION)
OPENSSL_SHA256=$(pin OPENSSL_SHA256)
NDK_VERSION=$(pin NDK_VERSION)
API=$(pin ANDROID_API_LEVEL)
ABIS=$(pin ABIS | tr ',' ' ')

if [[ -z "${ANDROID_SDK_ROOT:-}" ]]; then
  ANDROID_SDK_ROOT=$(grep -E '^sdk\.dir=' "$ROOT/local.properties" 2>/dev/null | cut -d= -f2-) || true
fi
if [[ -z "${JAVA_HOME:-}" && -x /opt/android-studio/jbr/bin/java ]]; then
  JAVA_HOME=/opt/android-studio/jbr
fi
[[ -n "${ANDROID_SDK_ROOT:-}" ]] || die "set ANDROID_SDK_ROOT or sdk.dir in local.properties"
export ANDROID_SDK_ROOT
if [[ -n "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
fi

NDK_ROOT="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
NDK_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
# The official instructions require exactly this CMake from the SDK.
CMAKE_DIR="$ANDROID_SDK_ROOT/cmake/3.22.1"

check_environment() {
  local problems=()
  [[ "$OSTYPE" == linux* ]] || problems+=("this script supports only Linux")
  [[ -d "$NDK_ROOT" ]] || problems+=("NDK $NDK_VERSION is not installed (SDK Manager > SDK Tools > NDK)")
  [[ -x "$CMAKE_DIR/bin/cmake" ]] || problems+=("CMake 3.22.1 is not installed in the SDK (SDK Manager > SDK Tools > CMake)")
  local tool
  # Superset of what td/example/android/check-environment.sh asks for, plus what this script uses.
  for tool in gperf php perl make git curl tar sha256sum sed unzip yes which c++ java jar javadoc ; do
    command -v "$tool" >/dev/null 2>&1 || problems+=("missing tool: $tool")
  done
  if command -v perl >/dev/null 2>&1 &&
     ! perl -MExtUtils::MakeMaker -MLocale::Maketext::Simple -MPod::Usage -e '' >/dev/null 2>&1 ; then
    problems+=("Perl lacks ExtUtils::MakeMaker, Locale::Maketext::Simple or Pod::Usage")
  fi
  JAVADOC_PLATFORM=$(ls "$ANDROID_SDK_ROOT/platforms" 2>/dev/null | grep -E '^android-[0-9]' | sort -V | tail -1) || true
  [[ -n "$JAVADOC_PLATFORM" ]] || problems+=("no Android platform installed in the SDK")

  if (( ${#problems[@]} )); then
    printf 'Environment is not ready:\n' >&2
    printf '  - %s\n' "${problems[@]}" >&2
    exit 1
  fi
  echo "Environment OK: NDK $NDK_VERSION, CMake 3.22.1, javadoc against $JAVADOC_PLATFORM."
}

fetch_tdlib() {
  local src="$WORK/td"
  if [[ ! -d "$src/.git" ]]; then
    git init -q "$src"
    git -C "$src" remote add origin https://github.com/tdlib/td.git
  fi
  if [[ "$(git -C "$src" rev-parse -q --verify HEAD 2>/dev/null || true)" != "$TDLIB_COMMIT" ]]; then
    echo "Fetching TDLib $TDLIB_COMMIT..."
    git -C "$src" fetch -q --depth 1 origin "$TDLIB_COMMIT"
    git -C "$src" checkout -q --detach FETCH_HEAD
  fi
  # Undo the previous run's patch; untracked build directories stay for incremental rebuilds.
  git -C "$src" checkout -q -- .
  [[ "$(git -C "$src" rev-parse HEAD)" == "$TDLIB_COMMIT" ]] || die "TDLib checkout is not at $TDLIB_COMMIT"
  grep -q "project(TDLib VERSION $TDLIB_VERSION " "$src/CMakeLists.txt" ||
    die "commit $TDLIB_COMMIT is not TDLib $TDLIB_VERSION"
}

openssl_target() {
  case "$1" in
    arm64-v8a)   echo "android-arm64" ;;
    x86_64)      echo "android-x86_64" ;;
    armeabi-v7a) echo "android-arm -D__ARM_MAX_ARCH__=8" ;;
    x86)         echo "android-x86" ;;
    *)           die "unknown ABI $1" ;;
  esac
}

# Same configuration as td/example/android/build-openssl.sh, for the pinned ABIs and API level.
build_openssl() {
  OPENSSL_DIR="$WORK/openssl-$OPENSSL_VERSION-api$API"
  local marker="$OPENSSL_DIR/.built-abis"
  if [[ -f "$marker" && "$(cat "$marker")" == "$ABIS" ]]; then
    echo "OpenSSL $OPENSSL_VERSION already built for: $ABIS"
    return
  fi
  rm -rf "$OPENSSL_DIR"

  local tarball="$WORK/openssl-$OPENSSL_VERSION.tar.gz"
  if [[ ! -f "$tarball" ]]; then
    echo "Downloading OpenSSL $OPENSSL_VERSION..."
    curl -sfL -o "$tarball.part" \
      "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz"
    mv "$tarball.part" "$tarball"
  fi
  echo "$OPENSSL_SHA256  $tarball" | sha256sum -c --quiet - ||
    { rm -f "$tarball"; die "OpenSSL tarball SHA-256 mismatch, deleted it"; }

  local src="$WORK/openssl-$OPENSSL_VERSION"
  rm -rf "$src"
  tar xzf "$tarball" -C "$WORK"

  (
    cd "$src"
    export ANDROID_NDK_ROOT="$NDK_ROOT" ANDROID_NDK_HOME="$NDK_ROOT"
    export PATH="$NDK_BIN:$PATH"
    clang --version >/dev/null 2>&1 || die "failed to run clang from the NDK"
    local abi target ldflags
    for abi in $ABIS ; do
      echo "Building OpenSSL for $abi..."
      target=$(openssl_target "$abi")
      ldflags=""
      [[ "$abi" == *64* ]] && ldflags="-Wl,-z,max-page-size=16384"
      # shellcheck disable=SC2086 # target carries an extra define for armeabi-v7a
      LDFLAGS="$ldflags" ./Configure $target no-shared -U__ANDROID_API__ -D__ANDROID_API__="$API" >/dev/null
      sed -i 's/-O3/-O3 -ffunction-sections -fdata-sections/g' Makefile
      make depend -s >/dev/null
      make -j"$(nproc)" -s build_libs >/dev/null
      mkdir -p "$OPENSSL_DIR/$abi/lib"
      cp libcrypto.a libssl.a "$OPENSSL_DIR/$abi/lib/"
      cp -r include "$OPENSSL_DIR/$abi/"
      make distclean >/dev/null
    done
  )
  rm -rf "$src"
  echo "$ABIS" > "$marker"
}

build_tdlib() {
  local android="$WORK/td/example/android"
  # The official script hardcodes android-34 only for the javadoc classpath.
  sed -i "s|platforms/android-34/android.jar|platforms/$JAVADOC_PLATFORM/android.jar|" "$android/build-tdlib.sh"
  grep -q "platforms/$JAVADOC_PLATFORM/android.jar" "$android/build-tdlib.sh" ||
    die "could not patch the javadoc platform in build-tdlib.sh; did the official script change?"

  echo "Building TDLib $TDLIB_VERSION for: $ABIS (API $API)..."
  (
    cd "$android"
    export PATH="$CMAKE_DIR/bin:$PATH"
    ./build-tdlib.sh "$ANDROID_SDK_ROOT" "$NDK_VERSION" "$OPENSSL_DIR" c++_static Java "$API" "$ABIS"
  )
}

# Independent of the build scripts: read the ELF headers back with the NDK's readelf.
verify_libs() {
  local libs="$1" abi so machine
  for abi in $ABIS ; do
    so="$libs/$abi/libtdjni.so"
    [[ -s "$so" ]] || die "missing $so"
    case "$abi" in
      arm64-v8a)   machine="AArch64" ;;
      x86_64)      machine="Advanced Micro Devices X86-64" ;;
      armeabi-v7a) machine="ARM" ;;
      x86)         machine="Intel 80386" ;;
    esac
    "$NDK_BIN/llvm-readelf" -h "$so" | grep -q "Machine: *$machine\$" || die "$so is not built for $abi"
    if [[ "$abi" == *64* ]]; then
      # Android 15+ devices with 16 KB pages refuse libraries whose LOAD segments are aligned to less.
      "$NDK_BIN/llvm-readelf" -lW "$so" | awk '$1 == "LOAD" && $NF != "0x4000" && $NF != "0x10000" { bad = 1 } END { exit bad }' ||
        die "$so is not 16 KB page aligned"
    fi
  done
}

install_dist() {
  local out="$WORK/td/example/android/tdlib"
  verify_libs "$out/libs"
  rm -rf "$DIST.tmp"
  mkdir -p "$DIST.tmp/java"
  local abi
  for abi in $ABIS ; do
    mkdir -p "$DIST.tmp/libs/$abi"
    cp "$out/libs/$abi/libtdjni.so" "$DIST.tmp/libs/$abi/"
  done
  cp -r "$out/java/." "$DIST.tmp/java/"
  # Same keys and values as tdlib.properties; core/tdlib compares them before every build.
  grep -E '^[A-Z0-9_]+=' "$PIN" > "$DIST.tmp/STAMP"
  rm -rf "$DIST"
  mv "$DIST.tmp" "$DIST"
  cp "$out/tdlib-debug.zip" "$WORK/tdlib-debug-$TDLIB_VERSION.zip"
}

main() {
  check_environment
  $CHECK_ONLY && exit 0

  mkdir -p "$WORK"
  fetch_tdlib
  build_openssl
  build_tdlib
  install_dist
  echo "Done: $DIST"
}

# Sourcing the script (for testing its functions) does not start a build.
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main
fi
