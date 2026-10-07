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

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Dependency-failure behaviour against a dedicated PostgreSQL container:
 * a temporary ledger-db outage (container paused) and a ledger reset.
 * Each test gets a fresh application context (and poller) on a shared
 * container, so one outage cannot leave the next test with a dead poller.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LedgerAvailabilityPostgresIntegrationTest {

    private static final TestJwtKeys KEYS = TestJwtKeys.generate();
    private static final Duration WAIT = Duration.ofSeconds(20);

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

    private ResponseEntity<String> getBalance(String account) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(KEYS.tokenFor(account));
        return http.exchange("/balances/" + account, HttpMethod.GET,
            new HttpEntity<>(headers), String.class);
    }

    private HttpStatus health() {
        return HttpStatus.valueOf(
            http.getForEntity("/healthy", String.class).getStatusCode().value());
    }

    private static void pauseLedgerDb() {
        DockerClientFactory.instance().client()
            .pauseContainerCmd(LEDGER_DB.getContainerId()).exec();
    }

    private static void unpauseLedgerDb() {
        DockerClientFactory.instance().client()
            .unpauseContainerCmd(LEDGER_DB.getContainerId()).exec();
    }

    @Test
    @Order(1)
    @DisplayName("During a ledger-db outage an uncached balance returns 500, never a guessed value")
    void outageReturnsServerErrorForUncachedBalance() {
        String customer = newAccount();
        insert(jdbc, newAccount(), EXTERNAL_ROUTING, customer, LOCAL_ROUTING, 4_000);
        pauseLedgerDb();
        try {
            ResponseEntity<String> response = getBalance(customer);
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertEquals("cache error", response.getBody());
        } finally {
            unpauseLedgerDb();
        }
        await().atMost(WAIT).until(() -> getBalance(customer).getStatusCode() == HttpStatus.OK);
        assertEquals("4000", getBalance(customer).getBody());
    }

    @Test
    @Order(2)
    @DisplayName("After a transient ledger-db outage the service never reports healthy while serving a stale balance")
    void transientOutageNeverLeavesHealthyButStaleBalance() throws Exception {
        String customer = newAccount();
        String counterparty = newAccount();
        insert(jdbc, counterparty, EXTERNAL_ROUTING, customer, LOCAL_ROUTING, 1_000);
        settle();
        assertEquals("1000", getBalance(customer).getBody());

        pauseLedgerDb();
        try {
            Thread.sleep(5_000);
        } finally {
            unpauseLedgerDb();
        }
        insert(jdbc, counterparty, EXTERNAL_ROUTING, customer, LOCAL_ROUTING, 250);

        // Documented contract: either the poller recovers and the cached balance
        // converges, or /healthy reports 500 so the orchestrator restarts the pod.
        await().atMost(WAIT).until(() -> health() == HttpStatus.INTERNAL_SERVER_ERROR
            || "1250".equals(getBalance(customer).getBody()));
        if (health() == HttpStatus.OK) {
            assertEquals("1250", getBalance(customer).getBody());
        }
    }

    @Test
    @Order(3)
    @DisplayName("Ledger reset (ids move backwards) stops the poller and liveness reports 500")
    void ledgerResetMarksServiceUnhealthy() {
        String customer = newAccount();
        insert(jdbc, newAccount(), EXTERNAL_ROUTING, customer, LOCAL_ROUTING, 100);
        settle();
        assertEquals("100", getBalance(customer).getBody());
        insert(jdbc, newAccount(), EXTERNAL_ROUTING, customer, LOCAL_ROUTING, 1);
        await().atMost(WAIT).until(() -> "101".equals(getBalance(customer).getBody()));
        assertEquals(HttpStatus.OK, health(), "precondition: poller healthy before reset");

        jdbc.execute("TRUNCATE TABLE TRANSACTIONS RESTART IDENTITY");

        await().atMost(WAIT).until(() -> health() == HttpStatus.INTERNAL_SERVER_ERROR);
        assertEquals("Ledger reader not healthy",
            http.getForEntity("/healthy", String.class).getBody());
    }
}
