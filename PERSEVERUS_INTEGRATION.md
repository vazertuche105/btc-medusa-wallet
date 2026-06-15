# Perseverus Privacy Tab — Sparrow Integration Guide

## Overview

This adds a **Privacy** tab to Sparrow Wallet that scans your UTXOs against the Perseverus Privacy Oracle to determine their KYC association status and compute an overall privacy score.

## What Was Changed

### New Files (3)

1. **`src/main/java/com/sparrowwallet/sparrow/wallet/PrivacyController.java`**
   Controller for the Privacy tab. Reads UTXOs from the loaded wallet, calls `perseverus-cli` as a subprocess for each confirmed UTXO, displays results in a table, and computes a 0–100 privacy score.

2. **`src/main/resources/com/sparrowwallet/sparrow/wallet/privacy.fxml`**
   FXML layout for the Privacy tab — summary header (score, UTXO count, status), results table (TXID, vout, value, block height, KYC tag, status), progress bar, server URL field, and Scan button.

3. **`src/main/resources/com/sparrowwallet/sparrow/wallet/privacy.css`**
   CSS styles for color-coded KYC tags (green/amber/red) and privacy score display.

### Modified Files (2)

4. **`src/main/java/com/sparrowwallet/sparrow/wallet/Function.java`**
   Added `PRIVACY` to the enum between `UTXOS` and `SETTINGS`.

5. **`src/main/resources/com/sparrowwallet/sparrow/wallet/wallet.fxml`**
   Added a Privacy ToggleButton (with eye icon) to the left sidebar menu between UTXOs and Settings.

## Prerequisites

1. **Java 25** (Eclipse Temurin recommended)
2. **Perseverus server** running on localhost:3030 (or specify a different URL in the tab)
3. **perseverus-cli** binary on your PATH (or set `PERSEVERUS_CLI` env var to its path)
4. **Pre-built block filters** for the block heights your wallet's UTXOs are in

## How to Build and Run

```bash
cd sparrow-fork

# Build Sparrow (first build downloads dependencies)
./gradlew build -x test

# Run Sparrow
./gradlew run
```

## How to Use

1. Open a wallet in Sparrow (File → Open Wallet)
2. Click the **Privacy** tab (eye icon) in the left sidebar
3. Verify the server URL points to your running Perseverus server
4. Click **Scan Wallet**
5. The scan runs in the background — each UTXO is queried through the full privacy-preserving pipeline (decoy blocks, filter download, OPRF query, DLEQ verification)
6. Results appear in the table as they complete, with a final privacy score

## Privacy Score Interpretation

- **80–100**: Excellent — most UTXOs have no KYC association
- **50–79**: Moderate — some UTXOs are linked to KYC exchanges
- **0–49**: Poor — most UTXOs are KYC-associated

### Tag Color Coding

- **Green**: Clean, Unknown, or CoinJoin (privacy-preserving)
- **Amber**: Coinbase/mining reward (identifiable but not KYC)
- **Red**: KYC Exchange (linked to identity verification)

## Architecture

The integration follows Sparrow's existing tab pattern exactly:

```
Function enum → wallet.fxml ToggleButton → privacy.fxml → PrivacyController
                                                              ↓
                                                    WalletForm.getWalletUtxosEntry()
                                                              ↓
                                                    For each UtxoEntry:
                                                      hashIndex.getHash() → txid
                                                      hashIndex.getIndex() → vout
                                                      hashIndex.getHeight() → block height
                                                      hashIndex.getValue() → sats
                                                              ↓
                                                    perseverus-cli query subprocess
                                                              ↓
                                                    Parse "KYC Tag: ..." output
                                                              ↓
                                                    Display in TableView + compute score
```

The CLI subprocess approach is used for the demo. In production, this would use JNI to call the Rust crypto library directly, eliminating process overhead and enabling tighter integration.
