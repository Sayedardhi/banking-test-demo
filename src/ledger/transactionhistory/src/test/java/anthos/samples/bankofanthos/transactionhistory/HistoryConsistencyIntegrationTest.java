/*
 * Copyright 2026 Google LLC
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

package anthos.samples.bankofanthos.transactionhistory;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Cache/ledger consistency when a customer opens their history between a ledger commit and the
 * reader's next poll (e.g. right after making a payment). POLL_MS is long so the window is
 * deterministic: a sentinel row marks the moment just after a poll.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HistoryConsistencyIntegrationTest extends LedgerIntegrationSupport {

    static final int POLL_MS = 4000;
    static final PostgreSQLContainer<?> DB = newLedgerDb();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        register(r, DB, POLL_MS, 100);
    }

    @AfterAll
    static void stopDb() {
        DB.stop();
    }

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("History opened right after a payment is committed lists that payment exactly once")
    void paymentListedOnceWhenHistoryOpenedBeforeNextPoll() throws Exception {
        Instant t0 = Instant.parse("2026-03-01T12:00:00Z");
        String sentinel = "4000000099";
        mvc.perform(get("/transactions/" + sentinel).header("Authorization", bearer(sentinel)))
            .andExpect(jsonPath("$", hasSize(0)));
        long marker = insert("9990000001", X, sentinel, L, 1, t0);
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
            mvc.perform(get("/transactions/" + sentinel).header("Authorization", bearer(sentinel)))
                .andExpect(jsonPath("$[0].transactionId").value(marker)));
        // A poll just happened; the next one is ~POLL_MS away.

        String customer = "4000000001";
        long payment = insert(customer, L, "4000000002", L, 2_500, t0.plusSeconds(1));
        mvc.perform(get("/transactions/" + customer).header("Authorization", bearer(customer)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)));

        awaitReaderCaughtUp();

        mvc.perform(get("/transactions/" + customer).header("Authorization", bearer(customer)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].transactionId").value(payment))
            .andExpect(jsonPath("$", hasSize(1)));
    }
}
