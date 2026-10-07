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

import static anthos.samples.bankofanthos.transactionhistory.TestFixtures.LOCAL_ROUTING;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * First view of an account while the LedgerReader is still behind the ledger (POLL_MS=1000 keeps
 * the window open deterministically). Each committed transaction must appear exactly once.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionHistoryFirstViewIntegrationTest {

    private static final String ALICE = "6000000001";
    private static final String BOB = "6000000002";
    private static final String SENTINEL = "6000000009";
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withCopyFileToContainer(
            MountableFile.forHostPath(Path.of("../ledger-db/initdb/0_init_tables.sql")),
            "/docker-entrypoint-initdb.d/0_init_tables.sql");

    private static final KeyPair KEYS = TestFixtures.rsaKeyPair();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path publicKey = Files.createTempFile("transactionhistory-firstview-it", ".pem");
        publicKey.toFile().deleteOnExit();
        TestFixtures.writePublicKeyPem(KEYS, publicKey);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("PUB_KEY_PATH", publicKey::toString);
        registry.add("LOCAL_ROUTING_NUM", () -> LOCAL_ROUTING);
        registry.add("POLL_MS", () -> 1000);
        registry.add("VERSION", () -> "integration-test");
        registry.add("PORT", () -> "0");
        registry.add("ENABLE_TRACING", () -> "false");
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    private void insert(String from, String to, int amount, int second) {
        jdbc.update("INSERT INTO TRANSACTIONS (FROM_ACCT, FROM_ROUTE, TO_ACCT, TO_ROUTE, AMOUNT, TIMESTAMP) "
            + "VALUES (?, ?, ?, ?, ?, ?)", from, LOCAL_ROUTING, to, LOCAL_ROUTING, amount,
            Timestamp.from(TestFixtures.BASE_TIME.plusSeconds(second)));
    }

    private List<Integer> amounts(String account) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestFixtures.validToken(KEYS, account));
        String body = http.exchange("/transactions/" + account, HttpMethod.GET,
            new HttpEntity<>(headers), String.class).getBody();
        List<Integer> out = new ArrayList<>();
        json.readTree(body).forEach(n -> out.add(n.get("amount").asInt()));
        return out;
    }

    @Test
    @DisplayName("Payment committed just before the account's first view appears exactly once")
    void paymentBeforeFirstViewAppearsOnce() throws Exception {
        assertThat(amounts(SENTINEL)).isEmpty();
        insert(BOB, ALICE, 4200, 1);

        assertThat(amounts(ALICE)).containsExactly(4200);

        // Rows stream in id order: once the sentinel's later row arrives, the payment was delivered too.
        insert(BOB, SENTINEL, 1, 2);
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (amounts(SENTINEL).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(amounts(SENTINEL)).containsExactly(1);
        assertThat(amounts(ALICE)).containsExactly(4200);
    }
}
