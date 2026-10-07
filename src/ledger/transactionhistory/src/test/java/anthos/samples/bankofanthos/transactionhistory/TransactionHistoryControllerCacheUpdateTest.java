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

import static anthos.samples.bankofanthos.transactionhistory.TestFixtures.EXTERNAL_ROUTING;
import static anthos.samples.bankofanthos.transactionhistory.TestFixtures.LOCAL_ROUTING;
import static anthos.samples.bankofanthos.transactionhistory.TestFixtures.localTx;
import static anthos.samples.bankofanthos.transactionhistory.TestFixtures.tx;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * How ledger events delivered by {@link LedgerReader} update cached histories. The controller's
 * callback is captured and invoked directly; the cache is the real Guava cache.
 */
class TransactionHistoryControllerCacheUpdateTest {

    private static final String ALICE = "1111111111";
    private static final String BOB = "2222222222";
    private static final String CAROL = "3333333333";
    private static final int HISTORY_LIMIT = 3;

    private TransactionRepository repo;
    private LoadingCache<String, Deque<Transaction>> cache;
    private LedgerReaderCallback callback;

    @BeforeEach
    void setUp() throws Exception {
        repo = mock(TransactionRepository.class);
        TransactionCache cacheConfig = new TransactionCache();
        ReflectionTestUtils.setField(cacheConfig, "dbRepo", repo);
        cache = cacheConfig.initializeCache(1000, 60, LOCAL_ROUTING, HISTORY_LIMIT);
        LedgerReader reader = mock(LedgerReader.class);
        TransactionHistoryController controller = new TransactionHistoryController(reader,
            TestFixtures.disabledMeterRegistry(), mock(JWTVerifier.class), "unused.pem", cache,
            LOCAL_ROUTING, "test");
        ReflectionTestUtils.setField(controller, "historyLimit", HISTORY_LIMIT);
        ArgumentCaptor<LedgerReaderCallback> captor = ArgumentCaptor.forClass(LedgerReaderCallback.class);
        verify(reader).startWithCallback(captor.capture());
        callback = captor.getValue();
    }

    private void warm(String account, Transaction... history) throws Exception {
        when(repo.findForAccount(eq(account), anyString(), any()))
            .thenReturn(new LinkedList<>(List.of(history)));
        cache.get(account);
    }

    private List<Long> ids(String account) {
        return cache.asMap().get(account).stream().map(Transaction::getTransactionId).toList();
    }

    @Test
    @DisplayName("Outgoing payment from a cached local account is added at the top of its history")
    void debitAddedToSender() throws Exception {
        warm(ALICE, localTx(1, BOB, ALICE, 100));

        callback.processTransaction(localTx(2, ALICE, BOB, 250));

        assertThat(ids(ALICE)).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("Incoming deposit to a cached local account is added at the top of its history")
    void creditAddedToRecipient() throws Exception {
        warm(BOB, localTx(1, BOB, CAROL, 100));

        callback.processTransaction(tx(2, ALICE, EXTERNAL_ROUTING, BOB, LOCAL_ROUTING, 5000,
            TestFixtures.BASE_TIME.plusSeconds(2)));

        assertThat(ids(BOB)).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("Transfer between two cached local accounts updates both histories")
    void transferUpdatesBothParties() throws Exception {
        warm(ALICE);
        warm(BOB);

        callback.processTransaction(localTx(7, ALICE, BOB, 700));

        assertThat(ids(ALICE)).containsExactly(7L);
        assertThat(ids(BOB)).containsExactly(7L);
    }

    @Test
    @DisplayName("Same account number at another bank does not leak into the local account's history")
    void externalRoutingNotAttributedToLocalAccount() throws Exception {
        warm(ALICE);

        callback.processTransaction(tx(5, ALICE, EXTERNAL_ROUTING, CAROL, EXTERNAL_ROUTING, 999,
            TestFixtures.BASE_TIME));
        callback.processTransaction(tx(6, CAROL, EXTERNAL_ROUTING, ALICE, EXTERNAL_ROUTING, 999,
            TestFixtures.BASE_TIME));

        assertThat(ids(ALICE)).isEmpty();
    }

    @Test
    @DisplayName("Events for uncached accounts neither load nor create a cache entry")
    void uncachedAccountIgnored() {
        callback.processTransaction(localTx(1, ALICE, BOB, 100));

        assertThat(cache.asMap()).doesNotContainKeys(ALICE, BOB);
        verify(repo, never()).findForAccount(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Ledger event for a transaction already in the loaded history is not added twice")
    void alreadyLoadedTransactionNotDuplicated() throws Exception {
        Transaction payment = localTx(1, BOB, ALICE, 100);
        warm(ALICE, payment);

        callback.processTransaction(localTx(1, BOB, ALICE, 100));

        assertThat(ids(ALICE)).containsExactly(1L);
    }

    @Test
    @DisplayName("History is capped at HISTORY_LIMIT, dropping the oldest entry")
    void historyLimitDropsOldest() throws Exception {
        warm(ALICE, localTx(3, ALICE, BOB, 3), localTx(2, ALICE, BOB, 2), localTx(1, ALICE, BOB, 1));

        callback.processTransaction(localTx(4, BOB, ALICE, 4));
        callback.processTransaction(localTx(5, ALICE, CAROL, 5));

        assertThat(ids(ALICE)).containsExactly(5L, 4L, 3L);
    }
}
