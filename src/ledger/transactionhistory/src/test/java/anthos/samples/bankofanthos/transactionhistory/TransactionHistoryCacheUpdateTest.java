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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.auth0.jwt.JWTVerifier;
import com.google.common.cache.LoadingCache;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * How new ledger rows delivered by LedgerReader update cached histories. The callback the controller
 * registers is captured and invoked directly, against a real Guava cache.
 */
class TransactionHistoryCacheUpdateTest {

    private static final String ALICE = "1011226111";
    private static final String BOB = "1033623433";
    private static final String CAROL = "1055757655";
    private static final String L = TestFixtures.LOCAL_ROUTING;
    private static final String X = TestFixtures.EXTERNAL_ROUTING;

    private TransactionRepository repo;
    private LoadingCache<String, Deque<Transaction>> cache;
    private LedgerReaderCallback callback;
    private TransactionHistoryController controller;

    @BeforeEach
    void setUp() {
        repo = mock(TransactionRepository.class);
        when(repo.findForAccount(anyString(), eq(L), any(Pageable.class))).thenAnswer(i -> new LinkedList<>());
        TransactionCache cacheConfig = new TransactionCache();
        ReflectionTestUtils.setField(cacheConfig, "dbRepo", repo);
        cache = cacheConfig.initializeCache(100, 60, L, 3);
        LedgerReader reader = mock(LedgerReader.class);
        controller = new TransactionHistoryController(reader,
            TransactionHistoryControllerAuthorizationTest.disabledRegistry(), mock(JWTVerifier.class),
            "unused", cache, L, "test");
        ReflectionTestUtils.setField(controller, "historyLimit", 3);
        ArgumentCaptor<LedgerReaderCallback> captor = ArgumentCaptor.forClass(LedgerReaderCallback.class);
        verify(reader).startWithCallback(captor.capture());
        callback = captor.getValue();
    }

    private static List<Long> ids(Deque<Transaction> history) {
        return history.stream().map(Transaction::getTransactionId).toList();
    }

    @Test
    @DisplayName("Internal payment is prepended to both the sender's and the recipient's cached history")
    void paymentUpdatesBothCachedParties() throws Exception {
        cache.get(ALICE);
        cache.get(BOB);

        callback.processTransaction(TestFixtures.transaction(11, ALICE, L, BOB, L, 2500));
        callback.processTransaction(TestFixtures.transaction(12, BOB, L, ALICE, L, 100));

        assertThat(ids(cache.get(ALICE))).containsExactly(12L, 11L);
        assertThat(ids(cache.get(BOB))).containsExactly(12L, 11L);
    }

    @Test
    @DisplayName("Rows for accounts that are not cached do not create cache entries or ledger reads")
    void uncachedAccountsIgnored() throws Exception {
        cache.get(ALICE);

        callback.processTransaction(TestFixtures.transaction(11, BOB, L, CAROL, L, 2500));

        assertThat(cache.asMap()).containsOnlyKeys(ALICE);
        assertThat(cache.get(ALICE)).isEmpty();
        verify(repo, never()).findForAccount(eq(BOB), anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("Same account number at another bank's routing number is not this customer's transaction")
    void externalRoutingWithSameAccountNumberIgnored() throws Exception {
        cache.get(ALICE);

        callback.processTransaction(TestFixtures.transaction(11, ALICE, X, CAROL, X, 900));
        callback.processTransaction(TestFixtures.transaction(12, CAROL, X, ALICE, X, 900));

        assertThat(cache.get(ALICE)).isEmpty();
    }

    @Test
    @DisplayName("Deposit from an external bank is credited to the local cached recipient only")
    void externalDepositCreditsLocalRecipient() throws Exception {
        cache.get(ALICE);

        callback.processTransaction(TestFixtures.transaction(11, ALICE, X, ALICE, L, 50000));

        assertThat(ids(cache.get(ALICE))).containsExactly(11L);
    }

    @Test
    @DisplayName("HISTORY_LIMIT keeps the newest rows and drops the oldest")
    void historyLimitDropsOldest() throws Exception {
        cache.get(ALICE);

        for (long id = 1; id <= 5; id++) {
            callback.processTransaction(TestFixtures.transaction(id, BOB, L, ALICE, L, 100));
        }

        assertThat(ids(cache.get(ALICE))).containsExactly(5L, 4L, 3L);
    }

    @Test
    @DisplayName("A row already loaded from the ledger and then delivered by the reader appears once")
    void rowLoadedThenDeliveredAppearsOnce() throws Exception {
        // Ledger head was 10 when the reader last polled; row 11 is committed; the customer opens
        // history before the next poll, so the cache load already contains row 11.
        Transaction row = TestFixtures.transaction(11, BOB, L, ALICE, L, 4200);
        when(repo.findForAccount(eq(ALICE), eq(L), any(Pageable.class))).thenReturn(new LinkedList<>(List.of(row)));
        cache.get(ALICE);

        callback.processTransaction(row);

        assertThat(ids(cache.get(ALICE))).containsExactly(11L);
    }
}
