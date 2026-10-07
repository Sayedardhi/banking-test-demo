/*
 * Copyright 2026, Google LLC.
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

import static anthos.samples.bankofanthos.balancereader.TestTransactions.EXTERNAL_ROUTING;
import static anthos.samples.bankofanthos.balancereader.TestTransactions.LOCAL_ROUTING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
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
 * HTTP -> JWT verification -> cache -> JPA -> PostgreSQL, with the real ledger schema. Each test uses its own
 * synthetic account numbers because the ledger is append-only (UPDATE/DELETE rules) and balances are cached.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BalanceReaderIntegrationTest {

    private static final AtomicLong NEXT_ACCOUNT = new AtomicLong(2_000_000_000L);

    @Container
    static final PostgreSQLContainer<?> LEDGER = LedgerDatabase.container();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        LedgerDatabase.register(registry, LEDGER);
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    private static String account() {
        return Long.toString(NEXT_ACCOUNT.incrementAndGet());
    }

    private ResponseEntity<String> get(String path, String authorization) {
        HttpHeaders headers = new HttpHeaders();
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> balanceAs(String account) {
        return get("/balances/" + account, TestTokens.bearer(TestTokens.token(account)));
    }

    private void transfer(String from, String to, int cents) {
        LedgerDatabase.insert(jdbc, from, LOCAL_ROUTING, to, LOCAL_ROUTING, cents);
    }

    @Test
    @DisplayName("Balance = credits - debits for the account at this bank's routing number")
    void creditsMinusDebits() {
        String customer = account();
        String payee = account();
        LedgerDatabase.insert(jdbc, "9099791699", EXTERNAL_ROUTING, customer, LOCAL_ROUTING, 100_000);
        transfer(customer, payee, 2_550);
        transfer(payee, customer, 50);
        transfer(customer, payee, 1);

        ResponseEntity<String> response = balanceAs(customer);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Long.toString(100_000 - 2_550 + 50 - 1));
        assertThat(balanceAs(payee).getBody()).isEqualTo(Long.toString(2_550 - 50 + 1));
    }

    @Test
    @DisplayName("Rows for the same account number at another routing number are not counted")
    void otherBanksRowsExcluded() {
        String customer = account();
        LedgerDatabase.insert(jdbc, "9099791699", EXTERNAL_ROUTING, customer, LOCAL_ROUTING, 5_000);
        LedgerDatabase.insert(jdbc, "9099791699", EXTERNAL_ROUTING, customer, EXTERNAL_ROUTING, 999_999);
        LedgerDatabase.insert(jdbc, customer, EXTERNAL_ROUTING, "9099791699", EXTERNAL_ROUTING, 777);

        assertThat(balanceAs(customer).getBody()).isEqualTo("5000");
    }

    @Test
    @DisplayName("An account with no ledger rows has a zero balance")
    void noRowsIsZero() {
        ResponseEntity<String> response = balanceAs(account());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("0");
    }

    @Test
    @DisplayName("An account with debits but no credits reports the negative balance, not 0")
    void debitsOnlyAreNegative() {
        String customer = account();
        transfer(customer, account(), 500);

        assertThat(balanceAs(customer).getBody()).isEqualTo("-500");
    }

    @Test
    @DisplayName("New ledger rows reach an already-cached balance through the ledger reader")
    void cachedBalanceFollowsLedger() {
        String customer = account();
        String payee = account();
        assertThat(balanceAs(customer).getBody()).isEqualTo("0");
        assertThat(balanceAs(payee).getBody()).isEqualTo("0");

        LedgerDatabase.insert(jdbc, "9099791699", EXTERNAL_ROUTING, customer, LOCAL_ROUTING, 10_000);
        LedgerDatabase.insert(jdbc, "9099791699", EXTERNAL_ROUTING, payee, LOCAL_ROUTING, 1_000);
        awaitBalance(customer, 10_000);
        awaitBalance(payee, 1_000);

        transfer(customer, payee, 2_500);
        transfer(payee, customer, 1);
        LedgerDatabase.insert(jdbc, customer, LOCAL_ROUTING, "9099791699", EXTERNAL_ROUTING, 300);

        awaitBalance(customer, 10_000 - 2_500 + 1 - 300);
        awaitBalance(payee, 1_000 + 2_500 - 1);
    }

    private void awaitBalance(String account, long expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(balanceAs(account).getBody()).isEqualTo(Long.toString(expected)));
    }

    @Test
    @DisplayName("A valid token for one account cannot read another account's balance")
    void crossAccountReadRejected() {
        String victim = account();
        LedgerDatabase.insert(jdbc, "9099791699", EXTERNAL_ROUTING, victim, LOCAL_ROUTING, 42_000);

        ResponseEntity<String> response = get("/balances/" + victim, TestTokens.bearer(TestTokens.token(account())));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo("not authorized").doesNotContain("42000");
    }

    @Test
    @DisplayName("Forged, tampered, unsigned and expired tokens are rejected with 401")
    void invalidTokensRejected() {
        String customer = account();
        String[] tokens = {
            TestTokens.token(TestTokens.OTHER_SIGNER, customer, java.time.Instant.now().plusSeconds(3600)),
            TestTokens.tamperedToken(account(), customer),
            TestTokens.unsignedToken(customer),
            TestTokens.token(TestTokens.SIGNER, customer, java.time.Instant.now().minusSeconds(60)),
            "not-a-jwt",
        };
        for (String token : tokens) {
            ResponseEntity<String> response = get("/balances/" + customer, TestTokens.bearer(token));
            assertThat(response.getStatusCode()).as(token).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isEqualTo("not authorized");
        }
    }

    @Test
    @DisplayName("A request without an Authorization header is rejected as a client error")
    void missingHeader() {
        ResponseEntity<String> response = get("/balances/" + account(), null);

        assertThat(response.getStatusCode().is4xxClientError()).as(response.getStatusCode().toString()).isTrue();
    }

    @Test
    @DisplayName("An empty bearer token ('Bearer ') is rejected with 401, not a server error")
    void emptyBearerIs401() {
        ResponseEntity<String> response = get("/balances/" + account(), "Bearer ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Probes: /ready and /healthy return ok, /version returns VERSION")
    void probes() {
        assertThat(get("/ready", null).getBody()).isEqualTo("ok");
        ResponseEntity<String> healthy = get("/healthy", null);
        assertThat(healthy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(healthy.getBody()).isEqualTo("ok");
        assertThat(get("/version", null).getBody()).isEqualTo("v-integration");
    }
}
