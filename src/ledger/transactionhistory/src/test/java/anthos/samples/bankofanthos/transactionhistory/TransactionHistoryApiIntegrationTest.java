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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.common.cache.LoadingCache;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
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

/** GET /transactions/{accountId} end to end: real JWT verification, real ledger queries. */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionHistoryApiIntegrationTest extends LedgerIntegrationSupport {

    static final int HISTORY_LIMIT = 5;
    static final PostgreSQLContainer<?> DB = newLedgerDb();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        register(r, DB, 100, HISTORY_LIMIT);
    }

    @AfterAll
    static void stopDb() {
        DB.stop();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    LoadingCache<String, Deque<Transaction>> cache;

    private final Instant t0 = Instant.parse("2026-01-15T10:00:00Z");

    @Test
    @DisplayName("Owner sees debits and credits newest first; other customers and other banks' same-number accounts are excluded")
    void historyContainsOnlyOwnersRowsNewestFirst() throws Exception {
        String me = "2000000001";
        String friend = "2000000002";
        long deposit = insert("9990000001", X, me, L, 100_000, t0);
        long payment = insert(me, L, friend, L, 2_550, t0.plusSeconds(60));
        long refund = insert(friend, L, me, L, 1, t0.plusSeconds(120));
        insert(friend, L, "2000000003", L, 777, t0.plusSeconds(130));
        insert(me, X, "2000000003", X, 888, t0.plusSeconds(140));
        insert("2000000003", X, me, X, 999, t0.plusSeconds(150));
        awaitReaderCaughtUp();

        mvc.perform(get("/transactions/" + me).header("Authorization", bearer(me)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(3)))
            .andExpect(jsonPath("$[0].transactionId").value(refund))
            .andExpect(jsonPath("$[0].amount").value(1))
            .andExpect(jsonPath("$[0].fromAccountNum").value(friend))
            .andExpect(jsonPath("$[0].toAccountNum").value(me))
            .andExpect(jsonPath("$[1].transactionId").value(payment))
            .andExpect(jsonPath("$[1].amount").value(2_550))
            .andExpect(jsonPath("$[1].toRoutingNum").value(L))
            .andExpect(jsonPath("$[2].transactionId").value(deposit))
            .andExpect(jsonPath("$[2].fromRoutingNum").value(X));
    }

    @Test
    @DisplayName("HISTORY_LIMIT returns only the newest N rows from the ledger")
    void historyLimitAppliedToLedgerQuery() throws Exception {
        String me = "2000000011";
        long newest = 0;
        for (int i = 0; i < HISTORY_LIMIT + 3; i++) {
            newest = insert("2000000012", L, me, L, 100 + i, t0.plusSeconds(i));
        }
        awaitReaderCaughtUp();

        mvc.perform(get("/transactions/" + me).header("Authorization", bearer(me)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(HISTORY_LIMIT)))
            .andExpect(jsonPath("$[0].transactionId").value(newest))
            .andExpect(jsonPath("$[4].amount").value(103));
    }

    @Test
    @DisplayName("Payment committed after the history was cached shows up for sender and recipient without a reload")
    void newPaymentReachesBothCachedHistories() throws Exception {
        String alice = "2000000021";
        String bob = "2000000022";
        insert("9990000001", X, alice, L, 50_000, t0);
        awaitReaderCaughtUp();
        mvc.perform(get("/transactions/" + alice).header("Authorization", bearer(alice))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/transactions/" + bob).header("Authorization", bearer(bob))).andExpect(jsonPath("$", hasSize(0)));

        long payment = insert(alice, L, bob, L, 12_345, t0.plusSeconds(10));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            mvc.perform(get("/transactions/" + bob).header("Authorization", bearer(bob)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].transactionId").value(payment)));
        mvc.perform(get("/transactions/" + alice).header("Authorization", bearer(alice)))
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].transactionId").value(payment))
            .andExpect(jsonPath("$[0].amount").value(12_345));
    }

    @Test
    @DisplayName("Token for another customer is rejected: 401, no history in the body, nothing cached, ledger unchanged")
    void crossAccountReadRejectedWithoutSideEffects() throws Exception {
        String victim = "2000000031";
        insert("9990000001", X, victim, L, 9_999_99, t0);
        awaitReaderCaughtUp();
        int rows = ledgerRows();

        mvc.perform(get("/transactions/" + victim).header("Authorization", bearer("2000000032")))
            .andExpect(status().isUnauthorized())
            .andExpect(content().string("not authorized"));

        assertThat(cache.asMap()).doesNotContainKey(victim);
        assertThat(ledgerRows()).isEqualTo(rows);
    }

    @Test
    @DisplayName("Forged, expired and missing credentials never return history")
    void badCredentialsRejected() throws Exception {
        String victim = "2000000041";
        insert("9990000001", X, victim, L, 4_200, t0);
        awaitReaderCaughtUp();
        String forged = "Bearer " + TestFixtures.validToken(TestFixtures.newRsaKeyPair(), victim);
        String expired = "Bearer " + TestFixtures.token(KEYS, victim, Instant.now().minusSeconds(5));

        mvc.perform(get("/transactions/" + victim).header("Authorization", forged))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/transactions/" + victim).header("Authorization", expired))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/transactions/" + victim))
            .andExpect(status().is4xxClientError())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("4200"))));
        assertThat(cache.asMap()).doesNotContainKey(victim);
    }

    @Test
    @DisplayName("'Authorization: Bearer ' with empty credentials is rejected as 401, not a 500")
    void emptyBearerRejectedAs401() throws Exception {
        mvc.perform(get("/transactions/2000000051").header("Authorization", "Bearer "))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Probes: /ready, /healthy and /version")
    void probes() throws Exception {
        mvc.perform(get("/ready")).andExpect(status().isOk()).andExpect(content().string("ok"));
        mvc.perform(get("/healthy")).andExpect(status().isOk()).andExpect(content().string("ok"));
        mvc.perform(get("/version")).andExpect(status().isOk()).andExpect(content().string("it"));
    }
}
