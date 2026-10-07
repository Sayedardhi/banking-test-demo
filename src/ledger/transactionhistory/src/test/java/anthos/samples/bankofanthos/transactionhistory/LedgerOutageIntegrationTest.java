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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Ledger database failure modes with a real (paused / reset) PostgreSQL. Ordered because the last
 * test permanently stops the context's background reader.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LedgerOutageIntegrationTest extends LedgerIntegrationSupport {

    static final PostgreSQLContainer<?> DB = newLedgerDb();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        register(r, DB, 100, 100);
    }

    @AfterAll
    static void stopDb() {
        DB.stop();
    }

    @Autowired
    MockMvc mvc;

    private final Instant t0 = Instant.parse("2026-02-01T09:00:00Z");

    private void pause() {
        DB.getDockerClient().pauseContainerCmd(DB.getContainerId()).exec();
    }

    private void unpause() {
        DB.getDockerClient().unpauseContainerCmd(DB.getContainerId()).exec();
    }

    @Test
    @Order(1)
    @DisplayName("Ledger outage: cached history still served, uncached account gets 500 'cache error', recovers afterwards")
    void outageServesCacheAndFailsClosedForMisses() throws Exception {
        String cached = "3000000001";
        String uncached = "3000000002";
        long row = insert("9990000001", X, cached, L, 10_000, t0);
        insert("9990000001", X, uncached, L, 20_000, t0);
        awaitReaderCaughtUp();
        mvc.perform(get("/transactions/" + cached).header("Authorization", bearer(cached)))
            .andExpect(jsonPath("$", hasSize(1)));

        pause();
        try {
            mvc.perform(get("/transactions/" + cached).header("Authorization", bearer(cached)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].transactionId").value(row));
            mvc.perform(get("/transactions/" + uncached).header("Authorization", bearer(uncached)))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("cache error"));
            mvc.perform(get("/healthy")).andExpect(status().isOk());
        } finally {
            unpause();
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            mvc.perform(get("/transactions/" + uncached).header("Authorization", bearer(uncached)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amount").value(20_000)));
        long after = insert("3000000002", L, cached, L, 5, t0.plusSeconds(5));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            mvc.perform(get("/transactions/" + cached).header("Authorization", bearer(cached)))
                .andExpect(jsonPath("$[0].transactionId").value(after)));
    }

    @Test
    @Order(2)
    @DisplayName("Ledger reset (head moves backwards) stops the reader and /healthy reports 500 so the pod restarts")
    void ledgerResetFailsLiveness() throws Exception {
        insert("3000000011", L, "3000000012", L, 1, t0.plusSeconds(60));
        awaitReaderCaughtUp();
        mvc.perform(get("/healthy")).andExpect(status().isOk());

        jdbc.execute("TRUNCATE transactions RESTART IDENTITY");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            mvc.perform(get("/healthy"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("Ledger reader not healthy")));
    }
}
