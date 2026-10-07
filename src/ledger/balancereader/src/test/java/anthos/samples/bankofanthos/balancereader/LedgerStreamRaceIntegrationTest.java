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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A balance read that lands between a ledger insert and the next ledger-reader poll. POLL_MS is widened to
 * 3 s and the HTTP path is warmed up first, so the read reliably lands inside that window (production default: 100 ms).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "POLL_MS=3000")
class LedgerStreamRaceIntegrationTest {

    private static final String CUSTOMER = "4000000001";

    @Container
    static final PostgreSQLContainer<?> LEDGER = LedgerDatabase.container();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        LedgerDatabase.register(registry, LEDGER);
        registry.add("POLL_MS", () -> "3000");
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    private ResponseEntity<String> balance() {
        return balance(CUSTOMER);
    }

    private ResponseEntity<String> balance(String account) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, TestTokens.bearer(TestTokens.token(account)));
        return http.exchange("/balances/" + account, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("A deposit read before the ledger reader polls it is not counted twice once the poll arrives")
    void depositIsCountedOnce() {
        assertThat(balance("4000000099").getStatusCode()).isEqualTo(HttpStatus.OK);
        LedgerDatabase.insert(jdbc, "9099791699", EXTERNAL_ROUTING, CUSTOMER, LOCAL_ROUTING, 5_000);

        ResponseEntity<String> first = balance();
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).isEqualTo("5000");

        // Hold the expectation across two poll intervals so the reader has delivered the deposit.
        await().during(Duration.ofSeconds(7)).atMost(Duration.ofSeconds(9))
            .until(() -> "5000".equals(balance().getBody()));
    }
}
