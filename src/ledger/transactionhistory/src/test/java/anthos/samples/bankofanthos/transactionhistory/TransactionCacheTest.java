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
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class TransactionCacheTest {

    private static final String ACCOUNT = "1011226111";

    private TransactionRepository repo;

    @BeforeEach
    void setUp() {
        repo = mock(TransactionRepository.class);
    }

    private LoadingCache<String, Deque<Transaction>> cache(int maxSize, int historyLimit) {
        TransactionCache config = new TransactionCache();
        ReflectionTestUtils.setField(config, "dbRepo", repo);
        return config.initializeCache(maxSize, 60, TestFixtures.LOCAL_ROUTING, historyLimit);
    }

    @Test
    @DisplayName("Cache miss queries the ledger for this bank's routing number, first page of HISTORY_LIMIT rows")
    void loadsFromLedgerWithLocalRoutingAndHistoryLimit() throws Exception {
        LinkedList<Transaction> rows = new LinkedList<>(List.of(
            TestFixtures.transaction(2, ACCOUNT, TestFixtures.LOCAL_ROUTING, "1033623433", TestFixtures.LOCAL_ROUTING, 500)));
        when(repo.findForAccount(eq(ACCOUNT), eq(TestFixtures.LOCAL_ROUTING), any(Pageable.class))).thenReturn(rows);

        Deque<Transaction> history = cache(10, 25).get(ACCOUNT);

        assertThat(history).containsExactlyElementsOf(rows);
        verify(repo).findForAccount(ACCOUNT, TestFixtures.LOCAL_ROUTING, PageRequest.of(0, 25));
    }

    @Test
    @DisplayName("Repeated reads are served from the cache, not the ledger, and counted as hits")
    void secondReadIsCacheHit() throws Exception {
        when(repo.findForAccount(anyString(), anyString(), any(Pageable.class))).thenReturn(new LinkedList<>());
        LoadingCache<String, Deque<Transaction>> cache = cache(10, 100);

        cache.get(ACCOUNT);
        cache.get(ACCOUNT);

        verify(repo, times(1)).findForAccount(anyString(), anyString(), any(Pageable.class));
        assertThat(cache.stats().hitCount()).isEqualTo(1);
        assertThat(cache.stats().missCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Ledger outage during a cache load surfaces as UncheckedExecutionException and nothing is cached")
    void ledgerOutageIsNotCached() {
        when(repo.findForAccount(anyString(), anyString(), any(Pageable.class)))
            .thenThrow(new DataAccessResourceFailureException("ledger-db down"));
        LoadingCache<String, Deque<Transaction>> cache = cache(10, 100);

        assertThatThrownBy(() -> cache.get(ACCOUNT))
            .isInstanceOf(UncheckedExecutionException.class)
            .hasCauseInstanceOf(DataAccessResourceFailureException.class);
        assertThat(cache.asMap()).doesNotContainKey(ACCOUNT);
    }

    @Test
    @DisplayName("CACHE_SIZE bounds the number of cached account histories")
    void maximumSizeBoundsEntries() throws Exception {
        when(repo.findForAccount(anyString(), anyString(), any(Pageable.class))).thenAnswer(i -> new LinkedList<>());
        LoadingCache<String, Deque<Transaction>> cache = cache(2, 100);

        for (int i = 0; i < 5; i++) {
            cache.get("100000000" + i);
        }

        assertThat(cache.size()).isLessThanOrEqualTo(2);
        assertThat(cache.stats().evictionCount()).isGreaterThanOrEqualTo(3);
    }
}
