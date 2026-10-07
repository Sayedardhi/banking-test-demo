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

package anthos.samples.bankofanthos.balancereader;

import static anthos.samples.bankofanthos.balancereader.PostgresLedgerFixture.EXTERNAL_ROUTING;
import static anthos.samples.bankofanthos.balancereader.PostgresLedgerFixture.LOCAL_ROUTING;
import static anthos.samples.bankofanthos.balancereader.PostgresLedgerFixture.awaitPollerCaughtUp;
import static anthos.samples.bankofanthos.balancereader.PostgresLedgerFixture.insert;
import static anthos.samples.bankofanthos.balancereader.PostgresLedgerFixture.newAccount;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

/**
 * Full balancereader (HTTP, JWT, Guava cache, ledger poller, JPA queries)
 * against a real PostgreSQL ledger-db with the production schema.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BalanceReaderPostgresIntegrationTest {

    private static final TestJwtKeys KEYS = TestJwtKeys.generate();
    private static final Duration POLL_WAIT = Duration.ofSeconds(10);

    @Container
    static final PostgreSQLContainer<?> LEDGER_DB = PostgresLedgerFixture.ledgerDb();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        PostgresLedgerFixture.register(registry, LEDGER_DB, KEYS);
    }

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private LedgerReader ledgerReader;

    private void settle() {
        awaitPollerCaughtUp(ledgerReader, jdbc);
    }
    @Autowired
    private TransactionRepository repository;

    private ResponseEntity<String> getBalance(String authorization, String account) {
        HttpHeaders headers = new HttpHeaders();
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return http.exchange("/balances/" + account, HttpMethod.GET,
            new HttpEntity<>(headers), String.class);
    }

    private long balanceOf(String account) {
        ResponseEntity<String> response =
            getBalance("Bearer " + KEYS.tokenFor(account), account);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        return Long.parseLong(response.getBody());
    }

    private Map<String, Object> ledgerTotals() {
        return jdbc.queryForMap(
            "SELECT COUNT(*) AS n, COALESCE(SUM(AMOUNT), 0) AS total FROM TRANSACTIONS");
    }

    @Test
    @DisplayName("Balance = local credits - local debits; same account number at another bank is excluded")
    void balanceIsLocalCreditsMinusLocalDebits() {
        String customer = newAccount();
        String payee = newAccount();
        String externalParty = newAccount();
        insert(jdbc, externalParty, EXTERNAL_ROUTING, customer, LOCAL_ROUTING, 10_000);
        insert(jdbc, customer, LOCAL_ROUTING, payee, LOCAL_ROUTING, 2_500);
        insert(jdbc, payee, LOCAL_ROUTING, customer, LOCAL_ROUTING, 300);
        insert(jdbc, customer, EXTERNAL_ROUTING, externalParty, EXTERNAL_ROUTING, 999);
        insert(jdbc, externalParty, EXTERNAL_ROUTING, customer, EXTERNAL_ROUTING, 777);
        settle();

        assertEquals(7_800L, balanceOf(customer));
        assertEquals(2_200L, balanceOf(payee));
    }

    @Test
    @DisplayName("Account with no ledger rows reports a zero balance")
    void accountWithoutTransactionsHasZeroBalance() {
        assertEquals(0L, balanceOf(newAccount()));
    }

    @Test
    @DisplayName("Account with only outgoing ledger rows reports the negative net balance")
    void debitOnlyAccountReportsNegativeBalance() {
        String customer = newAccount();
        insert(jdbc, customer, LOCAL_ROUTING, newAccount(), EXTERNAL_ROUTING, 500);

        assertEquals(-500L, repository.findBalance(customer, LOCAL_ROUTING));
    }

    @Test
    @DisplayName("Poller queries return only newer rows, in id order, and the latest id")
    void pollerQueriesReturnNewerRowsInOrder() {
        String a = newAccount();
        String b = newAccount();
        insert(jdbc, a, LOCAL_ROUTING, b, LOCAL_ROUTING, 1);
        long first = jdbc.queryForObject("SELECT MAX(TRANSACTION_ID) FROM TRANSACTIONS", Long.class);
        insert(jdbc, a, LOCAL_ROUTING, b, LOCAL_ROUTING, 2);
        insert(jdbc, b, LOCAL_ROUTING, a, LOCAL_ROUTING, 3);

        List<Transaction> newer = repository.findLatest(first);

        assertEquals(2, newer.size());
        assertEquals(List.of(first + 1, first + 2),
            newer.stream().map(Transaction::getTransactionId).toList());
        assertEquals(List.of(2, 3), newer.stream().map(Transaction::getAmount).toList());
        assertEquals(b, newer.get(1).getFromAccountNum());
        assertEquals(first + 2, repository.latestTransactionId());
    }

    @Test
    @DisplayName("Token for another customer cannot read a balance (401) and the ledger is untouched")
    void otherCustomersBalanceIsRejected() {
        String victim = newAccount();
        insert(jdbc, newAccount(), EXTERNAL_ROUTING, victim, LOCAL_ROUTING, 50_000);
        Map<String, Object> before = ledgerTotals();

        ResponseEntity<String> response =
            getBalance("Bearer " + KEYS.tokenFor(newAccount()), victim);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("not authorized", response.getBody());
        assertEquals(before, ledgerTotals());
    }

    @Test
    @DisplayName("Missing Authorization header is rejected with 400")
    void missingAuthorizationHeaderIsRejected() {
        assertEquals(HttpStatus.BAD_REQUEST, getBalance(null, newAccount()).getStatusCode());
    }

    @Test
    @DisplayName("Forged, expired and malformed tokens are rejected with 401")
    void invalidTokensAreRejected() {
        String customer = newAccount();
        String forged = TestJwtKeys.generate().tokenFor(customer);
        String expired = KEYS.tokenFor(customer, Instant.now().minusSeconds(5));

        assertEquals(HttpStatus.UNAUTHORIZED, getBalance("Bearer " + forged, customer).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, getBalance("Bearer " + expired, customer).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, getBalance("Bearer garbage", customer).getStatusCode());
    }

    @Test
    @DisplayName("Empty bearer token is rejected with 401")
    void emptyBearerTokenIsRejected() {
        assertEquals(HttpStatus.UNAUTHORIZED, getBalance("Bearer ", newAccount()).getStatusCode());
    }

    @Test
    @DisplayName("Balance reads never write to the ledger")
    void readsDoNotModifyLedger() {
        String customer = newAccount();
        insert(jdbc, newAccount(), EXTERNAL_ROUTING, customer, LOCAL_ROUTING, 1_000);
        settle();
        Map<String, Object> before = ledgerTotals();

        for (int i = 0; i < 3; i++) {
            assertEquals(1_000L, balanceOf(customer));
        }
        getBalance("Bearer " + KEYS.tokenFor(newAccount()), customer);

        assertEquals(before, ledgerTotals());
    }

    @Test
    @DisplayName("Cached balance converges to the ledger after new credits and debits are written")
    void cachedBalanceTracksNewLedgerRows() {
        String customer = newAccount();
        String counterparty = newAccount();
        insert(jdbc, counterparty, EXTERNAL_ROUTING, customer, LOCAL_ROUTING, 5_000);
        settle();
        assertEquals(5_000L, balanceOf(customer));

        insert(jdbc, counterparty, EXTERNAL_ROUTING, customer, LOCAL_ROUTING, 1_000);
        insert(jdbc, customer, LOCAL_ROUTING, counterparty, LOCAL_ROUTING, 400);

        await().atMost(POLL_WAIT).until(() -> balanceOf(customer) == 5_600L);
        assertEquals(repository.findBalance(customer, LOCAL_ROUTING), balanceOf(customer),
            "cache and ledger agree");
        assertEquals(400L, balanceOf(counterparty));
    }

    @Test
    @DisplayName("Ledger rows for the same account number at another bank do not move the cached balance")
    void externalRoutingRowsDoNotMoveCachedBalance() {
        String customer = newAccount();
        String other = newAccount();
        insert(jdbc, other, EXTERNAL_ROUTING, customer, LOCAL_ROUTING, 2_000);
        settle();
        assertEquals(2_000L, balanceOf(customer));

        insert(jdbc, other, EXTERNAL_ROUTING, customer, EXTERNAL_ROUTING, 9_000);
        insert(jdbc, customer, EXTERNAL_ROUTING, other, EXTERNAL_ROUTING, 300);
        insert(jdbc, other, EXTERNAL_ROUTING, customer, LOCAL_ROUTING, 1);

        await().atMost(POLL_WAIT).until(() -> balanceOf(customer) != 2_000L);
        assertEquals(2_001L, balanceOf(customer));
    }

    @Test
    @DisplayName("Probes: ready, healthy (poller alive) and version")
    void probes() {
        assertEquals("ok", http.getForObject("/ready", String.class));
        ResponseEntity<String> healthy = http.getForEntity("/healthy", String.class);
        assertEquals(HttpStatus.OK, healthy.getStatusCode());
        assertEquals("ok", healthy.getBody());
        assertEquals("integration-test", http.getForObject("/version", String.class));
    }

    @Test
    @DisplayName("Ledger schema is append-only: UPDATE and DELETE leave rows unchanged")
    void ledgerSchemaIsAppendOnly() {
        String customer = newAccount();
        insert(jdbc, newAccount(), EXTERNAL_ROUTING, customer, LOCAL_ROUTING, 3_000);

        jdbc.update("UPDATE TRANSACTIONS SET AMOUNT = 1 WHERE TO_ACCT = ?", customer);
        jdbc.update("DELETE FROM TRANSACTIONS WHERE TO_ACCT = ?", customer);
        settle();

        assertEquals(3_000L, repository.findBalance(customer, LOCAL_ROUTING));
        assertNull(repository.findBalance(newAccount(), LOCAL_ROUTING),
            "no rows -> NULL from SQL, mapped to 0 by the cache loader");
        assertTrue(balanceOf(customer) == 3_000L);
    }
}
