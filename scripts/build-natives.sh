#!/usr/bin/env bash
# Copyright (C) 2026 Karol Krawczyk
# SPDX-License-Identifier: GPL-3.0-only
# Builds whisper.cpp and the whisper-jni bridge from pinned source.
#
#   ./scripts/build-natives.sh            for this Mac's architecture
#   ./scripts/build-natives.sh x86_64     for the other one (needs a compiler for it)
#
# Output: build/whisper-natives/<arch>/{libwhisper-jni,libwhisper.1,libggml}.dylib.
# build-app.sh uses them instead of the ones inside the whisper-jni jar when
# VISKLY_WHISPER_NATIVES points there.
#
# Why: whisper-jni ships these three libraries prebuilt by its maintainer's CI. Signed with
# our Developer ID they would carry the signature without having been built by us. Built
# here, from the two commits below, what went into them can be checked by anyone.
#
# Needs: git, cmake, Xcode command line tools, a JDK for the JNI headers (JAVA_HOME, or
# whatever java_home finds for 25).

set -euo pipefail

WHISPER_JNI_REPO="https://github.com/GiviMAD/whisper-jni.git"
WHISPER_JNI_TAG="v1.7.1"
WHISPER_JNI_COMMIT="1b537f459d299d5c3ce4e12306aceac75009c253"
# The submodule at that tag: whisper.cpp v1.7.1. It has to match the Java side of
# whisper-jni 1.7.1 in pom.xml, whose native method signatures these libraries implement.
WHISPER_CPP_COMMIT="ebca09a3d1033417b0c630bbbe607b0f185b1488"
# The same floor as upstream's build_macos.sh.
DEPLOYMENT_TARGET="11.0"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CMAKE="${CMAKE:-cmake}"

ARCH="${1:-$(uname -m)}"
case "${ARCH}" in
  arm64|aarch64) ARCH="arm64" ;;
  x86_64|amd64) ARCH="x86_64" ;;
  *) echo "Unknown architecture: ${ARCH}"; exit 1 ;;
esac

command -v "${CMAKE}" >/dev/null || { echo "No cmake. Install it, or set CMAKE=/path/to/cmake."; exit 1; }
if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
fi
[[ -f "${JAVA_HOME}/include/jni.h" ]] || { echo "No JNI headers under JAVA_HOME=${JAVA_HOME}"; exit 1; }
export JAVA_HOME

# Outside target/: build-app.sh starts with mvn clean, which would delete the result.
SRC="${ROOT}/build/whisper-jni-src"
OUT="${ROOT}/build/whisper-natives/${ARCH}"

echo "==> Fetching whisper-jni ${WHISPER_JNI_TAG}"
rm -rf "${SRC}"
git clone --quiet --depth 1 --branch "${WHISPER_JNI_TAG}" "${WHISPER_JNI_REPO}" "${SRC}"
# A tag can be moved; the commit cannot. Building from anything else defeats the point.
actual="$(git -C "${SRC}" rev-parse HEAD)"
if [[ "${actual}" != "${WHISPER_JNI_COMMIT}" ]]; then
  echo "whisper-jni ${WHISPER_JNI_TAG} is now ${actual}, expected ${WHISPER_JNI_COMMIT}."
  exit 1
fi
git -C "${SRC}" submodule update --quiet --init --depth 1
actual="$(git -C "${SRC}/src/main/native/whisper" rev-parse HEAD)"
if [[ "${actual}" != "${WHISPER_CPP_COMMIT}" ]]; then
  echo "whisper.cpp submodule is ${actual}, expected ${WHISPER_CPP_COMMIT}."
  exit 1
fi

echo "==> Building for ${ARCH}, macOS ${DEPLOYMENT_TARGET} and later"
"${CMAKE}" -S "${SRC}" -B "${SRC}/build" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="${SRC}/install" \
  -DCMAKE_OSX_DEPLOYMENT_TARGET="${DEPLOYMENT_TARGET}" \
  -DCMAKE_OSX_ARCHITECTURES="${ARCH}" >/dev/null
"${CMAKE}" --build "${SRC}/build" --config Release -j "$(sysctl -n hw.ncpu)" >/dev/null
"${CMAKE}" --install "${SRC}/build" >/dev/null

rm -rf "${OUT}"
mkdir -p "${OUT}"
for lib in libwhisper-jni.dylib libwhisper.1.dylib libggml.dylib; do
  # libwhisper.1.dylib is installed as a symlink to libwhisper.1.7.1.dylib; the loader
  # asks for the .1 name, so the file behind it is copied under that name.
  built="$(find "${SRC}/install" -name "${lib}" \( -type f -o -type l \) | head -1)"
  [[ -n "${built}" ]] || { echo "The build produced no ${lib}."; exit 1; }
  cp -L "${built}" "${OUT}/${lib}"
  # Everything they link has to be in the same directory or part of macOS; the bundle
  # carries nothing else.
  if otool -L "${OUT}/${lib}" | tail -n +2 | awk '{print $1}' \
       | grep -vE '^(/usr/lib/|/System/|@rpath/|@loader_path/)' | grep -q .; then
    echo "${lib} links outside the bundle:"
    otool -L "${OUT}/${lib}"
    exit 1
  fi
  lipo -archs "${OUT}/${lib}" | grep -qx "${ARCH}" || { echo "${lib} is not ${ARCH}."; exit 1; }
done

echo "==> ${OUT}"
(cd "${OUT}" && shasum -a 256 ./*.dylib | sed 's/^/    /')
