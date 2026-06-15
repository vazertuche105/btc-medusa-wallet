#!/usr/bin/env bash
#
# package-mac.sh — Build BTC Medusa as a downloadable macOS app (Apple Silicon).
#
# Produces an ad-hoc-signed "BTC Medusa.app" and a "BTC Medusa-<version>.dmg"
# that you can host for download. This is the UNSIGNED path (no Apple Developer
# ID): the app runs, but downloaders must bypass Gatekeeper on first open
# (see DISTRIBUTION.md). On Apple Silicon every app MUST be at least ad-hoc
# signed to launch at all — this script does that for you.
#
# Run on your Mac (Apple Silicon), from the sparrow-fork directory:
#     ./package-mac.sh
#
# Requirements on the Mac:
#   * JDK 22+ with jpackage (the same JDK you build Sparrow with)
#   * Xcode command-line tools  (xcode-select --install)  — for codesign/iconutil
#   * Rust toolchain (cargo)    — only if you want to rebuild the native dylib
#
set -euo pipefail

APP_NAME="BTC Medusa"
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

VERSION="$(grep -E "^version = " build.gradle | head -1 | sed -E "s/.*'([^']+)'.*/\1/")"
echo "==> Packaging ${APP_NAME} ${VERSION} (Apple Silicon, unsigned/ad-hoc)"

# ── 0. Sanity checks ─────────────────────────────────────────────────────────
if [[ "$(uname)" != "Darwin" ]]; then
  echo "ERROR: run this on macOS (jpackage + codesign are macOS-only)." >&2
  exit 1
fi
ARCH="$(uname -m)"
[[ "$ARCH" == "arm64" ]] || echo "WARNING: this Mac is '$ARCH', not arm64 — the bundled dylib is arm64-only."

# The org.beryx.jlink plugin reads JAVA_HOME to locate jpackage; if it's unset
# it does `new File(null)` and throws a bare NullPointerException in
# jpackageImage. Make sure JAVA_HOME points at a JDK that contains jpackage.
if [[ -z "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home 2>/dev/null || true)"
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/jpackage" ]]; then
  echo "ERROR: JAVA_HOME does not point at a JDK with jpackage." >&2
  echo "  JAVA_HOME='${JAVA_HOME:-<unset>}'" >&2
  echo "  Set it to your JDK, e.g.:" >&2
  echo "    export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home" >&2
  exit 1
fi
# Also hand the plugin the jpackage home explicitly (belt and suspenders).
export BADASS_JLINK_JPACKAGE_HOME="$JAVA_HOME"
echo "==> JAVA_HOME=$JAVA_HOME"

# ── 1. Regenerate the app icon from the iconset (crisper than the committed one)
ICONSET="src/main/deploy/package/macos/medusa.iconset"
ICNS="src/main/deploy/package/macos/medusa.icns"
if command -v iconutil >/dev/null 2>&1 && [[ -d "$ICONSET" ]]; then
  echo "==> Regenerating icon with iconutil"
  iconutil -c icns "$ICONSET" -o "$ICNS"
fi

# ── 2. (Optional) Rebuild the native Rust dylib (arm64) and refresh resources ─
NATIVE_SRC="../perseverus/client-native"
DYLIB_DEST="src/main/resources/native/osx/aarch64/libperseverus_client_native.dylib"
if [[ "${SKIP_NATIVE:-0}" != "1" && -d "$NATIVE_SRC" ]] && command -v cargo >/dev/null 2>&1; then
  echo "==> Rebuilding native dylib (cargo build --release)"
  ( cd "$NATIVE_SRC" && cargo build --release )
  BUILT="$NATIVE_SRC/target/release/libperseverus_client_native.dylib"
  if [[ -f "$BUILT" ]]; then
    mkdir -p "$(dirname "$DYLIB_DEST")"
    cp "$BUILT" "$DYLIB_DEST"
    echo "    copied $(basename "$BUILT") -> $DYLIB_DEST"
  fi
else
  echo "==> Skipping native rebuild (using committed dylib at $DYLIB_DEST)"
fi

# ── 3. Build the .app image with jpackage (skips the dmg; we make it ourselves)
echo "==> Building app image (./gradlew jpackageImage)"
./gradlew clean jpackageImage -PskipInstallers

APP_DIR="$(find build/jpackage -maxdepth 1 -name "*.app" -type d | head -1)"
[[ -n "$APP_DIR" ]] || { echo "ERROR: no .app produced under build/jpackage" >&2; exit 1; }
echo "    built: $APP_DIR"

# Verify CFBundleExecutable matches the actual binary (mismatch => won't launch)
EXEC_NAME="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$APP_DIR/Contents/Info.plist" 2>/dev/null || echo '')"
if [[ -n "$EXEC_NAME" && ! -f "$APP_DIR/Contents/MacOS/$EXEC_NAME" ]]; then
  echo "WARNING: CFBundleExecutable='$EXEC_NAME' but no matching binary in Contents/MacOS." >&2
  echo "         Contents/MacOS has: $(ls "$APP_DIR/Contents/MacOS")" >&2
  echo "         Fix CFBundleExecutable in src/main/deploy/package/macos/Info.plist to match." >&2
fi

# ── 4. Ad-hoc sign the bundle (REQUIRED on Apple Silicon to launch) ──────────
echo "==> Ad-hoc signing the app bundle"
codesign --force --deep --sign - --timestamp=none "$APP_DIR"
codesign --verify --deep --strict --verbose=2 "$APP_DIR" || true

# ── 5. Build the .dmg (app + drag-to-Applications shortcut) ──────────────────
DMG="${APP_NAME}-${VERSION}.dmg"
echo "==> Creating $DMG"
STAGING="$(mktemp -d)"
cp -R "$APP_DIR" "$STAGING/"
ln -s /Applications "$STAGING/Applications"
rm -f "$DMG"
hdiutil create -volname "$APP_NAME" -srcfolder "$STAGING" -ov -format UDZO "$DMG"
rm -rf "$STAGING"

echo
echo "==> DONE"
echo "    App: $APP_DIR"
echo "    DMG: $HERE/$DMG"
echo
echo "Test locally:  open \"$DMG\"  then drag the app to Applications."
echo "Because this build is unsigned, downloaders must bypass Gatekeeper on"
echo "first launch — see DISTRIBUTION.md."
