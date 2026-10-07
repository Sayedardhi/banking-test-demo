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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Ledger database outage: the database container is stopped mid-test (real connection failure, not a mock). */
@Testcontainers
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerOutageIntegrationTest {

    private static final String CACHED = "3000000001";
    private static final String UNCACHED = "3000000002";

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

    private ResponseEntity<String> balanceAs(String account) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.token(account)));
        return http.exchange("/balances/" + account, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("Ledger down: uncached balances fail with 500 'cache error' (never $0); cached balances still served")
    void outage() {
        // Cache CACHED before its deposit exists so the balance arrives via the ledger reader only.
        assertThat(balanceAs(CACHED).getBody()).isEqualTo("0");
        LedgerDatabase.insert(jdbc, "9099791699", EXTERNAL_ROUTING, CACHED, LOCAL_ROUTING, 8_800);
        LedgerDatabase.insert(jdbc, "9099791699", EXTERNAL_ROUTING, UNCACHED, LOCAL_ROUTING, 6_600);
        await().atMost(Duration.ofSeconds(10)).until(() -> "8800".equals(balanceAs(CACHED).getBody()));

        LEDGER.stop();

        ResponseEntity<String> uncached = balanceAs(UNCACHED);
        assertThat(uncached.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(uncached.getBody()).isEqualTo("cache error");

        ResponseEntity<String> cached = balanceAs(CACHED);
        assertThat(cached.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cached.getBody()).isEqualTo("8800");

        HttpHeaders foreign = new HttpHeaders();
        foreign.set(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.token(CACHED)));
        ResponseEntity<String> crossAccount = http.exchange("/balances/" + UNCACHED, HttpMethod.GET,
            new HttpEntity<>(foreign), String.class);
        assertThat(crossAccount.getStatusCode()).as("authorization still enforced during an outage")
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
