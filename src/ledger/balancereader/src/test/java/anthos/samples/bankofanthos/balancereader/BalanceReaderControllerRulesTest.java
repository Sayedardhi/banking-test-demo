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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

import com.auth0.jwt.JWTVerifier;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.micrometer.core.instrument.Clock;
import io.micrometer.stackdriver.StackdriverConfig;
import io.micrometer.stackdriver.StackdriverMeterRegistry;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Account-ownership authorization and cache consistency rules of
 * BalanceReaderController. Uses a real RS256 verifier and a real Guava
 * cache; only the ledger (DB-backed loader) and the reader thread are
 * substituted.
 */
class BalanceReaderControllerRulesTest {

    private static final String LOCAL_ROUTING = "883745000";
    private static final String EXTERNAL_ROUTING = "808889588";
    private static final String ALICE = "1000000001";
    private static final String BOB = "1000000002";
    private static final String CAROL = "1000000003";

    private static final TestJwtKeys KEYS = TestJwtKeys.generate();

    @TempDir
    Path tempDir;

    private LedgerReader ledgerReader;
    private final Map<String, Long> ledgerBalances = new HashMap<>();
    private final List<String> ledgerLoads = new ArrayList<>();
    private RuntimeException ledgerFailure;
    private LoadingCache<String, Long> cache;
    private BalanceReaderController controller;
    private LedgerReaderCallback callback;

    @BeforeEach
    void setUp() {
        ledgerReader = mock(LedgerReader.class);
        ArgumentCaptor<LedgerReaderCallback> captor =
            ArgumentCaptor.forClass(LedgerReaderCallback.class);
        doNothing().when(ledgerReader).startWithCallback(captor.capture());

        cache = CacheBuilder.newBuilder().recordStats()
            .build(new CacheLoader<String, Long>() {
                @Override
                public Long load(String accountId) {
                    ledgerLoads.add(accountId);
                    if (ledgerFailure != null) {
                        throw ledgerFailure;
                    }
                    return ledgerBalances.getOrDefault(accountId, 0L);
                }
            });
        JWTVerifier verifier = new JWTVerifierGenerator().generateJWTVerifier(
            KEYS.writePublicKey(tempDir).toString());
        controller = new BalanceReaderController(ledgerReader, verifier,
            disabledRegistry(), cache, LOCAL_ROUTING, "v-test");
        callback = captor.getValue();
    }

    private static StackdriverMeterRegistry disabledRegistry() {
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

    private ResponseEntity<?> getBalance(String token, String account) {
        return controller.getBalance("Bearer " + token, account);
    }

    @Nested
    @DisplayName("Authorization: a customer may only read their own balance")
    class Authorization {

        @Test
        @DisplayName("Valid token for the requested account returns the ledger balance")
        void ownAccountReturnsLedgerBalance() {
            ledgerBalances.put(ALICE, 12_345L);

            ResponseEntity<?> response = getBalance(KEYS.tokenFor(ALICE), ALICE);

            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(12_345L, response.getBody());
        }

        @Test
        @DisplayName("Valid token for another account is rejected without reading that balance")
        void otherAccountIsRejectedWithoutLedgerRead() {
            ledgerBalances.put(BOB, 99_999L);

            ResponseEntity<?> response = getBalance(KEYS.tokenFor(ALICE), BOB);

            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
            assertEquals("not authorized", response.getBody());
            assertTrue(ledgerLoads.isEmpty(), "ledger must not be queried");
            assertNull(cache.getIfPresent(BOB), "victim balance must not be cached");
        }

        @Test
        @DisplayName("Token signed by an untrusted key is rejected")
        void forgedSignatureIsRejected() {
            String forged = TestJwtKeys.generate().tokenFor(ALICE);

            ResponseEntity<?> response = getBalance(forged, ALICE);

            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
            assertTrue(ledgerLoads.isEmpty());
        }

        @Test
        @DisplayName("Token whose acct claim was tampered with is rejected")
        void tamperedPayloadIsRejected() {
            String[] parts = KEYS.tokenFor(ALICE).split("\\.");
            String tamperedPayload = java.util.Base64.getUrlEncoder()
                .withoutPadding().encodeToString(
                    ("{\"acct\":\"" + BOB + "\",\"exp\":"
                        + Instant.now().plusSeconds(3600).getEpochSecond() + "}")
                        .getBytes());
            String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

            ResponseEntity<?> response = getBalance(tampered, BOB);

            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
            assertTrue(ledgerLoads.isEmpty());
        }

        @Test
        @DisplayName("Expired token is rejected")
        void expiredTokenIsRejected() {
            String expired = KEYS.tokenFor(ALICE, Instant.now().minusSeconds(5));

            ResponseEntity<?> response = getBalance(expired, ALICE);

            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
            assertTrue(ledgerLoads.isEmpty());
        }

        @Test
        @DisplayName("Signed token without an acct claim is rejected")
        void tokenWithoutAccountClaimIsRejected() {
            ResponseEntity<?> response =
                getBalance(KEYS.tokenWithoutAccountClaim(), ALICE);

            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
            assertTrue(ledgerLoads.isEmpty());
        }

        @Test
        @DisplayName("Malformed token is rejected")
        void malformedTokenIsRejected() {
            ResponseEntity<?> response = getBalance("not-a-jwt", ALICE);

            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        }
    }

    @Nested
    @DisplayName("Ledger dependency failure")
    class LedgerFailure {

        @Test
        @DisplayName("Ledger database failure during balance load returns 500 and is not cached")
        void ledgerFailureReturnsServerErrorAndIsNotCached() {
            ledgerFailure = new DataAccessResourceFailureException("ledger down");

            ResponseEntity<?> response = getBalance(KEYS.tokenFor(ALICE), ALICE);

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            assertEquals("cache error", response.getBody());
            assertNull(cache.getIfPresent(ALICE), "failed load must not cache a balance");

            ledgerFailure = null;
            ledgerBalances.put(ALICE, 500L);
            ResponseEntity<?> retry = getBalance(KEYS.tokenFor(ALICE), ALICE);
            assertEquals(HttpStatus.OK, retry.getStatusCode());
            assertEquals(500L, retry.getBody());
        }
    }

    @Nested
    @DisplayName("Cache stays consistent with new ledger transactions")
    class CacheConsistency {

        @Test
        @DisplayName("Debit from a cached local account lowers its balance")
        void debitFromCachedLocalAccount() {
            cache.put(ALICE, 10_000L);

            callback.processTransaction(TestTransactions.tx(
                1, ALICE, LOCAL_ROUTING, CAROL, EXTERNAL_ROUTING, 2_500));

            assertEquals(7_500L, cache.getIfPresent(ALICE));
        }

        @Test
        @DisplayName("Credit to a cached local account raises its balance")
        void creditToCachedLocalAccount() {
            cache.put(ALICE, 10_000L);

            callback.processTransaction(TestTransactions.tx(
                1, CAROL, EXTERNAL_ROUTING, ALICE, LOCAL_ROUTING, 1_234));

            assertEquals(11_234L, cache.getIfPresent(ALICE));
        }

        @Test
        @DisplayName("Internal transfer moves exactly the amount between both cached accounts")
        void internalTransferUpdatesBothSides() {
            cache.put(ALICE, 10_000L);
            cache.put(BOB, 1_000L);

            callback.processTransaction(TestTransactions.tx(
                1, ALICE, LOCAL_ROUTING, BOB, LOCAL_ROUTING, 3_000));

            assertEquals(7_000L, cache.getIfPresent(ALICE));
            assertEquals(4_000L, cache.getIfPresent(BOB));
            assertEquals(11_000L,
                cache.getIfPresent(ALICE) + cache.getIfPresent(BOB),
                "total of internal accounts must be conserved");
        }

        @Test
        @DisplayName("Successive transactions are applied cumulatively")
        void successiveTransactionsAccumulate() {
            cache.put(ALICE, 0L);

            callback.processTransaction(TestTransactions.tx(
                1, CAROL, EXTERNAL_ROUTING, ALICE, LOCAL_ROUTING, 5_000));
            callback.processTransaction(TestTransactions.tx(
                2, ALICE, LOCAL_ROUTING, BOB, LOCAL_ROUTING, 1_200));
            callback.processTransaction(TestTransactions.tx(
                3, CAROL, EXTERNAL_ROUTING, ALICE, LOCAL_ROUTING, 300));

            assertEquals(4_100L, cache.getIfPresent(ALICE));
        }

        @Test
        @DisplayName("Uncached accounts are not inserted; their balance is later read from the ledger")
        void uncachedAccountsAreNotInserted() {
            callback.processTransaction(TestTransactions.tx(
                1, ALICE, LOCAL_ROUTING, BOB, LOCAL_ROUTING, 3_000));

            assertFalse(cache.asMap().containsKey(ALICE));
            assertFalse(cache.asMap().containsKey(BOB));
            assertTrue(ledgerLoads.isEmpty(), "callback must not trigger ledger loads");
        }

        @Test
        @DisplayName("Same account number at another bank does not change the local balance")
        void foreignRoutingNumberIsIgnored() {
            cache.put(ALICE, 10_000L);

            callback.processTransaction(TestTransactions.tx(
                1, ALICE, EXTERNAL_ROUTING, CAROL, EXTERNAL_ROUTING, 4_000));
            callback.processTransaction(TestTransactions.tx(
                2, CAROL, EXTERNAL_ROUTING, ALICE, EXTERNAL_ROUTING, 700));

            assertEquals(10_000L, cache.getIfPresent(ALICE));
        }

        @Test
        @DisplayName("Transaction already included in a freshly loaded balance is not applied twice")
        void transactionInLoadedBalanceIsNotDoubleCounted() {
            // Ledger already holds tx 1 (+10,000) but the poller has not delivered it yet.
            ledgerBalances.put(ALICE, 10_000L);
            assertEquals(10_000L, getBalance(KEYS.tokenFor(ALICE), ALICE).getBody());

            callback.processTransaction(TestTransactions.tx(
                1, CAROL, EXTERNAL_ROUTING, ALICE, LOCAL_ROUTING, 10_000));

            assertEquals(10_000L, getBalance(KEYS.tokenFor(ALICE), ALICE).getBody(),
                "served balance must equal the ledger balance");
        }

        @Test
        @DisplayName("Balance served after a cached update reflects the transaction")
        void servedBalanceReflectsAppliedTransaction() {
            ledgerBalances.put(ALICE, 10_000L);
            assertEquals(10_000L, getBalance(KEYS.tokenFor(ALICE), ALICE).getBody());

            callback.processTransaction(TestTransactions.tx(
                1, ALICE, LOCAL_ROUTING, BOB, LOCAL_ROUTING, 2_000));

            assertEquals(8_000L, getBalance(KEYS.tokenFor(ALICE), ALICE).getBody());
            assertEquals(List.of(ALICE), ledgerLoads, "ledger read once, then served from cache");
        }
    }

    @Test
    @DisplayName("Controller registers its cache-update callback with the ledger reader on startup")
    void registersCallbackOnStartup() {
        Mockito.verify(ledgerReader).startWithCallback(any());
    }
}
