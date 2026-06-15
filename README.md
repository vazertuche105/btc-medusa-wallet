# BTC Medusa

**BTC Medusa** is a privacy-scoring Bitcoin wallet built as a fork of
[Sparrow Wallet](https://sparrowwallet.com). In addition to everything Sparrow
does, it can privately assess the **KYC / exchange exposure of your UTXOs** —
telling you which coins are linked to identified entities — **without revealing
to any server which addresses you are checking.**

Privacy is enforced cryptographically, not by policy:

- Block data is published as **encrypted filters**; the wallet downloads them
  and decrypts the relevant entries locally.
- Decryption requires a **VOPRF** evaluation that is gated by a **zero-knowledge
  proof** (Groth16). The server verifies the proof and evaluates the OPRF
  **without learning which UTXO you asked about**.
- Queries are mixed with **decoy block fetches**, and traffic runs over an
  embedded **Tor** transport, so the server never sees your IP or your real
  block heights.

This repository contains only the **wallet (client) software** — enough to build
the application from source. The privacy-oracle server and the filter-building
pipeline are maintained separately and are **not** part of this repo.

---

## Relationship to Sparrow

BTC Medusa is a fork of Sparrow Wallet and inherits all of its functionality
(multisig, hardware wallets, PSBT, coin control, etc.). All upstream code remains
under the **Apache License 2.0** (see [`LICENSE`](LICENSE)). Huge thanks to Craig
Raw and the Sparrow contributors — please support the upstream project at
<https://sparrowwallet.com>.

The BTC Medusa additions live mainly under:

- `src/main/java/com/sparrowwallet/perseverus/` — the privacy-oracle client
  (token issuance, ZK spend, OPRF query, payment flows).
- `src/main/java/com/sparrowwallet/sparrow/wallet/PrivacyController.java` — the
  Privacy tab UI.
- `src/main/resources/native/.../libperseverus_client_native.dylib` — the
  compiled native client library (BabyJubJub OPRF, Groth16 prover).
- `src/main/resources/com/sparrowwallet/perseverus/pk.bin` — the **public**
  Groth16 proving key (a fixed artifact of the trusted setup; safe to ship).

---

## How it connects

By default the wallet talks to the BTC Medusa server over its **`.onion`
address using the bundled Tor client** — no external Tor install required. A
clearnet endpoint is available as a fallback (Settings → BTC Medusa →
Transport). You can point it at your own server instance in Settings.

Scanning is **paid**: you buy privacy tokens (Bitcoin, Lightning, or card), and
each UTXO scan spends one token via the gated ZK proof. Tokens are a **local
asset** stored on your computer, unlinkable to your payment — back them up with
**Settings → BTC Medusa → Token Backup → Export tokens** (they are *not*
recoverable from your seed phrase or hardware wallet).

---

## Building from source (macOS, Apple Silicon)

**Prerequisites**

- A JDK **22 or newer** that includes `jpackage` (`package-mac.sh` reads
  `JAVA_HOME`). Sparrow's upstream release binaries are built with Eclipse
  Temurin 25, which is a safe choice here too.
- Xcode command-line tools: `xcode-select --install` (for `codesign` / `iconutil`).
- `git` — the build pulls the `drongo` and `lark` submodules from the public
  Sparrow repositories.
- *(Optional)* a Rust toolchain — only needed if you want to rebuild the native
  `.dylib` yourself; otherwise the committed one is used.

**Build the app + DMG**

```bash
git clone https://github.com/<YOUR-GITHUB-USER>/btc-medusa-wallet.git
cd btc-medusa-wallet
git submodule update --init --recursive

export JAVA_HOME="$(/usr/libexec/java_home)"   # must contain jpackage
./package-mac.sh
```

This produces, in the project directory:

- `build/jpackage/BTC Medusa.app` — the application bundle (ad-hoc signed)
- `BTC Medusa-<version>.dmg` — a distributable disk image

> The build is **unsigned** (no Apple Developer ID). On first launch, macOS
> Gatekeeper requires a right-click → **Open** to bypass the warning.

**Run from source (development)**

```bash
git submodule update --init --recursive
./gradlew run
```

The wallet stores its config, logs, and wallets in `~/.sparrow` (same as upstream
Sparrow).

---

## What is intentionally *not* in this repo

- The BTC Medusa **privacy-oracle server** (token issuance, OPRF evaluation,
  proof verification, payments).
- The **filter-building pipeline** (block ingest, heuristic tagging, encrypted
  filter generation).
- Any **secret keys** (OPRF server key, trusted-setup secret, payment secrets).

The bundled `pk.bin` is the *public* proving key only. The wallet needs no
server source to build or run — it speaks to a running BTC Medusa server over
the network.

---

## License

Apache License 2.0 — see [`LICENSE`](LICENSE). Original work © the Sparrow Wallet
authors; BTC Medusa modifications are released under the same license.
