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

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.sql.Timestamp;
import java.time.Instant;
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
 * Dependency failure: the ledger database becomes unavailable while the service is running.
 * Uses its own container (stopped mid-test) so other integration tests are unaffected.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionHistoryDatabaseOutageIntegrationTest {

    private static final String ALICE = "5000000001";
    private static final String BOB = "5000000002";
    private static final String UNCACHED = "5000000003";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withCopyFileToContainer(
            MountableFile.forHostPath(Path.of("../ledger-db/initdb/0_init_tables.sql")),
            "/docker-entrypoint-initdb.d/0_init_tables.sql");

    private static final KeyPair KEYS = TestFixtures.rsaKeyPair();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path publicKey = Files.createTempFile("transactionhistory-outage-it", ".pem");
        publicKey.toFile().deleteOnExit();
        TestFixtures.writePublicKeyPem(KEYS, publicKey);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
        registry.add("PUB_KEY_PATH", publicKey::toString);
        registry.add("LOCAL_ROUTING_NUM", () -> LOCAL_ROUTING);
        registry.add("POLL_MS", () -> 50);
        registry.add("VERSION", () -> "integration-test");
        registry.add("PORT", () -> "0");
        registry.add("ENABLE_TRACING", () -> "false");
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;

    private ResponseEntity<String> history(String account) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestFixtures.validToken(KEYS, account));
        return http.exchange("/transactions/" + account, HttpMethod.GET, new HttpEntity<>(headers),
            String.class);
    }

    @Test
    @DisplayName("Ledger DB outage: cached history still served, uncached read returns 500 'cache error'")
    void ledgerOutage() {
        jdbc.update("INSERT INTO TRANSACTIONS (FROM_ACCT, FROM_ROUTE, TO_ACCT, TO_ROUTE, AMOUNT, TIMESTAMP) "
            + "VALUES (?, ?, ?, ?, ?, ?)", BOB, LOCAL_ROUTING, ALICE, LOCAL_ROUTING, 4200,
            Timestamp.from(Instant.parse("2026-01-15T10:00:00Z")));
        ResponseEntity<String> warm = history(ALICE);
        assertThat(warm.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(warm.getBody()).contains("\"amount\":4200");

        POSTGRES.stop();

        ResponseEntity<String> cached = history(ALICE);
        assertThat(cached.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cached.getBody()).isEqualTo(warm.getBody());

        ResponseEntity<String> uncached = history(UNCACHED);
        assertThat(uncached.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(uncached.getBody()).isEqualTo("cache error");
    }
}
