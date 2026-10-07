/*
 * Copyright 2026 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package anthos.samples.bankofanthos.ledgerwriter.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * Runs the real ledgerwriter Spring Boot app over HTTP against an isolated
 * PostgreSQL container initialised with the production ledger-db schema.
 *
 * Real: HTTP stack, JWT RSA256 verification, JPA/Hikari, PostgreSQL.
 * Stubbed: balancereader only (see {@link BalanceReaderStub}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LedgerWriterIT {

    static final String LOCAL_ROUTING = "883745000";
    static final String EXTERNAL_ROUTING = "111000025";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.15-alpine")
                    .withDatabaseName("postgresdb")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(
                                    "../ledger-db/initdb/0_init_tables.sql"),
                            "/docker-entrypoint-initdb.d/0_init_tables.sql");

    static final BalanceReaderStub BALANCES;
    static final KeyPair TRUSTED_KEYS;
    static final KeyPair UNTRUSTED_KEYS;
    static final Path PUBLIC_KEY_FILE;

    static {
        try {
            BALANCES = new BalanceReaderStub();
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            TRUSTED_KEYS = generator.generateKeyPair();
            UNTRUSTED_KEYS = generator.generateKeyPair();
            PUBLIC_KEY_FILE = Files.createTempFile("ledgerwriter-it", ".pem");
            Files.writeString(PUBLIC_KEY_FILE, "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder().encodeToString(
                            TRUSTED_KEYS.getPublic().getEncoded())
                    + "\n-----END PUBLIC KEY-----\n");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.connection-timeout", () -> "2000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "1000");
        registry.add("PUB_KEY_PATH", PUBLIC_KEY_FILE::toString);
        registry.add("BALANCES_API_ADDR", BALANCES::address);
        registry.add("LOCAL_ROUTING_NUM", () -> LOCAL_ROUTING);
        registry.add("VERSION", () -> "integration-test");
        registry.add("PORT", () -> "0");
        registry.add("ENABLE_TRACING", () -> "false");
    }

    @AfterAll
    static void stopStub() {
        BALANCES.stop();
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    final HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    // ---- helpers -----------------------------------------------------------

    static String newAccount() {
        return String.format("%010d",
                ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
    }

    static String token(String account) {
        return token(account, TRUSTED_KEYS, Instant.now().plusSeconds(3600));
    }

    static String token(String account, KeyPair keys, Instant expiresAt) {
        return JWT.create().withSubject("it-user").withClaim("user", "it-user")
                .withClaim("acct", account).withExpiresAt(expiresAt)
                .sign(Algorithm.RSA256((RSAPublicKey) keys.getPublic(),
                        (RSAPrivateKey) keys.getPrivate()));
    }

    static Map<String, Object> payment(String from, String to, Object amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromAccountNum", from);
        body.put("fromRoutingNum", LOCAL_ROUTING);
        body.put("toAccountNum", to);
        body.put("toRoutingNum", LOCAL_ROUTING);
        body.put("amount", amount);
        body.put("uuid", UUID.randomUUID().toString());
        return body;
    }

    static Map<String, Object> deposit(String externalAcct, String to, int amount) {
        Map<String, Object> body = payment(externalAcct, to, amount);
        body.put("fromRoutingNum", EXTERNAL_ROUTING);
        return body;
    }

    HttpResponse<String> post(String authorization, String rawBody,
                              String contentType) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/transactions"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(rawBody));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String authorization, Map<String, Object> body)
            throws Exception {
        return post(authorization, json.writeValueAsString(body),
                "application/json");
    }

    HttpResponse<String> submit(String sender, Map<String, Object> body)
            throws Exception {
        return post("Bearer " + token(sender), body);
    }

    List<Map<String, Object>> rowsInvolving(String account) {
        return jdbc.queryForList("SELECT * FROM transactions "
                + "WHERE from_acct = ? OR to_acct = ? ORDER BY transaction_id",
                account, account);
    }

    // ---- persistence of accepted transactions ------------------------------

    @Nested
    @DisplayName("Accepted transactions")
    class Accepted {

        @Test
        @DisplayName("Internal payment within balance returns 201 and persists "
                + "exactly the submitted ledger row")
        void internalPaymentPersistsRow() throws Exception {
            String sender = newAccount();
            String recipient = newAccount();
            BALANCES.balance(sender, 10_000);
            Instant before = Instant.now().minusSeconds(5);

            HttpResponse<String> response =
                    submit(sender, payment(sender, recipient, 2_500));

            assertEquals(201, response.statusCode());
            assertEquals("ok", response.body());
            List<Map<String, Object>> rows = rowsInvolving(sender);
            assertEquals(1, rows.size());
            Map<String, Object> row = rows.get(0);
            assertAll(
                    () -> assertEquals(sender, row.get("from_acct")),
                    () -> assertEquals(LOCAL_ROUTING, row.get("from_route")),
                    () -> assertEquals(recipient, row.get("to_acct")),
                    () -> assertEquals(LOCAL_ROUTING, row.get("to_route")),
                    () -> assertEquals(2_500, row.get("amount")),
                    () -> assertTrue(((Timestamp) row.get("timestamp"))
                            .toInstant().isAfter(before)));
        }

        @Test
        @DisplayName("Sender balance is looked up once at balancereader with "
                + "the caller's bearer token")
        void balanceLookupForwardsCallerToken() throws Exception {
            String sender = newAccount();
            BALANCES.balance(sender, 10_000);
            String jwt = token(sender);

            post("Bearer " + jwt, payment(sender, newAccount(), 100));

            List<BalanceReaderStub.Call> calls = BALANCES.callsFor(sender);
            assertEquals(1, calls.size());
            assertEquals("Bearer " + jwt, calls.get(0).authorization());
        }

        @Test
        @DisplayName("Payment of exactly the available balance is accepted")
        void paymentOfEntireBalance() throws Exception {
            String sender = newAccount();
            BALANCES.balance(sender, 4_000);

            HttpResponse<String> response =
                    submit(sender, payment(sender, newAccount(), 4_000));

            assertEquals(201, response.statusCode());
            assertEquals(1, rowsInvolving(sender).size());
        }

        @Test
        @DisplayName("External deposit into the caller's account is persisted "
                + "without a balance lookup for the external sender")
        void externalDepositPersistsWithoutBalanceLookup() throws Exception {
            String account = newAccount();
            String external = newAccount();

            HttpResponse<String> response =
                    submit(account, deposit(external, account, 75_000));

            assertEquals(201, response.statusCode());
            List<Map<String, Object>> rows = rowsInvolving(account);
            assertEquals(1, rows.size());
            assertEquals(external, rows.get(0).get("from_acct"));
            assertEquals(EXTERNAL_ROUTING, rows.get(0).get("from_route"));
            assertEquals(75_000, rows.get(0).get("amount"));
            assertTrue(BALANCES.callsFor(external).isEmpty());
        }

        @Test
        @DisplayName("A client-supplied timestamp is ignored; the ledger "
                + "records server time")
        void clientTimestampIgnored() throws Exception {
            String account = newAccount();
            Map<String, Object> body = deposit(newAccount(), account, 500);
            body.put("timestamp", 946_684_800_000L); // 2000-01-01

            assertEquals(201, submit(account, body).statusCode());

            Timestamp stored = (Timestamp) rowsInvolving(account).get(0)
                    .get("timestamp");
            assertTrue(stored.toInstant().isAfter(
                    Instant.now().minus(Duration.ofHours(1))),
                    "stored timestamp was " + stored);
        }

        @Test
        @DisplayName("A client-supplied transactionId matching an existing row "
                + "must not cause the new transaction to be dropped")
        void clientTransactionIdDoesNotDropTransaction() throws Exception {
            String first = newAccount();
            assertEquals(201, submit(first, deposit(newAccount(), first, 100))
                    .statusCode());
            Number existingId = (Number) rowsInvolving(first).get(0)
                    .get("transaction_id");

            String second = newAccount();
            Map<String, Object> body = deposit(newAccount(), second, 200);
            body.put("transactionId", existingId.longValue());
            HttpResponse<String> response = submit(second, body);

            assertEquals(201, response.statusCode());
            assertEquals(1, rowsInvolving(second).size(),
                    "201 Created was returned but no ledger row was written");
            List<Map<String, Object>> firstRows = rowsInvolving(first);
            assertEquals(1, firstRows.size());
            assertEquals(100, firstRows.get(0).get("amount"),
                    "existing ledger row must be unchanged (append-only)");
        }
    }

    // ---- authorization ------------------------------------------------------

    @Nested
    @DisplayName("Authorization")
    class Authorization {

        @Test
        @DisplayName("Token signed by an untrusted key returns 401 and "
                + "persists nothing")
        void untrustedSignature() throws Exception {
            String sender = newAccount();
            BALANCES.balance(sender, 10_000);
            String forged = token(sender, UNTRUSTED_KEYS,
                    Instant.now().plusSeconds(3600));

            HttpResponse<String> response =
                    post("Bearer " + forged, payment(sender, newAccount(), 100));

            assertEquals(401, response.statusCode());
            assertEquals("not authorized", response.body());
            assertTrue(rowsInvolving(sender).isEmpty());
            assertTrue(BALANCES.callsFor(sender).isEmpty());
        }

        @Test
        @DisplayName("Expired token returns 401 and persists nothing")
        void expiredToken() throws Exception {
            String sender = newAccount();
            String expired = token(sender, TRUSTED_KEYS,
                    Instant.now().minusSeconds(60));

            HttpResponse<String> response =
                    post("Bearer " + expired, payment(sender, newAccount(), 100));

            assertEquals(401, response.statusCode());
            assertTrue(rowsInvolving(sender).isEmpty());
        }

        @Test
        @DisplayName("Valid token for account A cannot debit local account B")
        void cannotDebitAnotherLocalAccount() throws Exception {
            String attacker = newAccount();
            String victim = newAccount();
            BALANCES.balance(victim, 1_000_000);

            HttpResponse<String> response =
                    submit(attacker, payment(victim, attacker, 50_000));

            assertEquals(400, response.statusCode());
            assertEquals("sender not authenticated", response.body());
            assertTrue(rowsInvolving(victim).isEmpty());
            assertTrue(BALANCES.callsFor(victim).isEmpty());
        }

        @Test
        @DisplayName("Missing Authorization header is rejected with 400 and "
                + "persists nothing")
        void missingAuthorizationHeader() throws Exception {
            String sender = newAccount();

            HttpResponse<String> response =
                    post(null, payment(sender, newAccount(), 100));

            assertEquals(400, response.statusCode());
            assertTrue(rowsInvolving(sender).isEmpty());
        }

        @Test
        @DisplayName("Empty bearer token ('Bearer ') is rejected as "
                + "unauthorized (401), not a server error")
        void emptyBearerToken() throws Exception {
            String sender = newAccount();

            HttpResponse<String> response =
                    post("Bearer ", payment(sender, newAccount(), 100));

            assertTrue(rowsInvolving(sender).isEmpty());
            assertEquals(401, response.statusCode(),
                    "body: " + response.body());
        }
    }

    // ---- data validation -----------------------------------------------------

    static Stream<Arguments> invalidTransactions() {
        return Stream.of(
                Arguments.of("9-digit recipient", "toAccountNum", "123456789",
                        "invalid account details"),
                Arguments.of("non-numeric recipient", "toAccountNum", "12345abcde",
                        "invalid account details"),
                Arguments.of("8-digit recipient routing", "toRoutingNum", "88374500",
                        "invalid account details"),
                Arguments.of("zero amount", "amount", 0, "invalid amount"),
                Arguments.of("negative amount", "amount", -500, "invalid amount"),
                Arguments.of("send to self", "toAccountNum", null,
                        "can't send to self"));
    }

    @ParameterizedTest(name = "{0} -> 400 \"{3}\"")
    @MethodSource("invalidTransactions")
    @DisplayName("Invalid transaction is rejected with 400 and persists nothing")
    void invalidTransactionRejected(String label, String field, Object value,
                                    String message) throws Exception {
        String sender = newAccount();
        BALANCES.balance(sender, 1_000_000);
        Map<String, Object> body = payment(sender, newAccount(), 1_000);
        body.put(field, value == null ? sender : value);

        HttpResponse<String> response = submit(sender, body);

        assertEquals(400, response.statusCode());
        assertEquals(message, response.body());
        assertTrue(rowsInvolving(sender).isEmpty());
        assertTrue(BALANCES.callsFor(sender).isEmpty(),
                "balance must not be checked for invalid transactions");
    }

    static Stream<String> requiredFields() {
        return Stream.of("amount", "toAccountNum", "fromRoutingNum");
    }

    @ParameterizedTest(name = "missing {0} -> 400")
    @MethodSource("requiredFields")
    @DisplayName("Transaction missing a required field is rejected with 400 "
            + "(client error) and persists nothing")
    void missingRequiredFieldRejected(String field) throws Exception {
        String sender = newAccount();
        BALANCES.balance(sender, 1_000_000);
        Map<String, Object> body = payment(sender, newAccount(), 1_000);
        body.remove(field);

        HttpResponse<String> response = submit(sender, body);

        assertTrue(rowsInvolving(sender).isEmpty());
        assertEquals(400, response.statusCode(), "body: " + response.body());
    }

    @Test
    @DisplayName("Malformed JSON and amounts beyond 32-bit range are rejected "
            + "with 400; wrong content type with 415")
    void malformedRequestsRejected() throws Exception {
        String sender = newAccount();
        String auth = "Bearer " + token(sender);

        assertEquals(400, post(auth, "{\"fromAccountNum\":", "application/json")
                .statusCode());
        assertEquals(400, post(auth, payment(sender, newAccount(),
                2_147_483_648L)).statusCode());
        assertEquals(415, post(auth, json.writeValueAsString(
                payment(sender, newAccount(), 1)), "text/plain").statusCode());
        assertTrue(rowsInvolving(sender).isEmpty());
    }

    // ---- balance and dependency failures ------------------------------------

    @Test
    @DisplayName("Payment above available balance returns 400 and persists "
            + "nothing")
    void insufficientBalance() throws Exception {
        String sender = newAccount();
        BALANCES.balance(sender, 999);

        HttpResponse<String> response =
                submit(sender, payment(sender, newAccount(), 1_000));

        assertEquals(400, response.statusCode());
        assertEquals("insufficient balance", response.body());
        assertTrue(rowsInvolving(sender).isEmpty());
    }

    @Test
    @DisplayName("balancereader 5xx returns 500 and persists nothing")
    void balanceReaderServerError() throws Exception {
        String sender = newAccount();
        BALANCES.error(sender, 503);

        HttpResponse<String> response =
                submit(sender, payment(sender, newAccount(), 100));

        assertEquals(500, response.statusCode());
        assertTrue(rowsInvolving(sender).isEmpty());
    }

    @Test
    @DisplayName("balancereader dropping the connection returns 500, "
            + "persists nothing, and a retry with the same uuid succeeds")
    void balanceReaderConnectionFailureThenRetry() throws Exception {
        String sender = newAccount();
        BALANCES.dropConnection(sender);
        Map<String, Object> body = payment(sender, newAccount(), 100);

        HttpResponse<String> failed = submit(sender, body);
        BALANCES.balance(sender, 10_000);
        HttpResponse<String> retry = submit(sender, body);

        assertEquals(500, failed.statusCode());
        assertEquals(201, retry.statusCode());
        assertEquals(1, rowsInvolving(sender).size());
    }

    @Test
    @DisplayName("Database unavailable returns 500; after recovery the same "
            + "uuid is accepted and persisted once")
    void databaseOutageThenRetry() throws Exception {
        String account = newAccount();
        Map<String, Object> body = deposit(newAccount(), account, 1_234);
        HttpResponse<String> failed;
        setDatabaseAcceptingConnections(false);
        try {
            failed = submit(account, body);
        } finally {
            setDatabaseAcceptingConnections(true);
        }

        int probesBeforeRecovery = awaitLedgerWriterRecovered();
        HttpResponse<String> retry = submit(account, body);

        assertEquals(500, failed.statusCode());
        assertEquals(201, retry.statusCode(), "probe requests that failed "
                + "with stale pooled connections: " + probesBeforeRecovery);
        assertEquals(1, rowsInvolving(account).size());
    }

    /**
     * Condition-based wait: submits throwaway deposits for unique probe
     * accounts until ledgerwriter persists one again. Pooled connections
     * killed during the outage surface as 500s until the pool replaces them.
     */
    int awaitLedgerWriterRecovered() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        int failures = 0;
        while (System.nanoTime() < deadline) {
            String probe = newAccount();
            if (submit(probe, deposit(newAccount(), probe, 1)).statusCode()
                    == 201) {
                return failures;
            }
            failures++;
        }
        throw new AssertionError("ledgerwriter did not recover within 20s");
    }

    /**
     * Simulates a ledger-db outage: refuses new connections to the app
     * database and terminates the app's pooled ones. Administrative SQL runs
     * via psql against the separate "postgres" maintenance database.
     */
    static void setDatabaseAcceptingConnections(boolean accepting)
            throws Exception {
        String sql = accepting
                ? "ALTER DATABASE postgresdb ALLOW_CONNECTIONS true"
                : "ALTER DATABASE postgresdb ALLOW_CONNECTIONS false; "
                + "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                + "WHERE datname = 'postgresdb' AND pid <> pg_backend_pid()";
        var result = POSTGRES.execInContainer("psql", "-v", "ON_ERROR_STOP=1",
                "-U", POSTGRES.getUsername(), "-d", "postgres", "-c", sql);
        assertEquals(0, result.getExitCode(), result.getStderr());
    }

    // ---- idempotency ----------------------------------------------------------

    @Test
    @DisplayName("Replaying a successful request uuid returns 400 duplicate "
            + "and the ledger holds one row")
    void sequentialDuplicateRejected() throws Exception {
        String sender = newAccount();
        BALANCES.balance(sender, 10_000);
        Map<String, Object> body = payment(sender, newAccount(), 700);

        HttpResponse<String> first = submit(sender, body);
        HttpResponse<String> replay = submit(sender, body);

        assertEquals(201, first.statusCode());
        assertEquals(400, replay.statusCode());
        assertEquals("duplicate transaction uuid", replay.body());
        assertEquals(1, rowsInvolving(sender).size());
    }

    @Test
    @DisplayName("Two concurrent submissions with the same uuid post the "
            + "payment at most once")
    void concurrentDuplicatePostedOnce() throws Exception {
        String sender = newAccount();
        BALANCES.slowBalance(sender, 10_000, 750);
        Map<String, Object> body = payment(sender, newAccount(), 700);
        String auth = "Bearer " + token(sender);

        List<CompletableFuture<HttpResponse<String>>> inFlight = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            inFlight.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return post(auth, body);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        List<Integer> statuses = inFlight.stream()
                .map(CompletableFuture::join)
                .map(HttpResponse::statusCode).sorted().toList();

        List<Map<String, Object>> rows = rowsInvolving(sender);
        assertAll(
                () -> assertEquals(1, rows.size(), "ledger rows for one "
                        + "payment uuid; responses were " + statuses),
                () -> assertEquals(List.of(201, 400), statuses));
    }
}
