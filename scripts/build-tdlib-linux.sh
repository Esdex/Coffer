#!/usr/bin/env bash
# Builds TDLib with the Java interface for desktop Linux (x86_64) from the same pinned sources as
# the Android build (scripts/build-tdlib.sh, SPEC §2).
#
# Follows tdlib/td build.html for Java on Linux: TDLib is built with JNI and installed into
# example/java/td, then example/java builds libtdjni.so against it. Deviations: OpenSSL is the
# pinned 3.5 LTS release linked statically (checked against its SHA-256), the compiler is clang,
# and the Java API gets the same @IntDef annotations as Android's (td/example/android/AddIntDef.php),
# so both apps compile against one TdApi.java.
#
# Inputs:  core/tdlib/tdlib.properties (TDLIB_*, OPENSSL_*)
# Output:  core/tdlib/dist-desktop/{linux-x86_64/libtdjni.so, java/, STAMP}
#
# The library links zlib and the C++ runtime from the system, as Arch packages do; OpenSSL is inside,
# its symbols and TDLib's hidden, so only the JNI entry point is exported. The machine's paths are
# mapped away and the linker writes no build ID, as on Android.
#
# Usage:   scripts/build-tdlib-linux.sh [--check]
#            --check  only verify the toolchain, build nothing
#
# Env:     JAVA_HOME       a JDK with include/jni.h; default: /usr/lib/jvm/default
#          TDLIB_JOBS      parallel compile jobs; default: nproc. TDLib's sources take 1 to 2 GB of
#                          RAM per job, so lower it on a machine with little memory.
#          TDLIB_WORK_DIR  default: build/tdlib-work (shared with the Android build: same TDLib checkout)

set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
PIN="$ROOT/core/tdlib/tdlib.properties"
DIST="$ROOT/core/tdlib/dist-desktop"
WORK=${TDLIB_WORK_DIR:-$ROOT/build/tdlib-work}
PLATFORM=linux-x86_64
JOBS=${TDLIB_JOBS:-$(nproc)}
CHECK_ONLY=false
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=true

die() { echo "Error: $*" >&2; exit 1; }

# The same parser as build-tdlib.sh: the file is shared with Gradle and must stay plain key=value.
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

# Android Studio's JBR, which Gradle runs on, has no JNI headers: fall back to the system JDK.
if [[ ! -f "${JAVA_HOME:-}/include/jni.h" ]]; then
  JAVA_HOME=/usr/lib/jvm/default
fi
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

OUT="$WORK/$PLATFORM"

check_environment() {
  local problems=()
  [[ "$OSTYPE" == linux* ]] || problems+=("this script builds on Linux only")
  [[ "$(uname -m)" == x86_64 ]] || problems+=("this script builds for x86_64 only")
  [[ -f "$JAVA_HOME/include/jni.h" ]] || problems+=("no JNI headers in $JAVA_HOME; set JAVA_HOME to a full JDK")
  local tool
  for tool in cmake gperf php perl make clang clang++ git curl tar sha256sum readelf nm strings java javac javadoc ; do
    command -v "$tool" >/dev/null 2>&1 || problems+=("missing tool: $tool")
  done
  [[ -f /usr/include/zlib.h ]] || problems+=("missing zlib headers")

  if (( ${#problems[@]} )); then
    printf 'Environment is not ready:\n' >&2
    printf '  - %s\n' "${problems[@]}" >&2
    exit 1
  fi
  echo "Environment OK: $(clang --version | head -1), JDK $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)."
}

# As in build-tdlib.sh; this script patches nothing in the checkout.
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
  git -C "$src" checkout -q -- .
  [[ "$(git -C "$src" rev-parse HEAD)" == "$TDLIB_COMMIT" ]] || die "TDLib checkout is not at $TDLIB_COMMIT"
  grep -q "project(TDLib VERSION $TDLIB_VERSION " "$src/CMakeLists.txt" ||
    die "commit $TDLIB_COMMIT is not TDLib $TDLIB_VERSION"
}

build_openssl() {
  OPENSSL_DIR="$OUT/openssl-$OPENSSL_VERSION"
  if [[ -f "$OPENSSL_DIR/lib/libcrypto.a" && -f "$OPENSSL_DIR/lib/libssl.a" ]]; then
    echo "OpenSSL $OPENSSL_VERSION already built for $PLATFORM"
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

  local src="$OUT/openssl-$OPENSSL_VERSION-src"
  rm -rf "$src"
  mkdir -p "$src"
  tar xzf "$tarball" -C "$src" --strip-components=1

  echo "Building OpenSSL $OPENSSL_VERSION for $PLATFORM..."
  (
    cd "$src"
    # OpenSSL writes its build date into the library unless given one: the TDLib commit's, as on Android.
    SOURCE_DATE_EPOCH=$(git -C "$WORK/td" log -1 --format=%ct)
    export SOURCE_DATE_EPOCH CC=clang
    # Static and position-independent, to be linked into libtdjni.so.
    ./Configure linux-x86_64 no-shared no-tests -fPIC >/dev/null
    make -j"$JOBS" -s build_libs >/dev/null
    mkdir -p "$OPENSSL_DIR/lib"
    cp libcrypto.a libssl.a "$OPENSSL_DIR/lib/"
    cp -r include "$OPENSSL_DIR/"
  )
  rm -rf "$src"
}

build_tdlib() {
  local td="$WORK/td" work jdk flags ldflags
  work=$(cd "$WORK" && pwd -P)
  jdk=$(cd "$JAVA_HOME" && pwd -P)
  # TDLib logs __FILE__: source paths under the work directory and the JDK become fixed names.
  flags="-ffile-prefix-map=$work=/coffer-tdlib -ffile-prefix-map=$jdk=/jdk"
  # Without --exclude-libs every symbol of the static TDLib and OpenSSL is exported (61 363 of them),
  # free to clash with other native libraries in the JVM's process. -s drops the symbol table: 82 MB
  # unstripped.
  ldflags="-Wl,--build-id=none -Wl,--exclude-libs,ALL -s"

  echo "Building TDLib $TDLIB_VERSION for $PLATFORM with $JOBS jobs..."
  # example/java looks for TDLib in example/java/td, so it is installed there, fresh each time.
  rm -rf "$td/example/java/td"
  cmake -S "$td" -B "$OUT/td-build" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ \
    -DCMAKE_C_FLAGS="$flags" -DCMAKE_CXX_FLAGS="$flags" \
    -DCMAKE_INSTALL_PREFIX:PATH="$td/example/java/td" \
    -DTD_ENABLE_JNI=ON \
    -DOPENSSL_ROOT_DIR="$OPENSSL_DIR" -DOPENSSL_USE_STATIC_LIBS=TRUE >/dev/null
  cmake --build "$OUT/td-build" --target install -j "$JOBS" >/dev/null

  echo "Building libtdjni.so and the Java API..."
  rm -rf "$OUT/jni-install"
  cmake -S "$td/example/java" -B "$OUT/jni-build" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_COMPILER=clang++ \
    -DCMAKE_CXX_FLAGS="$flags" -DCMAKE_SHARED_LINKER_FLAGS="$ldflags" \
    -DCMAKE_INSTALL_PREFIX:PATH="$OUT/jni-install" \
    -DTd_DIR:PATH="$td/example/java/td/lib/cmake/Td" >/dev/null
  cmake --build "$OUT/jni-build" --target install -j "$JOBS" >/dev/null

  # TdApi.java again, with the commands of td/example/android/CMakeLists.txt: example/java's has no
  # androidx @Nullable and @IntDef, Android's has. Generated the same way, both apps share one file.
  local bin="$td/example/java/td/bin"
  JAVA_OUT="$OUT/java"
  rm -rf "$JAVA_OUT"
  mkdir -p "$JAVA_OUT/org/drinkless/tdlib"
  local api="$JAVA_OUT/org/drinkless/tdlib/TdApi.java"
  "$bin/td_generate_java_api" TdApi "$bin/td/generate/scheme/td_api.tlo" "$JAVA_OUT" org/drinkless/tdlib
  php "$bin/td/generate/JavadocTlDocumentationGenerator.php" "$bin/td/generate/scheme/td_api.tl" "$api" \
    androidx.annotation.Nullable @Nullable
  php "$td/example/android/AddIntDef.php" "$api"
  cp "$td/example/java/org/drinkless/tdlib/Client.java" "$JAVA_OUT/org/drinkless/tdlib/"
}

# Independent of the build: read the library back with binutils, then load it in a JVM.
verify() {
  local so="$OUT/jni-install/bin/libtdjni.so"
  [[ -s "$so" ]] || die "missing $so"
  readelf -h "$so" | grep -q "Machine: *Advanced Micro Devices X86-64\$" || die "$so is not x86_64"
  readelf -h "$so" | grep -q "Type: *DYN" || die "$so is not a shared library"
  # OpenSSL must be inside, not a system dependency.
  if readelf -d "$so" | grep -E 'NEEDED.*lib(ssl|crypto)\.so'; then die "$so links OpenSSL dynamically"; fi
  if readelf -n "$so" | grep -q "Build ID"; then die "$so has a build ID"; fi
  if strings -a "$so" | grep -qF "$(cd "$WORK" && pwd -P)"; then die "$so contains the work directory's path"; fi
  # Java finds the natives through JNI_OnLoad; nothing else is for others to call.
  local exported
  exported=$(nm -D --defined-only "$so" | awk '$2 == "T" { print $3 }')
  [[ "$exported" == "JNI_OnLoad" ]] || die "$so exports more than JNI_OnLoad: $(wc -l <<<"$exported") functions"

  # Android's TdApi.java, when it is built, must be the same file.
  local android="$ROOT/core/tdlib/dist/java/org/drinkless/tdlib"
  if [[ -f "$android/TdApi.java" ]]; then
    cmp -s "$android/TdApi.java" "$JAVA_OUT/org/drinkless/tdlib/TdApi.java" || die "TdApi.java differs from Android's"
    cmp -s "$android/Client.java" "$JAVA_OUT/org/drinkless/tdlib/Client.java" || die "Client.java differs from Android's"
    echo "Java API identical to Android's."
  fi

  # Load the library and ask TDLib for its version: no network, no account. The classes are the ones
  # example/java compiled from the unannotated TdApi.java (@IntDef is not kept in class files).
  local smoke="$OUT/smoke"
  mkdir -p "$smoke"
  cat > "$smoke/Smoke.java" <<'EOF'
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

public class Smoke {
    public static void main(String[] args) throws Exception {
        Client.execute(new TdApi.SetLogVerbosityLevel(0));
        TdApi.Object version = Client.execute(new TdApi.GetOption("version"));
        System.out.println(((TdApi.OptionValueString) version).value);
    }
}
EOF
  local got
  got=$(java --enable-native-access=ALL-UNNAMED -Djava.library.path="$(dirname "$so")" -cp "$OUT/jni-install/bin" "$smoke/Smoke.java")
  [[ "$got" == "$TDLIB_VERSION" ]] || die "the library reports TDLib '$got', expected $TDLIB_VERSION"
  echo "Loaded in a JVM: TDLib $got."
}

install_dist() {
  rm -rf "$DIST.tmp"
  mkdir -p "$DIST.tmp/$PLATFORM"
  cp "$OUT/jni-install/bin/libtdjni.so" "$DIST.tmp/$PLATFORM/"
  cp -r "$JAVA_OUT" "$DIST.tmp/java"
  # The inputs this build used, in tdlib.properties' format, for Gradle to compare.
  grep -E '^(TDLIB|OPENSSL)_[A-Z0-9_]*=' "$PIN" > "$DIST.tmp/STAMP"
  rm -rf "$DIST"
  mv "$DIST.tmp" "$DIST"
}

main() {
  check_environment
  $CHECK_ONLY && exit 0

  mkdir -p "$OUT"
  fetch_tdlib
  build_openssl
  build_tdlib
  verify
  install_dist
  echo "Done: $DIST"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main
fi
