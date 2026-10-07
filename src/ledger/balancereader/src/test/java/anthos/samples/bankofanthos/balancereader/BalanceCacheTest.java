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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

/** Balance cache loading rules; the repository is the only substitute. */
class BalanceCacheTest {

    private static final String LOCAL_ROUTING = "883745000";
    private static final String ACCOUNT = "1000000001";

    private TransactionRepository repository;
    private BalanceCache balanceCache;

    @BeforeEach
    void setUp() {
        repository = mock(TransactionRepository.class);
        balanceCache = new BalanceCache();
        ReflectionTestUtils.setField(balanceCache, "dbRepo", repository);
    }

    private LoadingCache<String, Long> cache(int size) {
        return balanceCache.initializeCache(size, LOCAL_ROUTING);
    }

    @Test
    @DisplayName("Balance is loaded for the account at this bank's routing number")
    void loadsBalanceForLocalRoutingNumber() throws Exception {
        when(repository.findBalance(ACCOUNT, LOCAL_ROUTING)).thenReturn(4_200L);

        assertEquals(4_200L, cache(10).get(ACCOUNT));
        verify(repository).findBalance(ACCOUNT, LOCAL_ROUTING);
    }

    @Test
    @DisplayName("Account with no ledger rows has a zero balance")
    void missingBalanceIsZero() throws Exception {
        when(repository.findBalance(ACCOUNT, LOCAL_ROUTING)).thenReturn(null);

        assertEquals(0L, cache(10).get(ACCOUNT));
    }

    @Test
    @DisplayName("Repeated reads are served from cache without re-querying the ledger")
    void repeatedReadsHitCache() throws Exception {
        when(repository.findBalance(ACCOUNT, LOCAL_ROUTING)).thenReturn(100L);
        LoadingCache<String, Long> cache = cache(10);

        cache.get(ACCOUNT);
        cache.get(ACCOUNT);
        cache.get(ACCOUNT);

        verify(repository, times(1)).findBalance(ACCOUNT, LOCAL_ROUTING);
        assertEquals(2, cache.stats().hitCount(), "stats are recorded for metrics");
    }

    @Test
    @DisplayName("Ledger failure surfaces to the caller and is not cached as a balance")
    void ledgerFailureIsNotCached() throws Exception {
        when(repository.findBalance(ACCOUNT, LOCAL_ROUTING))
            .thenThrow(new DataAccessResourceFailureException("down"))
            .thenReturn(700L);
        LoadingCache<String, Long> cache = cache(10);

        UncheckedExecutionException e =
            assertThrows(UncheckedExecutionException.class, () -> cache.get(ACCOUNT));
        assertInstanceOf(DataAccessResourceFailureException.class, e.getCause());
        assertNull(cache.getIfPresent(ACCOUNT));

        assertEquals(700L, cache.get(ACCOUNT), "next read retries the ledger");
    }

    @Test
    @DisplayName("Cache size is bounded by CACHE_SIZE")
    void cacheSizeIsBounded() throws Exception {
        when(repository.findBalance(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(LOCAL_ROUTING))).thenReturn(1L);
        LoadingCache<String, Long> cache = cache(2);

        for (int i = 0; i < 5; i++) {
            cache.get("100000000" + i);
        }
        cache.cleanUp();

        assertTrue(cache.size() <= 2, "size was " + cache.size());
    }
}
