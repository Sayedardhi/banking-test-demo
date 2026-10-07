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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/** BalanceCache loader: what a cache miss reads from the ledger repository. */
class BalanceCacheTest {

    private static final String ROUTING = TestTransactions.LOCAL_ROUTING;

    private TransactionRepository repository;
    private BalanceCache balanceCache;

    @BeforeEach
    void setUp() {
        repository = mock(TransactionRepository.class);
        balanceCache = new BalanceCache();
        ReflectionTestUtils.setField(balanceCache, "dbRepo", repository);
    }

    @Test
    @DisplayName("A miss loads the balance for the configured local routing number")
    void loadsForLocalRouting() throws Exception {
        when(repository.findBalance("1011226111", ROUTING)).thenReturn(-250L);
        LoadingCache<String, Long> cache = balanceCache.initializeCache(10, ROUTING);

        assertThat(cache.get("1011226111")).isEqualTo(-250L);
        verify(repository).findBalance("1011226111", ROUTING);
    }

    @Test
    @DisplayName("No ledger rows (null sum) is cached as 0")
    void nullIsZero() throws Exception {
        when(repository.findBalance("1011226111", ROUTING)).thenReturn(null);
        LoadingCache<String, Long> cache = balanceCache.initializeCache(10, ROUTING);

        assertThat(cache.get("1011226111")).isZero();
    }

    @Test
    @DisplayName("A repository outage propagates instead of caching a default balance")
    void outagePropagates() {
        when(repository.findBalance(anyString(), anyString()))
            .thenThrow(new DataAccessResourceFailureException("ledger-db down"));
        LoadingCache<String, Long> cache = balanceCache.initializeCache(10, ROUTING);

        assertThatThrownBy(() -> cache.getUnchecked("1011226111"))
            .isInstanceOf(UncheckedExecutionException.class)
            .hasCauseInstanceOf(DataAccessResourceFailureException.class);
        assertThat(cache.asMap()).isEmpty();
    }

    @Test
    @DisplayName("CACHE_SIZE bounds the number of cached balances and stats are recorded for metrics")
    void sizeBoundAndStats() throws Exception {
        when(repository.findBalance(anyString(), anyString())).thenReturn(1L);
        LoadingCache<String, Long> cache = balanceCache.initializeCache(2, ROUTING);

        for (String account : new String[] {"1000000001", "1000000002", "1000000003", "1000000004"}) {
            cache.get(account);
        }
        cache.get("1000000004");
        cache.cleanUp();

        assertThat(cache.size()).isLessThanOrEqualTo(2);
        assertThat(cache.stats().loadCount()).isEqualTo(4);
        assertThat(cache.stats().hitCount()).isEqualTo(1);
        verify(repository, times(4)).findBalance(anyString(), anyString());
    }
}
