# package-win.ps1 — Build BTC Medusa as a downloadable Windows .exe installer.
#
# MUST run on Windows (jpackage builds Windows installers only on Windows).
# Produces build\jpackage\BTC Medusa-<version>.exe.
#
# Requirements on the Windows machine:
#   * JDK 22+ with jpackage           (set JAVA_HOME to it)
#   * WiX Toolset 3.x                 (https://wixtoolset.org/ — jpackage needs candle/light on PATH)
#   * Rust (rustup) + Visual Studio Build Tools (C++ workload) — to compile the native .dll
#   * The repo, including ..\..\perseverus\client-native (the Rust JNI crate)
#
# Run from the sparrow-fork directory in PowerShell:
#     .\package-win.ps1
#
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

$version = (Select-String -Path build.gradle -Pattern "^version = '([^']+)'").Matches[0].Groups[1].Value
Write-Host "==> Packaging BTC Medusa $version (Windows x64, unsigned)"

# ── 0. JAVA_HOME / jpackage sanity (also fixes the org.beryx.jlink NPE) ──────
if (-not $env:JAVA_HOME) { throw "JAVA_HOME is not set. Point it at your JDK (must contain bin\jpackage.exe)." }
if (-not (Test-Path "$env:JAVA_HOME\bin\jpackage.exe")) { throw "No jpackage.exe under $env:JAVA_HOME\bin." }
$env:BADASS_JLINK_JPACKAGE_HOME = $env:JAVA_HOME
Write-Host "==> JAVA_HOME=$env:JAVA_HOME"

# ── 1. Build the native Rust DLL (x86_64-windows) and refresh resources ──────
$nativeSrc = Join-Path $here "..\perseverus\client-native"
$dllDest   = Join-Path $here "src\main\resources\native\windows\x64\perseverus_client_native.dll"
if (($env:SKIP_NATIVE -ne "1") -and (Test-Path $nativeSrc) -and (Get-Command cargo -ErrorAction SilentlyContinue)) {
    Write-Host "==> Building native dll (cargo build --release)"
    Push-Location $nativeSrc
    cargo build --release
    Pop-Location
    $built = Join-Path $nativeSrc "target\release\perseverus_client_native.dll"
    if (Test-Path $built) {
        New-Item -ItemType Directory -Force -Path (Split-Path $dllDest) | Out-Null
        Copy-Item $built $dllDest -Force
        Write-Host "    copied perseverus_client_native.dll -> $dllDest"
    } else {
        throw "cargo did not produce $built — check the Rust/MSVC toolchain."
    }
} else {
    if (-not (Test-Path $dllDest)) {
        throw "No native dll at $dllDest and cargo build was skipped. Build it first (Rust + VS Build Tools)."
    }
    Write-Host "==> Skipping native rebuild (using existing $dllDest)"
}

# ── 2. Build the .exe installer (jpackage builds it directly on Windows) ─────
Write-Host "==> Building installer (.\gradlew.bat jpackage)"
.\gradlew.bat clean jpackage
if ($LASTEXITCODE -ne 0) { throw "gradle jpackage failed." }

$exe = Get-ChildItem -Path "build\jpackage" -Filter *.exe -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $exe) { throw "No .exe produced under build\jpackage." }

Write-Host ""
Write-Host "==> DONE"
Write-Host "    Installer: $($exe.FullName)"
Write-Host ""
Write-Host "This build is unsigned, so Windows SmartScreen will warn on first run."
Write-Host "See WINDOWS-DISTRIBUTION.md for the download-page instructions."
