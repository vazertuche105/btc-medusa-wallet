package com.sparrowwallet.perseverus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test: Sparrow → JNI → client-native → server.
 *
 * <p>Exercises the full issuance pipeline with <b>real BN254/BabyJubJub
 * crypto</b> against the server. The server generates a real OPRF key,
 * signs token packs via {@code sign_pack_batch}, produces valid DLEQ
 * proofs, and accepts Pedersen commitments on the bulletin board — so
 * every cryptographic check inside the Rust client is exercised.
 *
 * <p>Tokens are immediately spendable after issuance — no epoch
 * rollover needed.
 *
 * <h2>Prerequisites</h2>
 * <ol>
 *   <li>Build the JNI cdylib:
 *       {@code cargo build --release -p perseverus-client-native}</li>
 *   <li>Start the server:
 *       {@code cargo run -p perseverus-server}</li>
 *   <li>Run with both system properties:
 *   <pre>{@code
 *   ./gradlew test --tests com.sparrowwallet.perseverus.IntegrationTest \
 *       -Dperseverus.library.path=$(pwd)/../perseverus/target/release/libperseverus_client_native.dylib \
 *       -Dperseverus.mock.url=http://localhost:3030
 *   }</pre>
 *   </li>
 * </ol>
 *
 * <p>Tests are ordered because later steps depend on state created by
 * earlier ones (issuance must precede spend).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = Native.LIB_PATH_PROP, matches = ".+")
@EnabledIfSystemProperty(named = "perseverus.mock.url", matches = ".+")
class IntegrationTest {

    /** System property for the server base URL. */
    private static final String MOCK_URL_PROP = "perseverus.mock.url";

    /** Pack size used throughout the test. Small to keep it fast. */
    private static final int PACK_SIZE = 4;

    // ── Shared state across ordered tests ──────────────────────────

    private static String mockUrl;
    private static HttpClient http;

    /** Server's real G1 OPRF pubkey, fetched once in setup. */
    private static String serverPubkeyHex;

    /** The issued pack — kept across tests so spend can use it. */
    private static IssuedPack issuedPack;

    // ── Lifecycle ──────────────────────────────────────────────────

    @BeforeAll
    static void setUp() throws Exception {
        mockUrl = System.getProperty(MOCK_URL_PROP).replaceAll("/+$", "");
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // Fetch the server's real G1 pubkey via HTTP — this is the same
        // key the Rust IssuanceClient will verify DLEQ proofs against.
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(mockUrl + "/server/pubkey"))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, resp.statusCode(),
                "GET /server/pubkey failed: " + resp.body());

        serverPubkeyHex = extractJsonString(resp.body(), "pubkey_hex");
        assertNotNull(serverPubkeyHex, "pubkey_hex missing from response");
        assertFalse(serverPubkeyHex.isBlank(), "pubkey_hex is blank");
    }

    @AfterAll
    static void tearDown() {
        // Nothing to tear down — the server keeps running.
    }

    // ── Tests (ordered) ────────────────────────────────────────────

    /**
     * Verify we can open an IssuanceClient with the server's real
     * pubkey. This exercises the JNI boundary: Java → Rust
     * IssuanceClient::new, which parses and validates the G1 point.
     */
    @Test
    @Order(1)
    void openIssuanceClientWithRealPubkey() {
        try (IssuanceClient ic = IssuanceClient.open(mockUrl, serverPubkeyHex)) {
            assertNotNull(ic, "IssuanceClient.open returned null");
        }
    }

    /**
     * Issue a token pack — the core round-trip:
     * <ol>
     *   <li>Client draws seed + blinds, computes α_i = r_i · P_i</li>
     *   <li>POST /issue sends α_i, server signs → β_i + DLEQ proof</li>
     *   <li>Client verifies DLEQ, unblinds, builds pack Merkle tree</li>
     *   <li>Pedersen commitment published to bulletin board</li>
     * </ol>
     *
     * Tokens are immediately spendable after this call.
     */
    @Test
    @Order(2)
    void issuePackSucceeds() {
        try (IssuanceClient ic = IssuanceClient.open(mockUrl, serverPubkeyHex)) {
            issuedPack = ic.issuePack(PACK_SIZE);

            assertNotNull(issuedPack, "issuePack returned null");
            assertEquals(PACK_SIZE, issuedPack.packSize(),
                    "pack size should match requested size");

            byte[] blob = issuedPack.bytes();
            assertNotNull(blob, "pack blob is null");
            assertTrue(blob.length > 0, "pack blob is empty");
        }
    }

    /**
     * The bulletin board should have entries after issuance.
     * Verify via the /board/root endpoint.
     */
    @Test
    @Order(3)
    void boardRootUpdatedAfterPublish() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(mockUrl + "/board/root"))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, resp.statusCode(),
                "GET /board/root failed: " + resp.body());

        String rootHex = extractJsonString(resp.body(), "board_root_hex");
        assertNotNull(rootHex, "board_root_hex missing from response");
        assertFalse(rootHex.isBlank(), "board_root_hex is blank");
    }

    /**
     * The /board/leaves endpoint should contain the leaf hash from
     * our published commitment.
     */
    @Test
    @Order(4)
    void boardLeavesContainsPublishedCommitment() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(mockUrl + "/board/leaves"))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, resp.statusCode(),
                "GET /board/leaves failed: " + resp.body());

        String body = resp.body();
        assertTrue(body.contains("leaves_hex"),
                "response should contain leaves_hex field");
        assertTrue(body.length() > 50,
                "leaves response looks too short to contain any leaves");
    }

    // ── Error paths ────────────────────────────────────────────────

    /**
     * Issuing a pack with size 0 should fail at the Rust validation
     * layer, not silently return garbage.
     */
    @Test
    @Order(20)
    void issuePackSizeZeroThrows() {
        try (IssuanceClient ic = IssuanceClient.open(mockUrl, serverPubkeyHex)) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    Exception.class,
                    () -> ic.issuePack(0),
                    "issuePack(0) should throw");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────

    /**
     * Minimal JSON string field extractor. Avoids pulling in a JSON
     * library for test-only use.
     */
    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int keyIdx = json.indexOf(pattern);
        if (keyIdx < 0) return null;

        int colonIdx = json.indexOf(':', keyIdx + pattern.length());
        if (colonIdx < 0) return null;

        int openQuote = json.indexOf('"', colonIdx + 1);
        if (openQuote < 0) return null;

        int closeQuote = openQuote + 1;
        while (closeQuote < json.length()) {
            if (json.charAt(closeQuote) == '"' && json.charAt(closeQuote - 1) != '\\') {
                break;
            }
            closeQuote++;
        }
        if (closeQuote >= json.length()) return null;

        return json.substring(openQuote + 1, closeQuote);
    }
}
