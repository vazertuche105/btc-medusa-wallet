#!/usr/bin/env bash
#
# End-to-end integration test runner for Perseverus ↔ Sparrow.
#
# Builds the JNI cdylib, starts the mock server, runs the Java
# integration tests, and tears everything down.
#
# Usage:
#   ./run-integration-tests.sh
#
# Environment variables (all optional):
#   MOCK_PORT       — port for the mock server (default: 3030)
#   SKIP_BUILD      — set to "1" to skip cargo build steps
#   PERSEVERUS_DIR  — path to perseverus repo (default: ../perseverus)
#   MOCK_SERVER_DIR — path to mock server crate (default: ../perseverus-mock-server)
#
set -euo pipefail

# ── Config ──────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MOCK_PORT="${MOCK_PORT:-3030}"
SKIP_BUILD="${SKIP_BUILD:-0}"
PERSEVERUS_DIR="${PERSEVERUS_DIR:-$SCRIPT_DIR/../perseverus}"
MOCK_SERVER_DIR="${MOCK_SERVER_DIR:-$SCRIPT_DIR/../perseverus-mock-server}"

MOCK_URL="http://localhost:${MOCK_PORT}"
MOCK_PID=""

# Detect platform for the cdylib extension and name.
case "$(uname -s)" in
    Darwin*) LIB_EXT="dylib" ;;
    Linux*)  LIB_EXT="so" ;;
    MINGW*|CYGWIN*|MSYS*) LIB_EXT="dll" ;;
    *) echo "Unsupported OS: $(uname -s)"; exit 1 ;;
esac

LIB_NAME="libperseverus_client_native.${LIB_EXT}"
LIB_PATH="${PERSEVERUS_DIR}/target/release/${LIB_NAME}"

# ── Helpers ─────────────────────────────────────────────────────────

cleanup() {
    echo ""
    if [ -n "$MOCK_PID" ] && kill -0 "$MOCK_PID" 2>/dev/null; then
        echo "Stopping mock server (PID $MOCK_PID)..."
        kill "$MOCK_PID" 2>/dev/null || true
        wait "$MOCK_PID" 2>/dev/null || true
    fi
    echo "Done."
}

trap cleanup EXIT

wait_for_server() {
    local url="$1"
    local max_attempts=30
    local attempt=0

    echo -n "Waiting for mock server at ${url}"
    while ! curl -sf "${url}/health" >/dev/null 2>&1; do
        attempt=$((attempt + 1))
        if [ "$attempt" -ge "$max_attempts" ]; then
            echo " TIMEOUT"
            echo "Mock server did not start within ${max_attempts}s."
            echo "Check the log above for errors."
            exit 1
        fi
        echo -n "."
        sleep 1
    done
    echo " ready."
}

# ── Step 1: Build JNI cdylib ───────────────────────────────────────

if [ "$SKIP_BUILD" = "1" ]; then
    echo "SKIP_BUILD=1 — skipping cargo builds."
else
    echo "=== Building JNI cdylib (perseverus-client-native) ==="
    (cd "$PERSEVERUS_DIR" && cargo build --release -p perseverus-client-native --features jni)
    echo ""

    echo "=== Building mock server ==="
    (cd "$MOCK_SERVER_DIR" && cargo build --release)
    echo ""
fi

# Verify the cdylib exists.
if [ ! -f "$LIB_PATH" ]; then
    echo "ERROR: JNI library not found at ${LIB_PATH}"
    echo "Run without SKIP_BUILD=1, or build manually:"
    echo "  cd ${PERSEVERUS_DIR} && cargo build --release -p perseverus-client-native"
    exit 1
fi

echo "JNI library: ${LIB_PATH}"

# ── Step 2: Start mock server ──────────────────────────────────────

MOCK_BIN="${MOCK_SERVER_DIR}/target/release/perseverus-mock-server"
if [ ! -f "$MOCK_BIN" ]; then
    # Fall back to cargo run if the binary isn't where expected.
    MOCK_BIN=""
fi

echo "=== Starting mock server on port ${MOCK_PORT} ==="

if [ -n "$MOCK_BIN" ]; then
    BIND_ADDR="0.0.0.0:${MOCK_PORT}" "$MOCK_BIN" &
else
    (cd "$MOCK_SERVER_DIR" && BIND_ADDR="0.0.0.0:${MOCK_PORT}" cargo run --release) &
fi
MOCK_PID=$!

wait_for_server "$MOCK_URL"

# ── Step 3: Run Java integration tests ─────────────────────────────

echo ""
echo "=== Running Perseverus integration tests ==="
echo "  Library:    ${LIB_PATH}"
echo "  Mock URL:   ${MOCK_URL}"
echo ""

cd "$SCRIPT_DIR"

./gradlew :test \
    --tests "com.sparrowwallet.perseverus.IntegrationTest" \
    -Dperseverus.library.path="$LIB_PATH" \
    -Dperseverus.mock.url="$MOCK_URL" \
    --info

TEST_EXIT=$?

echo ""
if [ $TEST_EXIT -eq 0 ]; then
    echo "=== All integration tests PASSED ==="
else
    echo "=== Integration tests FAILED (exit code: $TEST_EXIT) ==="
fi

exit $TEST_EXIT
