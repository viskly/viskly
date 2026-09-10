#!/usr/bin/env bash
# Copyright (C) 2026 Karol Krawczyk
# SPDX-License-Identifier: GPL-3.0-only
# Builds Viskly.app — a self-contained macOS application with its own Java runtime.
# The user needs neither Java nor Maven installed.
#
#   ./scripts/build-app.sh              build into target/dist
#   ./scripts/build-app.sh --install    build and copy to /Applications
#   ./scripts/build-app.sh --install --login   also launch it at login
#   ./scripts/build-app.sh --dmg        also pack target/dist/Viskly-<version>-<arch>.dmg
#
# Signing, from the environment:
#
#   VISKLY_SIGN_IDENTITY   "Developer ID Application: ..." from the keychain. Unset means
#                          an ad-hoc signature, which runs on this machine and nowhere else.
#   VISKLY_NOTARY_PROFILE  a notarytool keychain profile (xcrun notarytool
#                          store-credentials). Set, the disk image is notarised and stapled,
#                          which is what lets Gatekeeper open it on other Macs.
#   VISKLY_WHISPER_NATIVES a directory from build-natives.sh. Set, the whisper libraries
#                          built there replace the prebuilt ones from the whisper-jni jar,
#                          which are then dropped rather than signed. Releases set it.
#   VISKLY_JDK             the JDK 25 home to build with, when java_home would pick another.
#                          Anything meant for other Macs needs one whose libraries link
#                          nothing outside itself, such as Temurin; Homebrew's does not.
#
# IMPORTANT about permissions: macOS ties the Accessibility and Input Monitoring consents
# to the signature. Ad-hoc, that is the hash of this exact build, so EVERY rebuild looks
# like a new application and wipes them. With a Developer ID it is the team, and they
# survive updates.

set -euo pipefail

APP="Viskly"
BUNDLE_ID="com.viskly.app"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="${ROOT}/target/dist"
STAGE="${ROOT}/target/jpackage-input"
ENTITLEMENTS="${ROOT}/scripts/entitlements.plist"

INSTALL=false
LOGIN=false
DMG=false
for arg in "$@"; do
  case "$arg" in
    --install) INSTALL=true ;;
    --login) LOGIN=true ;;
    --dmg) DMG=true ;;
    *) echo "Unknown argument: $arg"; exit 1 ;;
  esac
done

if [[ "$(uname)" != "Darwin" ]]; then
  echo "This script builds a macOS package and only runs on macOS."
  exit 1
fi

IDENTITY="${VISKLY_SIGN_IDENTITY:--}"
NOTARY_PROFILE="${VISKLY_NOTARY_PROFILE:-}"
OWN_WHISPER="${VISKLY_WHISPER_NATIVES:-}"
if [[ -n "${OWN_WHISPER}" ]]; then
  OWN_WHISPER="$(cd "${OWN_WHISPER}" && pwd)" || { echo "No directory ${VISKLY_WHISPER_NATIVES}"; exit 1; }
fi
# Both checked before the build rather than after it: finding out at the signing step
# costs a full jpackage run, and on a CI runner a few minutes of macOS time.
if [[ "${IDENTITY}" != "-" ]] && ! security find-identity -v -p codesigning | grep -qF "${IDENTITY}"; then
  echo "No signing identity matching \"${IDENTITY}\" in the keychain. Available:"
  security find-identity -v -p codesigning | sed 's/^/    /'
  exit 1
fi
if [[ -n "${NOTARY_PROFILE}" ]] && [[ "${IDENTITY}" == "-" || "${DMG}" != true ]]; then
  echo "Notarisation needs a Developer ID in VISKLY_SIGN_IDENTITY and --dmg."
  exit 1
fi
# Once a signed (not ad-hoc) Viskly has been opened, macOS's App Management protection
# stops other programs, this terminal included, from changing or deleting it, and the
# install step's rm -rf would fail file by file halfway through. Checked here, before a
# build that could not be installed anyway, and before the running copy is quit.
if [[ "${INSTALL}" == true && -d "/Applications/${APP}.app" ]]; then
  probe="/Applications/${APP}.app/Contents/.viskly-write-probe"
  if ! touch "${probe}" 2>/dev/null; then
    echo "macOS protects the installed ${APP} from being replaced by this terminal."
    echo "Either allow the terminal once in Settings > Privacy & Security > App Management,"
    echo "or build without --install and drag target/dist/${APP}.app onto /Applications in"
    echo "Finder, choosing Replace."
    exit 1
  fi
  rm -f "${probe}"
fi

# Pin the JDK explicitly instead of trusting PATH.
#
# jpackage bundles the runtime of whichever JDK runs it, and the jpackage on PATH is not
# necessarily the one JAVA_HOME points at. When they disagree the build succeeds and the
# application then dies on first launch with UnsupportedClassVersionError, because the
# classes were compiled by one JDK and the bundled runtime is another.
is_jdk25() {
  [[ -x "$1/bin/java" ]] && "$1/bin/java" -version 2>&1 | head -1 | grep -qE '"25(\.|")'
}
JDK=""
if [[ -n "${VISKLY_JDK:-}" ]]; then
  is_jdk25 "${VISKLY_JDK}" || { echo "VISKLY_JDK=${VISKLY_JDK} is not a JDK 25."; exit 1; }
  JDK="${VISKLY_JDK}"
else
  JDK="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
fi
# A CI runner's setup-java installs into its tool cache, where java_home does not look.
# JAVA_HOME is taken then, but only once it has proved to be a 25.
if [[ -z "${JDK}" && -n "${JAVA_HOME:-}" ]] && is_jdk25 "${JAVA_HOME}"; then
  JDK="${JAVA_HOME}"
fi
if [[ -z "${JDK}" ]]; then
  echo "No JDK 25 found. Install it with: brew install openjdk@25"
  echo "Installed JDKs:"
  /usr/libexec/java_home -V 2>&1 | sed 's/^/    /'
  exit 1
fi
export JAVA_HOME="${JDK}"
JAVA="${JDK}/bin/java"
JPACKAGE="${JDK}/bin/jpackage"
[[ -x "${JPACKAGE}" ]] || { echo "No jpackage in ${JDK}/bin"; exit 1; }
echo "==> JDK: $("${JAVA}" -version 2>&1 | head -1)"
echo "    signing as: ${IDENTITY/#-/ad-hoc}"

# jpackage bundles this JDK's own runtime, so the natives shipped beside it have to be the
# ones for the same architecture. The names are those the two libraries use in their jars.
case "$(sed -nE 's/^OS_ARCH="([^"]+)"$/\1/p' "${JDK}/release")" in
  aarch64) WHISPER_NATIVES="macos-arm64"; SQLITE_NATIVES="Mac/aarch64"; JDK_ARCH="arm64" ;;
  x86_64) WHISPER_NATIVES="macos-amd64"; SQLITE_NATIVES="Mac/x86_64"; JDK_ARCH="x86_64" ;;
  *) echo "Unknown architecture in ${JDK}/release"; exit 1 ;;
esac
if [[ -n "${OWN_WHISPER}" ]]; then
  for lib in libwhisper-jni.dylib libwhisper.1.dylib libggml.dylib; do
    [[ -f "${OWN_WHISPER}/${lib}" ]] || { echo "No ${lib} in ${OWN_WHISPER}"; exit 1; }
    if ! lipo -archs "${OWN_WHISPER}/${lib}" | grep -qw "${JDK_ARCH}"; then
      echo "${OWN_WHISPER}/${lib} is not built for ${JDK_ARCH}, the JDK's architecture."
      exit 1
    fi
  done
  echo "    whisper natives: own build, ${OWN_WHISPER#"${ROOT}"/}"
fi

# Hardened runtime on every build, ad-hoc ones included. Notarisation requires it, and a
# JVM under it needs the exceptions in entitlements.plist; a missing one (the microphone,
# say) should fail on this machine today, not in the first release.
sign() {
  local timestamp="--timestamp" out
  [[ "${IDENTITY}" == "-" ]] && timestamp="--timestamp=none"
  # codesign reports every re-signed file on stderr; kept only when it actually failed.
  if ! out="$(codesign --force --options runtime "${timestamp}" --sign "${IDENTITY}" "$@" 2>&1)"; then
    echo "${out}" >&2
    return 1
  fi
}

# "executable", "dynamically linked shared library" and "bundle" are the Mach-O kinds a
# JDK ships; anything else in the runtime is data.
macho_kind() {
  file -b "$1" | sed -nE 's/^Mach-O 64-bit (executable|dynamically linked shared library|bundle).*/\1/p'
}

echo "==> Building the jar"
cd "${ROOT}"
mvn -q clean package -DskipTests

JAR="$(find "${ROOT}/target" -maxdepth 1 -name '*.jar' ! -name '*.original' | head -1)"
[[ -n "${JAR}" ]] || { echo "No built jar found in target/"; exit 1; }

# jpackage on macOS wants a numeric version — "0.1.0-SNAPSHOT" is rejected.
PROJECT_VERSION="$(basename "${JAR}" .jar | sed -E 's/^.*-([0-9]+(\.[0-9]+)*).*$/\1/')"
[[ -n "${PROJECT_VERSION}" ]] || PROJECT_VERSION="0.1.0"

# CFBundleVersion, which is what jpackage fills from --app-version, must not start with
# zero — macOS rejects the bundle outright ("The first number in an app-version cannot be
# zero or negative"). CFBundleShortVersionString, the one a user actually sees, has no such
# rule, so the real version goes there further down and only the internal number is faked.
BUNDLE_VERSION="${PROJECT_VERSION}"
if [[ "${BUNDLE_VERSION}" =~ ^0(\.|$) ]]; then
  BUNDLE_VERSION="1.0.0"
  echo "    app-version ${PROJECT_VERSION} -> ${BUNDLE_VERSION} (macOS forbids a leading zero)"
fi

echo "==> Preparing the icon"
ICONSET="${ROOT}/target/${APP}.iconset"
ICNS="${ROOT}/target/${APP}.icns"
rm -rf "${ICONSET}"; mkdir -p "${ICONSET}"
for size in 16 32 128 256 512; do
  sips -z $size $size "${ROOT}/scripts/icon.png" \
       --out "${ICONSET}/icon_${size}x${size}.png" >/dev/null
  sips -z $((size*2)) $((size*2)) "${ROOT}/scripts/icon.png" \
       --out "${ICONSET}/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "${ICONSET}" -o "${ICNS}"

rm -rf "${DIST}" "${STAGE}"
mkdir -p "${DIST}" "${STAGE}"
cp "${JAR}" "${STAGE}/"
FAT="${STAGE}/$(basename "${JAR}")"

echo "==> Signing the native libraries inside the jar"
# whisper-jni and sqlite-jdbc keep their macOS libraries inside their own jars, which sit
# inside ours, and ship them unsigned or ad-hoc signed. Notarisation opens archives and
# rejects any binary in them without a Developer ID signature, so they are taken out,
# signed and put back before jpackage seals the bundle.
NATIVES="${ROOT}/target/jar-natives"
rm -rf "${NATIVES}"; mkdir -p "${NATIVES}" "${STAGE}/natives"
while IFS= read -r nested; do
  unzip -q -o "${FAT}" "${nested}" -d "${NATIVES}"
  libs=()
  while IFS= read -r lib; do
    libs+=("${lib}")
  done < <(unzip -Z1 "${NATIVES}/${nested}" | grep -E '\.(dylib|jnilib)$' || true)
  [[ ${#libs[@]} -gt 0 ]] || continue

  if [[ -n "${OWN_WHISPER}" && "${nested}" == */whisper-jni-*.jar ]]; then
    # Replaced by the libraries build-natives.sh made from pinned source. The prebuilt
    # ones are removed rather than signed: nothing we did not build carries our signature.
    zip -q -d "${NATIVES}/${nested}" "${libs[@]}"
    (cd "${NATIVES}" && zip -q -0 "${FAT}" "${nested}")
    for lib in libwhisper-jni.dylib libwhisper.1.dylib libggml.dylib; do
      cp "${OWN_WHISPER}/${lib}" "${STAGE}/natives/"
    done
    echo "    ${nested##*/}: ${#libs[@]} prebuilt libraries dropped, own build from ${OWN_WHISPER#"${ROOT}"/}"
    continue
  fi

  mkdir -p "${NATIVES}/unpacked"
  (cd "${NATIVES}/unpacked" && unzip -q -o "${NATIVES}/${nested}" "${libs[@]}")
  for lib in "${libs[@]}"; do
    sign "${NATIVES}/unpacked/${lib}"
  done
  (cd "${NATIVES}/unpacked" && zip -q "${NATIVES}/${nested}" "${libs[@]}")
  # -0: Spring Boot reads nested jars in place and refuses one that is compressed.
  (cd "${NATIVES}" && zip -q -0 "${FAT}" "${nested}")
  # The copies the application actually loads: sealed inside the bundle rather than
  # extracted to a temporary directory at every start, where another process of the same
  # user could swap them before they are loaded. See the java-options further down.
  for lib in "${libs[@]}"; do
    case "${lib}" in
      "${WHISPER_NATIVES}"/*|org/sqlite/native/"${SQLITE_NATIVES}"/*)
        cp "${NATIVES}/unpacked/${lib}" "${STAGE}/natives/" ;;
    esac
  done
  rm -rf "${NATIVES}/unpacked"
  echo "    ${nested##*/}: ${#libs[@]} libraries"
done < <(unzip -Z1 "${FAT}" 'BOOT-INF/lib/*.jar')
rm -rf "${NATIVES}"
for lib in libwhisper-jni.dylib libwhisper.1.dylib libggml.dylib libsqlitejdbc.dylib; do
  [[ -f "${STAGE}/natives/${lib}" ]] || { echo "No ${lib} for ${WHISPER_NATIVES} in the jars."; exit 1; }
done

echo "==> Assembling the app image"
# Every JDK module rather than a computed set. Spring reaches for reflection in places
# jdeps cannot see, so trimming here ends in a ClassNotFoundException on the user's
# machine, not on yours. Slimming down is a job for later, once there is something to
# measure what is actually used. The two exceptions are tools, not runtime: a JDK without
# packaged modules (Temurin 25 is one) refuses to put jdk.jlink into another image, and
# jdk.jpackage depends on it.
MODULES="$("${JAVA}" --list-modules | cut -d'@' -f1 | grep -vE '^jdk\.(jlink|jpackage)$' | paste -sd, -)"

# The JVM options, since a comment cannot sit inside the command below:
#   whisperjni.libdir, org.sqlite.lib.*: load the natives from the bundle (see above).
#   DisableAttachMechanism, -EnableDynamicAgentLoading: a process of the same user could
#     otherwise attach to this JVM and run code with its Input Monitoring, Accessibility
#     and microphone consents. It also means jcmd and jstack cannot reach a packaged build;
#     debug with mvn spring-boot:run.
# $APPDIR stays literal here: the launcher expands it to Contents/app at every start.
# shellcheck disable=SC2016
"${JPACKAGE}" \
  --type app-image \
  --name "${APP}" \
  --app-version "${BUNDLE_VERSION}" \
  --input "${STAGE}" \
  --main-jar "$(basename "${JAR}")" \
  --dest "${DIST}" \
  --icon "${ICNS}" \
  --add-modules "${MODULES}" \
  --java-options "--enable-native-access=ALL-UNNAMED" \
  --java-options "-Dapple.awt.UIElement=true" \
  --java-options "-Xss4m" \
  --java-options '-Dio.github.givimad.whisperjni.libdir=$APPDIR/natives' \
  --java-options '-Dorg.sqlite.lib.path=$APPDIR/natives' \
  --java-options "-Dorg.sqlite.lib.name=libsqlitejdbc.dylib" \
  --java-options "-XX:+DisableAttachMechanism" \
  --java-options "-XX:-EnableDynamicAgentLoading"

BUNDLE="${DIST}/${APP}.app"
PLIST="${BUNDLE}/Contents/Info.plist"
RUNTIME="${BUNDLE}/Contents/runtime"
# What the bundled runtime runs on, taken from the JVM itself rather than from uname: the
# disk image name has to match the binaries inside, whatever machine built them.
ARCH="$(lipo -archs "${RUNTIME}/Contents/Home/lib/server/libjvm.dylib")"

echo "==> Checking what the bundle links against"
# Homebrew's openjdk links its font, image and colour code to /opt/homebrew. A bundle made
# from it starts on this Mac and on no Mac without those packages, and nothing says so:
# only switching library validation on revealed it. So every binary is checked here.
external=""
while IFS= read -r -d '' binary; do
  [[ -n "$(macho_kind "${binary}")" ]] || continue
  own_id="$(otool -D "${binary}" | tail -n +2)"
  while IFS= read -r dep; do
    [[ "${dep}" == "${own_id}" ]] && continue
    external+="    ${binary#"${BUNDLE}"/} -> ${dep}"$'\n'
  done < <(otool -L "${binary}" | tail -n +2 | awk '{print $1}' \
             | grep -vE '^(/usr/lib/|/System/|@rpath/|@loader_path/|@executable_path/)' || true)
done < <(find "${BUNDLE}" -type f -print0)
if [[ -n "${external}" ]]; then
  printf '%s' "${external}"
  if [[ "${IDENTITY}" != "-" || "${DMG}" == true ]]; then
    echo "The bundle links against libraries outside itself and macOS, so it would not start"
    echo "on a Mac without them. Build with a self-contained JDK: VISKLY_JDK=<Temurin 25 home>."
    exit 1
  fi
  echo "    Runs on this Mac only. Anything for another Mac needs a self-contained JDK."
else
  echo "    nothing outside the bundle and macOS"
fi

# Licence texts of what the bundle carries, beside the code they cover.
cp "${ROOT}/THIRD-PARTY-NOTICES.md" "${BUNDLE}/Contents/Resources/"

echo "==> Filling in Info.plist"
# The keys are added afterwards rather than through --resource-dir with our own template:
# a template would need updating on every jpackage change, whereas here we add exactly
# what is missing and everything else stays as the JDK generated it.
plist_set() {
  local key="$1" type="$2" value="$3"
  /usr/libexec/PlistBuddy -c "Delete :${key}" "${PLIST}" 2>/dev/null || true
  /usr/libexec/PlistBuddy -c "Add :${key} ${type} ${value}" "${PLIST}"
}

# Without this key macOS refuses microphone access and does not say why.
plist_set NSMicrophoneUsageDescription string \
  "Viskly turns what you say into text. The recording never leaves this machine."
# A UI agent: no Dock icon, no taking over the menu bar.
plist_set LSUIElement bool true
# A stable identifier — macOS ties the consents to the application by this.
plist_set CFBundleIdentifier string "${BUNDLE_ID}"
# The version a user sees, unconstrained by the leading-zero rule above.
plist_set CFBundleShortVersionString string "${PROJECT_VERSION}"

# Released builds run under library validation: only code signed by the same team loads.
# An ad-hoc signature has no team, so a local ad-hoc build has to switch it off or not even
# its own runtime would load. Only that build gets the exception, never a signed one.
if [[ "${IDENTITY}" == "-" ]]; then
  ADHOC_ENTITLEMENTS="${ROOT}/target/entitlements-adhoc.plist"
  cp "${ENTITLEMENTS}" "${ADHOC_ENTITLEMENTS}"
  /usr/libexec/PlistBuddy -c "Add :com.apple.security.cs.disable-library-validation bool true" \
    "${ADHOC_ENTITLEMENTS}"
  ENTITLEMENTS="${ADHOC_ENTITLEMENTS}"
fi

echo "==> Signing the bundle"
# Inside out, each piece on its own: every binary in the runtime, then the runtime as a
# bundle, then the application. codesign --deep would be shorter, but it signs nested code
# with the outer bundle's options, and Apple advises against it for exactly that reason.
# Editing Info.plist above has already invalidated what jpackage signed.
# Entitlements go on executables only; a library runs with those of the process that
# loaded it.
count=0
while IFS= read -r -d '' binary; do
  case "$(macho_kind "${binary}")" in
    executable) sign --entitlements "${ENTITLEMENTS}" "${binary}" ;;
    "") continue ;;
    *) sign "${binary}" ;;
  esac
  count=$((count + 1))
done < <(find "${RUNTIME}" "${BUNDLE}/Contents/app" -type f -print0)
sign "${RUNTIME}"
sign --entitlements "${ENTITLEMENTS}" "${BUNDLE}"
echo "    ${count} binaries in the runtime and natives/, the runtime and the application (${ARCH})"

codesign --verify --strict --verbose=1 "${BUNDLE}" 2>&1 | sed 's/^/    /'

# The failure this catches shipped once already: a runtime older than the bytecode.
# Cheap to check here, invisible until first launch otherwise.
echo "==> Checking the bundled runtime"
# jpackage strips bin/ from the runtime it bundles, so there is no java binary to ask. An
# earlier version of this check looked for one, never found it, and skipped itself on
# every build. The release file jlink writes carries the version instead.
RELEASE="${RUNTIME}/Contents/Home/release"
if [[ ! -f "${RELEASE}" ]]; then
  echo "No ${RELEASE}: the bundled runtime cannot be verified."
  exit 1
fi
RUNTIME_VERSION="$(sed -nE 's/^JAVA_VERSION="([^"]+)"$/\1/p' "${RELEASE}")"
RUNTIME_MAJOR="${RUNTIME_VERSION%%.*}"
echo "    Java ${RUNTIME_VERSION}"
if [[ -z "${RUNTIME_MAJOR}" || "${RUNTIME_MAJOR}" -lt 25 ]]; then
  echo
  echo "The bundled runtime is Java '${RUNTIME_VERSION}', but the code needs 25."
  echo "The application would build and then fail on launch with UnsupportedClassVersionError."
  exit 1
fi

DMG_PATH=""
if [[ "${DMG}" == true ]]; then
  echo "==> Packing the disk image"
  # The full Maven version, SNAPSHOT included, unlike the bundle: a development build must
  # not pass for a release once the file is lying around in someone's Downloads.
  MAVEN_VERSION="$(basename "${JAR}" .jar)"
  MAVEN_VERSION="${MAVEN_VERSION#*-}"
  DMG_PATH="${DIST}/${APP}-${MAVEN_VERSION}-${ARCH}.dmg"
  DMG_STAGE="${ROOT}/target/dmg-stage"
  DMG_MOUNT="${ROOT}/target/dmg-mount"
  rm -rf "${DMG_STAGE}" "${DMG_MOUNT}"
  mkdir -p "${DMG_STAGE}" "${DMG_MOUNT}"

  # ditto, not cp: it is the copy that keeps everything a bundle signature covers.
  ditto "${BUNDLE}" "${DMG_STAGE}/${APP}.app"
  # The layout people expect from a Mac download: the app beside a link to drag it onto.
  ln -s /Applications "${DMG_STAGE}/Applications"
  hdiutil create -volname "${APP}" -srcfolder "${DMG_STAGE}" -ov -format UDZO \
    "${DMG_PATH}" >/dev/null
  if [[ "${IDENTITY}" != "-" ]]; then
    codesign --force --timestamp --sign "${IDENTITY}" "${DMG_PATH}"
  fi

  # Verify the copy inside the image, not the one beside it. A signature broken on the
  # way in shows up only on someone else's machine, as an application that will not open.
  # An explicit mount point, so a Viskly volume the user already has open cannot collide.
  hdiutil attach -nobrowse -readonly -mountpoint "${DMG_MOUNT}" "${DMG_PATH}" >/dev/null
  if ! codesign --verify --strict "${DMG_MOUNT}/${APP}.app" 2>/dev/null; then
    hdiutil detach "${DMG_MOUNT}" -quiet
    echo "The application inside ${DMG_PATH} fails signature verification."
    exit 1
  fi
  hdiutil detach "${DMG_MOUNT}" -quiet
  rm -rf "${DMG_STAGE}" "${DMG_MOUNT}"
  echo "    $(basename "${DMG_PATH}"), $(du -h "${DMG_PATH}" | cut -f1 | tr -d ' '), signature intact"
fi

if [[ -n "${NOTARY_PROFILE}" ]]; then
  echo "==> Notarising (Apple scans the whole image; usually a few minutes)"
  RESULT="${DIST}/notarisation.json"
  xcrun notarytool submit "${DMG_PATH}" --keychain-profile "${NOTARY_PROFILE}" \
    --wait --output-format json > "${RESULT}"
  STATUS="$(plutil -extract status raw -o - "${RESULT}")"
  if [[ "${STATUS}" != "Accepted" ]]; then
    # The status alone says nothing about which file Apple objected to; the log does.
    SUBMISSION="$(plutil -extract id raw -o - "${RESULT}")"
    echo "Notarisation came back ${STATUS}. Apple's log:"
    xcrun notarytool log "${SUBMISSION}" --keychain-profile "${NOTARY_PROFILE}"
    exit 1
  fi
  # Stapling puts the ticket into the image itself, so the first open does not depend on
  # Gatekeeper reaching Apple.
  xcrun stapler staple -q "${DMG_PATH}"
  spctl --assess --type open --context context:primary-signature "${DMG_PATH}"
  echo "    accepted and stapled"
fi

if [[ "${INSTALL}" == true ]]; then
  # Replacing the bundle under a running instance leaves that process alive on deleted
  # files, and launching the new build then only brings the old process forward: macOS
  # sees the same bundle identifier already running.
  INSTALLED="/Applications/${APP}.app/Contents/MacOS/${APP}"
  if pgrep -f "${INSTALLED}" >/dev/null; then
    echo "==> Quitting the running ${APP}"
    osascript -e "tell application id \"${BUNDLE_ID}\" to quit" >/dev/null 2>&1 || true
    for _ in {1..20}; do
      pgrep -f "${INSTALLED}" >/dev/null || break
      sleep 0.5
    done
    if pgrep -f "${INSTALLED}" >/dev/null; then
      echo "${APP} did not quit within 10 s. Builds from before the quit fix hang on"
      echo "quitting; end it with: pkill -9 -f ${INSTALLED}"
      exit 1
    fi
  fi

  echo "==> Installing into /Applications"
  rm -rf "/Applications/${APP}.app"
  cp -R "${BUNDLE}" /Applications/
  BUNDLE="/Applications/${APP}.app"
fi

if [[ "${LOGIN}" == true ]]; then
  echo "==> Adding to login items"
  osascript -e "tell application \"System Events\" to make login item at end \
    with properties {path:\"${BUNDLE}\", hidden:true}" >/dev/null
fi

GATEKEEPER=""
if [[ -n "${DMG_PATH}" && -z "${NOTARY_PROFILE}" ]]; then
  GATEKEEPER="
  Not notarised: on another Mac, Gatekeeper refuses it until the user allows it in
  Settings > Privacy & Security > Open Anyway."
fi

cat <<INFO

Done: ${BUNDLE}${DMG_PATH:+
Disk image: ${DMG_PATH}}${GATEKEEPER}

On first launch macOS will ask about the microphone. The other two consents you have to
grant by hand, in Settings > Privacy & Security:

  Input Monitoring   -> ${APP}    (listening for the shortcut)
  Accessibility      -> ${APP}    (pasting text)

After ticking them, quit the application and start it again — macOS reads permissions
when the process starts.

The model (574 MB) is not part of the package. If you do not have it yet:
  ./scripts/get-model.sh
INFO
