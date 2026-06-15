# Distributing BTC Medusa for macOS

This is the **unsigned / Apple-Silicon** packaging path: a `BTC Medusa.dmg` you
can host on a website. The app runs, but because it isn't signed with an Apple
Developer ID, downloaders must bypass macOS Gatekeeper the first time. When
you're ready for a clean "just double-click" experience, see
[Upgrading to signed + notarized](#upgrading-to-signed--notarized).

---

## 1. One-time setup on your Mac (Apple Silicon)

- **JDK with `jpackage`** — the same JDK you build the wallet with (the project
  targets a recent JDK). Check: `jpackage --version`.
- **Xcode command-line tools** — for `codesign`, `iconutil`, `hdiutil`:
  `xcode-select --install`
- **Rust** (only if you want to rebuild the native library): `cargo --version`.

## 2. Build the app + DMG

From the `sparrow-fork` directory:

```bash
./package-mac.sh
```

It will:
1. regenerate the app icon (`medusa.icns`) from the iconset,
2. rebuild the native Rust dylib (arm64) and refresh it in resources
   (skip with `SKIP_NATIVE=1 ./package-mac.sh` to use the committed one),
3. build `BTC Medusa.app` with `jpackage`,
4. **ad-hoc sign** the bundle — required on Apple Silicon or it won't launch,
5. produce `BTC Medusa-<version>.dmg` (app + drag-to-Applications shortcut).

Output: `sparrow-fork/BTC Medusa-2.5.2.dmg`.

Test it locally: `open "BTC Medusa-2.5.2.dmg"`, drag the app into Applications,
launch it.

## 3. Host it for download

A `.dmg` is just a file — host it like any download:
- Upload `BTC Medusa-<version>.dmg` to your web host / S3 / GitHub Releases.
- Link to it from your site (e.g. a "Download for Mac" button).
- Publish the **SHA-256 checksum** so users can verify the file:
  `shasum -a 256 "BTC Medusa-2.5.2.dmg"`

## 4. What your downloaders must do (unsigned app)

Because the build isn't notarized, the **first** launch is blocked by Gatekeeper.
Put these instructions next to your download link:

> **First launch on macOS**
> 1. Open the `.dmg` and drag **BTC Medusa** to **Applications**.
> 2. In Applications, **right-click** BTC Medusa → **Open**, then click **Open**
>    in the dialog. (A normal double-click will say it "can't be opened" — use
>    right-click → Open the first time only.)
> 3. If macOS says the app **"is damaged and can't be opened,"** that's the
>    download-quarantine flag. Open Terminal and run:
>    ```
>    xattr -dr com.apple.quarantine "/Applications/BTC Medusa.app"
>    ```
>    then open it normally.

After the first successful open, it launches with a normal double-click.

---

## Upgrading to signed + notarized

This removes every Gatekeeper warning — users just double-click. You need an
**Apple Developer Program** membership ($99/year).

1. Enroll at <https://developer.apple.com/programs/> (approval: hours to ~2 days).
2. In Xcode or the developer portal, create a **"Developer ID Application"**
   certificate and install it in your login keychain. Find its identity name:
   ```bash
   security find-identity -v -p codesigning
   # e.g.  "Developer ID Application: Your Name (TEAMID1234)"
   ```
3. Sign with the **hardened runtime** and the project's entitlements, then
   notarize and staple. Replace the ad-hoc `codesign` step in `package-mac.sh`
   with:
   ```bash
   IDENTITY="Developer ID Application: Your Name (TEAMID1234)"
   codesign --force --deep --options runtime --timestamp \
            --entitlements src/main/deploy/package/macos/Sparrow.entitlements \
            --sign "$IDENTITY" "$APP_DIR"
   # build the dmg (as the script does), then:
   xcrun notarytool submit "BTC Medusa-<version>.dmg" \
        --apple-id "you@example.com" --team-id TEAMID1234 \
        --password "<app-specific-password>" --wait
   xcrun stapler staple "BTC Medusa-<version>.dmg"
   ```
   (Create the app-specific password at <https://appleid.apple.com> → Sign-In &
   Security → App-Specific Passwords.)
4. Host the stapled `.dmg`. Now it opens with a plain double-click, no warnings.

---

## Troubleshooting

- **App bounces in the Dock and quits / "damaged":** on Apple Silicon the bundle
  must be signed. The script's ad-hoc `codesign` step handles this; if you built
  by hand, run `codesign --force --deep --sign - "BTC Medusa.app"`.
- **App won't launch, no error:** `CFBundleExecutable` in
  `src/main/deploy/package/macos/Info.plist` must exactly match the binary in
  `BTC Medusa.app/Contents/MacOS/`. The script warns if they differ.
- **QR scanning / camera does nothing:** the camera permission string lives in
  that same `Info.plist` (`NSCameraUsageDescription`) — don't remove it.
- **"libperseverus_client_native not found":** the arm64 dylib must be at
  `src/main/resources/native/osx/aarch64/`. Re-run without `SKIP_NATIVE=1`, or
  build it: `cd ../perseverus/client-native && cargo build --release`.
- **Intel Mac users:** this build is arm64-only and won't run on Intel. Making a
  universal build means building the dylib for `x86_64` too and merging with
  `lipo` — ask and I'll wire that up.
