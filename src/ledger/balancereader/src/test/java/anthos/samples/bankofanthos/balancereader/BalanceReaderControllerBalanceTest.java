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

import static anthos.samples.bankofanthos.balancereader.TestTokens.bearer;
import static anthos.samples.bankofanthos.balancereader.TestTokens.token;
import static anthos.samples.bankofanthos.balancereader.TestTransactions.EXTERNAL_ROUTING;
import static anthos.samples.bankofanthos.balancereader.TestTransactions.LOCAL_ROUTING;
import static anthos.samples.bankofanthos.balancereader.TestTransactions.local;
import static anthos.samples.bankofanthos.balancereader.TestTransactions.transaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.cache.LoadingCache;
import io.micrometer.core.instrument.Clock;
import io.micrometer.stackdriver.StackdriverConfig;
import io.micrometer.stackdriver.StackdriverMeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Balance correctness and authorization rules of BalanceReaderController, wired to the real
 * Guava cache from BalanceCache and a real RSA JWT verifier. Only the ledger repository and the
 * background LedgerReader thread are substituted.
 */
class BalanceReaderControllerBalanceTest {

    private static final String OWNER = "1011226111";
    private static final String OTHER = "1033623433";
    private static final String THIRD = "1055757655";

    private TransactionRepository repository;
    private LoadingCache<String, Long> cache;
    private LedgerReaderCallback stream;
    private BalanceReaderController controller;

    @BeforeEach
    void setUp() {
        repository = mock(TransactionRepository.class);
        BalanceCache balanceCache = new BalanceCache();
        ReflectionTestUtils.setField(balanceCache, "dbRepo", repository);
        cache = balanceCache.initializeCache(1000, LOCAL_ROUTING);

        LedgerReader reader = mock(LedgerReader.class);
        controller = new BalanceReaderController(reader, TestTokens.verifier(), registry(), cache,
            LOCAL_ROUTING, "v-test");
        ArgumentCaptor<LedgerReaderCallback> callback = ArgumentCaptor.forClass(LedgerReaderCallback.class);
        verify(reader).startWithCallback(callback.capture());
        stream = callback.getValue();
    }

    private static StackdriverMeterRegistry registry() {
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

    private ResponseEntity<?> balanceAs(String account) {
        return controller.getBalance(bearer(token(account)), account);
    }

    @Nested
    @DisplayName("Balance reads")
    class Reads {

        @Test
        @DisplayName("Owner receives the ledger balance computed for this bank's routing number")
        void ownerReceivesLedgerBalance() {
            when(repository.findBalance(OWNER, LOCAL_ROUTING)).thenReturn(12_345L);

            ResponseEntity<?> response = balanceAs(OWNER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(12_345L);
            verify(repository).findBalance(OWNER, LOCAL_ROUTING);
        }

        @Test
        @DisplayName("An account with no ledger history reports a zero balance, not null")
        void noHistoryIsZero() {
            when(repository.findBalance(OWNER, LOCAL_ROUTING)).thenReturn(null);

            ResponseEntity<?> response = balanceAs(OWNER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Repeat reads are served from the cache without re-querying the ledger")
        void repeatReadsUseCache() {
            when(repository.findBalance(OWNER, LOCAL_ROUTING)).thenReturn(500L);

            balanceAs(OWNER);
            balanceAs(OWNER);
            ResponseEntity<?> third = balanceAs(OWNER);

            assertThat(third.getBody()).isEqualTo(500L);
            verify(repository, times(1)).findBalance(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("JWT authorization")
    class Authorization {

        @Test
        @DisplayName("A customer cannot read another customer's balance, and the ledger is not queried")
        void otherCustomersAccountIsRejected() {
            ResponseEntity<?> response = controller.getBalance(bearer(token(OWNER)), OTHER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isEqualTo("not authorized");
            verifyNoInteractions(repository);
            assertThat(cache.asMap()).doesNotContainKey(OTHER);
        }

        @Test
        @DisplayName("A token without an acct claim is rejected")
        void tokenWithoutAccountClaim() {
            ResponseEntity<?> response = controller.getBalance(bearer(TestTokens.tokenWithoutAccount()), OWNER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("An expired token is rejected")
        void expiredToken() {
            String expired = token(TestTokens.SIGNER, OWNER, Instant.now().minus(1, ChronoUnit.MINUTES));

            ResponseEntity<?> response = controller.getBalance(bearer(expired), OWNER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("A token signed by a different key is rejected")
        void foreignSignature() {
            String forged = token(TestTokens.OTHER_SIGNER, OWNER, Instant.now().plus(1, ChronoUnit.HOURS));

            ResponseEntity<?> response = controller.getBalance(bearer(forged), OWNER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("A genuine signature with a swapped payload (acct changed) is rejected")
        void tamperedPayload() {
            ResponseEntity<?> response = controller.getBalance(bearer(TestTokens.tamperedToken(OWNER, OTHER)), OTHER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(repository);
        }

        @Test
        @DisplayName("An unsigned (alg=none) token is rejected")
        void unsignedToken() {
            ResponseEntity<?> response = controller.getBalance(bearer(TestTokens.unsignedToken(OWNER)), OWNER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(repository);
        }

        @ParameterizedTest(name = "Authorization: \"{0}\"")
        @ValueSource(strings = {"Bearer ", "Bearer", "", "Bearer not-a-jwt", "Basic dXNlcjpwYXNz", "bearer x.y.z",
            "Bearer Bearer "})
        @DisplayName("Malformed Authorization headers are rejected with 401, never an unhandled error")
        void malformedHeaders(String header) {
            ResponseEntity<?> response = assertDoesNotThrow(() -> controller.getBalance(header, OWNER));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(repository);
        }
    }

    @Nested
    @DisplayName("Ledger outages")
    class Outages {

        @Test
        @DisplayName("Database unavailable on a cache miss returns 500 'cache error', never a $0 balance")
        void databaseOutageOnMiss() {
            when(repository.findBalance(OWNER, LOCAL_ROUTING))
                .thenThrow(new DataAccessResourceFailureException("ledger-db down"));

            ResponseEntity<?> response = balanceAs(OWNER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isEqualTo("cache error");
            assertThat(cache.asMap()).doesNotContainKey(OWNER);
        }

        @Test
        @DisplayName("Network failure reaching the ledger returns 500 'cache error'")
        void networkOutageOnMiss() {
            when(repository.findBalance(OWNER, LOCAL_ROUTING))
                .thenThrow(new ResourceAccessException("connection refused"));

            ResponseEntity<?> response = balanceAs(OWNER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isEqualTo("cache error");
        }

        @Test
        @DisplayName("A failed load is not cached: the next read after recovery returns the real balance")
        void failureIsNotCached() {
            when(repository.findBalance(OWNER, LOCAL_ROUTING))
                .thenThrow(new DataAccessResourceFailureException("ledger-db down"))
                .thenReturn(7_700L);

            assertThat(balanceAs(OWNER).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            ResponseEntity<?> recovered = balanceAs(OWNER);

            assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(recovered.getBody()).isEqualTo(7_700L);
        }

        @Test
        @DisplayName("A balance cached before the outage is still served while the ledger is down")
        void cachedBalanceSurvivesOutage() {
            when(repository.findBalance(OWNER, LOCAL_ROUTING)).thenReturn(4_200L)
                .thenThrow(new DataAccessResourceFailureException("ledger-db down"));
            balanceAs(OWNER);

            ResponseEntity<?> response = balanceAs(OWNER);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(4_200L);
        }
    }

    @Nested
    @DisplayName("Ledger stream updates to cached balances")
    class StreamUpdates {

        private void cacheBalances(long owner, long other) {
            when(repository.findBalance(OWNER, LOCAL_ROUTING)).thenReturn(owner);
            when(repository.findBalance(OTHER, LOCAL_ROUTING)).thenReturn(other);
            balanceAs(OWNER);
            balanceAs(OTHER);
        }

        @Test
        @DisplayName("A local transfer debits the sender and credits the recipient by the exact amount")
        void localTransferMovesExactAmount() {
            cacheBalances(10_000L, 500L);

            stream.processTransaction(local(1, OWNER, OTHER, 2_501));

            assertThat(balanceAs(OWNER).getBody()).isEqualTo(7_499L);
            assertThat(balanceAs(OTHER).getBody()).isEqualTo(3_001L);
            verify(repository, times(2)).findBalance(anyString(), anyString());
        }

        @Test
        @DisplayName("A deposit from an external bank credits only the local recipient")
        void externalDepositCreditsRecipient() {
            cacheBalances(0L, 0L);

            stream.processTransaction(transaction(2, OTHER, EXTERNAL_ROUTING, OWNER, LOCAL_ROUTING, 12_345));

            assertThat(balanceAs(OWNER).getBody()).isEqualTo(12_345L);
            assertThat(balanceAs(OTHER).getBody())
                .as("same account number at another bank is a different account").isEqualTo(0L);
        }

        @Test
        @DisplayName("A payment to an external bank debits only the local sender")
        void externalPaymentDebitsSender() {
            cacheBalances(5_000L, 5_000L);

            stream.processTransaction(transaction(3, OWNER, LOCAL_ROUTING, OTHER, EXTERNAL_ROUTING, 1_000));

            assertThat(balanceAs(OWNER).getBody()).isEqualTo(4_000L);
            assertThat(balanceAs(OTHER).getBody()).isEqualTo(5_000L);
        }

        @Test
        @DisplayName("A transfer to oneself leaves the balance unchanged")
        void selfTransferIsNeutral() {
            cacheBalances(900L, 0L);

            stream.processTransaction(local(4, OWNER, OWNER, 300));

            assertThat(balanceAs(OWNER).getBody()).isEqualTo(900L);
        }

        @Test
        @DisplayName("Transactions for accounts not yet cached do not create cache entries")
        void uncachedAccountsAreLoadedFromLedgerLater() {
            stream.processTransaction(local(5, OWNER, THIRD, 700));

            assertThat(cache.asMap()).doesNotContainKeys(OWNER, THIRD);
            when(repository.findBalance(THIRD, LOCAL_ROUTING)).thenReturn(700L);
            assertThat(balanceAs(THIRD).getBody()).isEqualTo(700L);
        }

        @Test
        @DisplayName("A transaction already included in the loaded balance is not applied a second time")
        void loadedBalanceIsNotDoubleCounted() {
            // The ledger already holds deposit #11 when the balance is first read (cache miss), but the
            // ledger reader has not polled it yet; the stream then delivers #11.
            when(repository.findBalance(OWNER, LOCAL_ROUTING)).thenReturn(5_000L);
            assertThat(balanceAs(OWNER).getBody()).isEqualTo(5_000L);

            stream.processTransaction(transaction(11, OTHER, EXTERNAL_ROUTING, OWNER, LOCAL_ROUTING, 5_000));

            assertThat(balanceAs(OWNER).getBody()).isEqualTo(5_000L);
        }

        @Test
        @DisplayName("A sequence of transfers keeps the two cached balances' total constant")
        void transfersConserveMoney() {
            cacheBalances(10_000L, 10_000L);

            stream.processTransaction(local(6, OWNER, OTHER, 1));
            stream.processTransaction(local(7, OTHER, OWNER, 2_500));
            stream.processTransaction(local(8, OWNER, OTHER, 9_999));

            long owner = (Long) balanceAs(OWNER).getBody();
            long other = (Long) balanceAs(OTHER).getBody();
            assertThat(owner).isEqualTo(10_000L - 1 + 2_500 - 9_999);
            assertThat(owner + other).isEqualTo(20_000L);
        }
    }

    @Test
    @DisplayName("The controller registers exactly one ledger callback at construction")
    void registersCallback() {
        LedgerReader reader = mock(LedgerReader.class);
        new BalanceReaderController(reader, TestTokens.verifier(), registry(), cache, LOCAL_ROUTING, "v");
        verify(reader, times(1)).startWithCallback(any());
    }
}
