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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.google.common.cache.LoadingCache;
import io.micrometer.core.instrument.Clock;
import io.micrometer.stackdriver.StackdriverConfig;
import io.micrometer.stackdriver.StackdriverMeterRegistry;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * GET /transactions/{accountId} authorization and error handling with a real RS256 verifier and a
 * real Guava cache; only the ledger repository and the background reader are mocked.
 */
class TransactionHistoryControllerAuthorizationTest {

    private static final String OWN = "1011226111";
    private static final String OTHER = "1033623433";

    private static KeyPair keys;
    private static KeyPair attacker;

    @TempDir
    static Path dir;

    private TransactionRepository repo;
    private TransactionHistoryController controller;

    @BeforeAll
    static void keys() {
        keys = TestFixtures.newRsaKeyPair();
        attacker = TestFixtures.newRsaKeyPair();
    }

    static StackdriverMeterRegistry disabledRegistry() {
        return new StackdriverMeterRegistry(new StackdriverConfig() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public String projectId() {
                return "test";
            }

            @Override
            public String get(String key) {
                return null;
            }
        }, Clock.SYSTEM);
    }

    @BeforeEach
    void setUp() {
        repo = mock(TransactionRepository.class);
        TransactionCache cacheConfig = new TransactionCache();
        ReflectionTestUtils.setField(cacheConfig, "dbRepo", repo);
        LoadingCache<String, Deque<Transaction>> cache =
            cacheConfig.initializeCache(100, 60, TestFixtures.LOCAL_ROUTING, 100);
        String keyPath = TestFixtures.writePublicKeyPem(keys, dir).toString();
        controller = new TransactionHistoryController(mock(LedgerReader.class), disabledRegistry(),
            new JWTVerifierGenerator().generateJWTVerifier(keyPath), keyPath, cache,
            TestFixtures.LOCAL_ROUTING, "test");
        ReflectionTestUtils.setField(controller, "historyLimit", 100);
    }

    private LinkedList<Transaction> ledgerHas(String account, Transaction... rows) {
        LinkedList<Transaction> list = new LinkedList<>(List.of(rows));
        when(repo.findForAccount(eq(account), eq(TestFixtures.LOCAL_ROUTING), any(Pageable.class))).thenReturn(list);
        return list;
    }

    private void assertUnauthorizedWithoutLedgerRead(ResponseEntity<?> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo("not authorized");
        verify(repo, never()).findForAccount(anyString(), anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("Owner with a valid token gets their ledger history, newest first as stored")
    void ownerGetsHistory() {
        Transaction credit = TestFixtures.transaction(9, OTHER, TestFixtures.LOCAL_ROUTING, OWN, TestFixtures.LOCAL_ROUTING, 7500);
        Transaction debit = TestFixtures.transaction(8, OWN, TestFixtures.LOCAL_ROUTING, OTHER, TestFixtures.LOCAL_ROUTING, 1200);
        ledgerHas(OWN, credit, debit);

        ResponseEntity<?> response = controller.getTransactions("Bearer " + TestFixtures.validToken(keys, OWN), OWN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Iterable<Object>) response.getBody()).containsExactly(credit, debit);
    }

    @Test
    @DisplayName("Second request is served from cache (ledger read once)")
    void secondRequestServedFromCache() {
        ledgerHas(OWN);
        String header = "Bearer " + TestFixtures.validToken(keys, OWN);

        controller.getTransactions(header, OWN);
        ResponseEntity<?> response = controller.getTransactions(header, OWN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(repo, times(1)).findForAccount(anyString(), anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("Valid token for account A cannot read account B (horizontal privilege escalation)")
    void otherAccountRejected() {
        ledgerHas(OTHER, TestFixtures.transaction(1, OTHER, TestFixtures.LOCAL_ROUTING, OWN, TestFixtures.LOCAL_ROUTING, 1));

        assertUnauthorizedWithoutLedgerRead(
            controller.getTransactions("Bearer " + TestFixtures.validToken(keys, OWN), OTHER));
    }

    @Test
    @DisplayName("Account id that differs only by whitespace/prefix is not treated as the same account")
    void nearMatchAccountRejected() {
        String header = "Bearer " + TestFixtures.validToken(keys, OWN);

        assertUnauthorizedWithoutLedgerRead(controller.getTransactions(header, " " + OWN));
        assertUnauthorizedWithoutLedgerRead(controller.getTransactions(header, OWN.substring(1)));
    }

    @Test
    @DisplayName("Token signed by an unknown key is rejected before any ledger read")
    void forgedTokenRejected() {
        assertUnauthorizedWithoutLedgerRead(
            controller.getTransactions("Bearer " + TestFixtures.validToken(attacker, OWN), OWN));
    }

    @Test
    @DisplayName("Expired token is rejected")
    void expiredTokenRejected() {
        String expired = TestFixtures.token(keys, OWN, Instant.now().minusSeconds(1));

        assertUnauthorizedWithoutLedgerRead(controller.getTransactions("Bearer " + expired, OWN));
    }

    @Test
    @DisplayName("Correctly signed token without an acct claim is rejected")
    void tokenWithoutAccountClaimRejected() {
        String noAcct = JWT.create().withSubject("synthetic-user")
            .withExpiresAt(java.util.Date.from(Instant.now().plusSeconds(60)))
            .sign(Algorithm.RSA256((RSAPublicKey) keys.getPublic(), (RSAPrivateKey) keys.getPrivate()));

        assertUnauthorizedWithoutLedgerRead(controller.getTransactions("Bearer " + noAcct, OWN));
    }

    @Test
    @DisplayName("Malformed tokens (garbage, 'Bearer' with no space, wrong scheme) get 401")
    void malformedTokensRejected() {
        assertUnauthorizedWithoutLedgerRead(controller.getTransactions("Bearer not-a-jwt", OWN));
        assertUnauthorizedWithoutLedgerRead(controller.getTransactions("Bearer", OWN));
        assertUnauthorizedWithoutLedgerRead(controller.getTransactions("Basic dXNlcjpwYXNz", OWN));
    }

    @Test
    @DisplayName("'Bearer ' with empty credentials is an authentication failure (401), not a server error")
    void emptyBearerCredentialsRejected() {
        assertUnauthorizedWithoutLedgerRead(controller.getTransactions("Bearer ", OWN));
    }

    @Test
    @DisplayName("Ledger outage on a cache miss returns 500 'cache error' (no partial or empty history)")
    void ledgerOutageReturns500() {
        when(repo.findForAccount(anyString(), anyString(), any(Pageable.class)))
            .thenThrow(new DataAccessResourceFailureException("ledger-db down"));

        ResponseEntity<?> response = controller.getTransactions("Bearer " + TestFixtures.validToken(keys, OWN), OWN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isEqualTo("cache error");
    }

    @Test
    @DisplayName("EXTRA_LATENCY_MILLIS delays the authorized response")
    void extraLatencyApplied() {
        ledgerHas(OWN);
        ReflectionTestUtils.setField(controller, "extraLatencyMillis", 150);

        long start = System.nanoTime();
        ResponseEntity<?> response = controller.getTransactions("Bearer " + TestFixtures.validToken(keys, OWN), OWN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(150);
    }
}
