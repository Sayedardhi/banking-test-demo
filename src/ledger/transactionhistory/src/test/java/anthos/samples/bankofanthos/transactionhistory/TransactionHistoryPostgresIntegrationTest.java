/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package anthos.samples.bankofanthos.transactionhistory;

import static anthos.samples.bankofanthos.transactionhistory.TestFixtures.BASE_TIME;
import static anthos.samples.bankofanthos.transactionhistory.TestFixtures.EXTERNAL_ROUTING;
import static anthos.samples.bankofanthos.transactionhistory.TestFixtures.LOCAL_ROUTING;
import static org.assertj.core.api.Assertions.assertThat;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.LoadingCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * HTTP -> JWT verification -> Guava cache -> JPA -> PostgreSQL, plus the live LedgerReader thread,
 * against a throwaway postgres:16-alpine initialized with the repository's ledger-db schema.
 * Each test uses its own synthetic account numbers; the ledger is append-only, so rows are never
 * deleted between tests and the container is discarded afterwards.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionHistoryPostgresIntegrationTest {

    static final int HISTORY_LIMIT = 5;
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final AtomicLong ACCOUNTS = new AtomicLong(1000000000L);
    private static final AtomicLong CLOCK = new AtomicLong();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withCopyFileToContainer(
            MountableFile.forHostPath(Path.of("../ledger-db/initdb/0_init_tables.sql")),
            "/docker-entrypoint-initdb.d/0_init_tables.sql");

    static final KeyPair KEYS = TestFixtures.rsaKeyPair();
    private static final KeyPair ATTACKER_KEYS = TestFixtures.rsaKeyPair();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path publicKey = Files.createTempFile("transactionhistory-it", ".pem");
        publicKey.toFile().deleteOnExit();
        TestFixtures.writePublicKeyPem(KEYS, publicKey);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("PUB_KEY_PATH", publicKey::toString);
        registry.add("LOCAL_ROUTING_NUM", () -> LOCAL_ROUTING);
        registry.add("HISTORY_LIMIT", () -> HISTORY_LIMIT);
        registry.add("POLL_MS", () -> 50);
        registry.add("VERSION", () -> "integration-test");
        registry.add("PORT", () -> "0");
        registry.add("ENABLE_TRACING", () -> "false");
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoadingCache<String, Deque<Transaction>> cache;
    private final ObjectMapper json = new ObjectMapper();

    static String newAccount() {
        return Long.toString(ACCOUNTS.incrementAndGet());
    }

    /** Inserts a ledger row with a strictly increasing timestamp and returns its id. */
    private long insert(String from, String fromRoute, String to, String toRoute, int amount) {
        Timestamp ts = Timestamp.from(BASE_TIME.plusSeconds(CLOCK.incrementAndGet()));
        return jdbc.queryForObject("INSERT INTO TRANSACTIONS "
            + "(FROM_ACCT, FROM_ROUTE, TO_ACCT, TO_ROUTE, AMOUNT, TIMESTAMP) "
            + "VALUES (?, ?, ?, ?, ?, ?) RETURNING TRANSACTION_ID",
            Long.class, from, fromRoute, to, toRoute, amount, ts);
    }

    private long local(String from, String to, int amount) {
        return insert(from, LOCAL_ROUTING, to, LOCAL_ROUTING, amount);
    }

    private ResponseEntity<String> get(String path, String authorization) {
        HttpHeaders headers = new HttpHeaders();
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> history(String account) {
        return get("/transactions/" + account, "Bearer " + TestFixtures.validToken(KEYS, account));
    }

    private List<Integer> amounts(ResponseEntity<String> response) throws Exception {
        List<Integer> out = new ArrayList<>();
        json.readTree(response.getBody()).forEach(n -> out.add(n.get("amount").asInt()));
        return out;
    }

    private List<Integer> awaitAmounts(String account, Predicate<List<Integer>> condition) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        List<Integer> latest = amounts(history(account));
        while (!condition.test(latest) && System.nanoTime() < deadline) {
            Thread.sleep(50);
            latest = amounts(history(account));
        }
        return latest;
    }

    /**
     * Waits until the LedgerReader has processed every row inserted so far: rows are streamed in
     * id order, so once a sentinel row for a freshly cached account shows up, all earlier rows
     * have been delivered.
     */
    void awaitReaderCaughtUp() throws Exception {
        String sentinel = newAccount();
        assertThat(amounts(history(sentinel))).isEmpty();
        local(newAccount(), sentinel, 1);
        assertThat(awaitAmounts(sentinel, a -> !a.isEmpty())).containsExactly(1);
    }

    private long ledgerRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM TRANSACTIONS", Long.class);
    }

    @Test
    @DisplayName("Owner sees only their local-bank transactions, newest first, in the frontend's JSON format")
    void ownerSeesOwnLocalHistoryNewestFirst() throws Exception {
        String alice = newAccount();
        String bob = newAccount();
        String carol = newAccount();
        insert(carol, EXTERNAL_ROUTING, alice, LOCAL_ROUTING, 101);   // external deposit to alice
        local(alice, bob, 102);                                        // alice pays bob
        insert(bob, LOCAL_ROUTING, alice, EXTERNAL_ROUTING, 103);     // alice's number at another bank
        insert(alice, EXTERNAL_ROUTING, carol, EXTERNAL_ROUTING, 104); // alice's number at another bank
        local(bob, carol, 105);                                        // unrelated
        local(bob, alice, 106);                                        // bob pays alice
        awaitReaderCaughtUp();

        ResponseEntity<String> response = history(alice);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(amounts(response)).containsExactly(106, 102, 101);
        JsonNode newest = json.readTree(response.getBody()).get(0);
        assertThat(newest.get("fromAccountNum").asText()).isEqualTo(bob);
        assertThat(newest.get("toAccountNum").asText()).isEqualTo(alice);
        assertThat(newest.get("toRoutingNum").asText()).isEqualTo(LOCAL_ROUTING);
        assertThat(newest.get("timestamp").asText())
            .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{2}:?\\d{2}");
    }

    @Test
    @DisplayName("Initial load returns only the newest HISTORY_LIMIT transactions")
    void initialLoadCappedAtHistoryLimit() throws Exception {
        String alice = newAccount();
        String bob = newAccount();
        for (int i = 1; i <= HISTORY_LIMIT + 2; i++) {
            local(alice, bob, i);
        }
        awaitReaderCaughtUp();

        assertThat(amounts(history(alice))).containsExactly(7, 6, 5, 4, 3);
    }

    @Test
    @DisplayName("New ledger entry appears once at the top of a cached history via the LedgerReader")
    void newEntryStreamedIntoCachedHistory() throws Exception {
        String alice = newAccount();
        String bob = newAccount();
        local(bob, alice, 10);
        awaitReaderCaughtUp();
        assertThat(amounts(history(alice))).containsExactly(10);
        assertThat(amounts(history(bob))).containsExactly(10);

        local(alice, bob, 20);

        assertThat(awaitAmounts(alice, a -> a.contains(20))).containsExactly(20, 10);
        assertThat(awaitAmounts(bob, a -> a.contains(20))).containsExactly(20, 10);
    }

    @Test
    @DisplayName("Streamed updates keep a cached history at HISTORY_LIMIT, dropping the oldest")
    void streamedUpdatesRespectHistoryLimit() throws Exception {
        String alice = newAccount();
        String bob = newAccount();
        for (int i = 1; i <= HISTORY_LIMIT; i++) {
            local(alice, bob, i);
        }
        awaitReaderCaughtUp();
        assertThat(amounts(history(alice))).containsExactly(5, 4, 3, 2, 1);

        local(bob, alice, 6);

        assertThat(awaitAmounts(alice, a -> a.get(0) == 6)).containsExactly(6, 5, 4, 3, 2);
    }

    @Test
    @DisplayName("Ledger entries for accounts nobody has viewed are not pulled into the cache")
    void unviewedAccountsNotCached() throws Exception {
        String dormant = newAccount();
        String watched = newAccount();
        String other = newAccount();
        awaitReaderCaughtUp();
        history(watched);
        local(dormant, other, 1);
        local(other, watched, 2);
        awaitAmounts(watched, a -> a.contains(2));

        assertThat(cache.asMap()).doesNotContainKeys(dormant, other);
        assertThat(amounts(history(dormant))).containsExactly(1);
    }

    @Test
    @DisplayName("Valid token for another account gets 401 with no data and no cache/ledger change")
    void otherAccountRejected() throws Exception {
        String alice = newAccount();
        String victim = newAccount();
        local(victim, alice, 500);
        long rowsBefore = ledgerRows();

        ResponseEntity<String> response = get("/transactions/" + victim,
            "Bearer " + TestFixtures.validToken(KEYS, alice));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo("not authorized");
        assertThat(cache.asMap()).doesNotContainKey(victim);
        assertThat(ledgerRows()).isEqualTo(rowsBefore);
    }

    @Test
    @DisplayName("Forged, expired and unsigned tokens are rejected with 401")
    void invalidTokensRejected() {
        String alice = newAccount();
        local(alice, newAccount(), 1);
        String forged = TestFixtures.validToken(ATTACKER_KEYS, alice);
        String expired = TestFixtures.token(KEYS, alice, Instant.now().minusSeconds(5));
        String unsigned = JWT.create().withClaim("acct", alice).sign(Algorithm.none());

        for (String token : List.of(forged, expired, unsigned, "not-a-jwt")) {
            ResponseEntity<String> response = get("/transactions/" + alice, "Bearer " + token);
            assertThat(response.getStatusCode()).as(token).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isEqualTo("not authorized");
        }
        assertThat(cache.asMap()).doesNotContainKey(alice);
    }

    @Test
    @DisplayName("Missing Authorization header is rejected as a client error without data")
    void missingAuthorizationHeaderRejected() {
        String alice = newAccount();
        local(alice, newAccount(), 1);

        ResponseEntity<String> response = get("/transactions/" + alice, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(cache.asMap()).doesNotContainKey(alice);
    }

    @Test
    @DisplayName("Empty bearer token ('Bearer ') is rejected as unauthorized (401)")
    void emptyBearerTokenUnauthorized() {
        String alice = newAccount();

        ResponseEntity<String> response = get("/transactions/" + alice, "Bearer ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Probes: /ready and /healthy report ok while the ledger reader runs; /version echoes VERSION")
    void probes() {
        assertThat(get("/ready", null).getBody()).isEqualTo("ok");
        ResponseEntity<String> healthy = get("/healthy", null);
        assertThat(healthy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(healthy.getBody()).isEqualTo("ok");
        assertThat(get("/version", null).getBody()).isEqualTo("integration-test");
    }
}
