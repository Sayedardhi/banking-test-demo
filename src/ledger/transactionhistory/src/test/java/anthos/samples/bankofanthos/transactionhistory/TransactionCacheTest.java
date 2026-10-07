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

import static anthos.samples.bankofanthos.transactionhistory.TestFixtures.LOCAL_ROUTING;
import static anthos.samples.bankofanthos.transactionhistory.TestFixtures.localTx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

/** Cache configuration from {@link TransactionCache} with a substituted repository. */
class TransactionCacheTest {

    private static final String ALICE = "1111111111";
    private static final String BOB = "2222222222";

    private TransactionRepository repo;
    private TransactionCache cacheConfig;

    @BeforeEach
    void setUp() {
        repo = mock(TransactionRepository.class);
        cacheConfig = new TransactionCache();
        ReflectionTestUtils.setField(cacheConfig, "dbRepo", repo);
    }

    @Test
    @DisplayName("Loader queries the ledger for the local routing number, first page of HISTORY_LIMIT rows")
    void loaderUsesLocalRoutingAndHistoryLimit() throws Exception {
        LinkedList<Transaction> rows = new LinkedList<>(List.of(localTx(2, ALICE, BOB, 20)));
        when(repo.findForAccount(ALICE, LOCAL_ROUTING, PageRequest.of(0, 25))).thenReturn(rows);
        LoadingCache<String, Deque<Transaction>> cache =
            cacheConfig.initializeCache(10, 60, LOCAL_ROUTING, 25);

        Deque<Transaction> history = cache.get(ALICE);

        assertThat(history).extracting(Transaction::getTransactionId).containsExactly(2L);
        verify(repo).findForAccount(ALICE, LOCAL_ROUTING, PageRequest.of(0, 25));
    }

    @Test
    @DisplayName("Second read is a cache hit; the ledger is queried once per account")
    void cachesPerAccount() throws Exception {
        when(repo.findForAccount(anyString(), anyString(), any())).thenAnswer(i -> new LinkedList<>());
        LoadingCache<String, Deque<Transaction>> cache =
            cacheConfig.initializeCache(10, 60, LOCAL_ROUTING, 100);

        cache.get(ALICE);
        cache.get(ALICE);
        cache.get(BOB);

        verify(repo, times(1)).findForAccount(eq(ALICE), anyString(), any());
        verify(repo, times(1)).findForAccount(eq(BOB), anyString(), any());
        assertThat(cache.stats().hitCount()).isEqualTo(1);
        assertThat(cache.stats().missCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("CACHE_SIZE bounds the number of cached account histories")
    void cacheSizeBound() throws Exception {
        when(repo.findForAccount(anyString(), anyString(), any())).thenAnswer(i -> new LinkedList<>());
        LoadingCache<String, Deque<Transaction>> cache =
            cacheConfig.initializeCache(1, 60, LOCAL_ROUTING, 100);

        cache.get(ALICE);
        cache.get(BOB);

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.asMap()).containsOnlyKeys(BOB);
        assertThat(cache.stats().evictionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Ledger outage surfaces as UncheckedExecutionException and caches nothing")
    void ledgerOutageNotCached() {
        when(repo.findForAccount(anyString(), anyString(), any()))
            .thenThrow(new DataAccessResourceFailureException("down"));
        LoadingCache<String, Deque<Transaction>> cache =
            cacheConfig.initializeCache(10, 60, LOCAL_ROUTING, 100);

        assertThatThrownBy(() -> cache.get(ALICE))
            .isInstanceOf(UncheckedExecutionException.class)
            .hasCauseInstanceOf(DataAccessResourceFailureException.class);
        assertThat(cache.asMap()).isEmpty();
    }
}
