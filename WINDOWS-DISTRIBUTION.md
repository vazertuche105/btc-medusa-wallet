# Distributing BTC Medusa for Windows

Produces a downloadable **`BTC Medusa-<version>.exe`** installer (unsigned, x64).

> **You must build on Windows.** `jpackage` only makes Windows installers when
> run on Windows. You can't produce the `.exe` from your Mac. Options:
> - a Windows 10/11 PC,
> - a Windows VM on your Mac (Parallels / VMware / UTM), or
> - **GitHub Actions** with a `windows-latest` runner (no Windows PC needed — see
>   the bottom of this file).

---

## 1. One-time setup on the Windows machine

Install these and make sure they're on `PATH`:

1. **JDK 22+** (the same line you build the wallet with). Set `JAVA_HOME` to it
   (must contain `bin\jpackage.exe`).
2. **WiX Toolset 3.x** — <https://wixtoolset.org/>. `jpackage` shells out to WiX
   (`candle.exe`/`light.exe`) to build the `.exe`/`.msi`. Add its `bin` to `PATH`.
3. **Rust** via <https://rustup.rs/> **and** the **Visual Studio Build Tools**
   with the *“Desktop development with C++”* workload — needed to compile the
   native `.dll` with the MSVC toolchain.
4. Get the project onto the machine, **including** `..\perseverus\client-native`
   (the Rust JNI crate the script compiles). Easiest: `git clone` your repo, or
   copy the `Patent` folder over.

## 2. Build the installer

From the `sparrow-fork` directory in **PowerShell**:

```powershell
.\package-win.ps1
```

It will:
1. build the native Rust library → `perseverus_client_native.dll` and place it in
   `src\main\resources\native\windows\x64\` (skip with `$env:SKIP_NATIVE="1"`),
2. run `jpackage` to produce the installer,
3. print the path: `build\jpackage\BTC Medusa-2.5.2.exe`.

Test it: double-click the `.exe`, install, launch from the Start menu.

## 3. Host it for download

Same as the Mac `.dmg` — it's just a file:
- Upload `BTC Medusa-<version>.exe` to your host / GitHub Releases.
- Link a "Download for Windows" button to it.
- Publish the SHA-256 so users can verify: `Get-FileHash "BTC Medusa-2.5.2.exe"`.

## 4. What downloaders see (unsigned)

Windows isn't as strict as macOS — an unsigned app **runs**, but the first launch
shows a **SmartScreen** warning. Put this next to your download link:

> **First launch on Windows**
> When Windows shows *"Windows protected your PC,"* click **More info**, then
> **Run anyway**. (This appears because the installer isn't yet signed with a
> code-signing certificate.)

After the first run, it launches normally.

---

## Removing the SmartScreen warning later (code signing)

Unlike Apple, Windows signing goes through a third-party Certificate Authority,
not a single $99 program:

- Buy a **code-signing certificate** (OV ~$200–400/yr, or **EV** which clears
  SmartScreen immediately but costs more and needs a hardware token) from a CA
  such as DigiCert, Sectigo, SSL.com, etc.
- Sign the built `.exe` with Microsoft's `signtool`:
  ```powershell
  signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 `
    /a "build\jpackage\BTC Medusa-2.5.2.exe"
  ```
- OV certificates build SmartScreen "reputation" over time/downloads; EV
  certificates are trusted immediately. I can wire `signtool` into
  `package-win.ps1` once you have a cert.

---

## No Windows machine? Build it in GitHub Actions (recommended)

A ready-to-run workflow is committed at
**`.github/workflows/windows-installer.yaml`**. It runs on a `windows-2022`
runner and does the whole job: sets up JDK 25 + Rust + WiX, clones the private
`perseverus` repo, compiles `perseverus_client_native.dll`, copies it into
`src\main\resources\native\windows\x64\`, runs `jpackage`, and uploads the
`BTC Medusa-<version>.exe` (plus a `.sha256.txt`) as a downloadable artifact.

### One-time setup: add two repository secrets

Because the `perseverus` Rust crate lives in a **private** repo, CI needs
credentials to clone it. In your GitHub repo go to
**Settings → Secrets and variables → Actions → New repository secret** and add:

| Secret | Value |
| --- | --- |
| `PERSEVERUS_REPO_URL` | The HTTPS git URL of the private perseverus repo, e.g. `https://github.com/youruser/perseverus.git`. The `client-native` crate must sit at `<repo>/client-native`. |
| `PERSEVERUS_TOKEN` | A GitHub token with **read** access to that repo. A fine-grained PAT scoped to just the perseverus repo (Contents: Read) is ideal; a classic PAT with `repo` scope also works. |

### Run it

1. Push this repo (with the workflow file) to GitHub.
2. Open the **Actions** tab → **Windows Installer (.exe)** → **Run workflow**.
   Optionally set the perseverus branch/tag to build (defaults to `main`).
3. When it finishes, open the run and download the **`BTC-Medusa-Windows-x64`**
   artifact — it contains the `.exe` and its SHA-256.

No WiX/Rust/JDK install on your side, and no Windows PC required.

> The crate is cloned with the MSVC toolchain (`x86_64-pc-windows-msvc`), so the
> DLL matches the x64 installer. For ARM Windows you'd add an
> `aarch64-pc-windows-msvc` build — rare, ask if you need it.

---

## Troubleshooting

- **`jpackage` NPE / "JAVA_HOME"**: the build script sets `BADASS_JLINK_JPACKAGE_HOME`,
  but `JAVA_HOME` must point at a JDK with `bin\jpackage.exe` (not a JRE).
- **"WiX tools not found" / candle/light errors**: install WiX 3.x and add its
  `bin` to `PATH`, then restart PowerShell.
- **`link.exe` / MSVC errors during cargo build**: install the Visual Studio
  Build Tools "Desktop development with C++" workload.
- **"perseverus_client_native not found" at runtime**: the `.dll` must be at
  `src\main\resources\native\windows\x64\`. Re-run without `$env:SKIP_NATIVE`.
- **Arm Windows**: this is x64-only; build a separate `aarch64-pc-windows-msvc`
  dll for ARM laptops if you need it (rare).
