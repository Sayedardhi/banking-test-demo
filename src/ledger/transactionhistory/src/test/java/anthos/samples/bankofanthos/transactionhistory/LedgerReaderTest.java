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
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

class LedgerReaderTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private TransactionRepository repo;
    private LedgerReader reader;
    private final List<Transaction> delivered = new CopyOnWriteArrayList<>();
    /** Ledger head as seen by the reader; set to -100 to make the reader stop (out-of-sync path). */
    private final AtomicLong remoteHead = new AtomicLong();

    @BeforeEach
    void setUp() {
        repo = mock(TransactionRepository.class);
        reader = new LedgerReader();
        ReflectionTestUtils.setField(reader, "dbRepo", repo);
        ReflectionTestUtils.setField(reader, "pollMs", 5);
        ReflectionTestUtils.setField(reader, "localRoutingNum", TestFixtures.LOCAL_ROUTING);
    }

    @AfterEach
    void stopBackgroundThread() {
        if (ReflectionTestUtils.getField(reader, "backgroundThread") == null) {
            return;
        }
        // The reader has no stop(); a ledger head behind the reader's position ends its loop.
        when(repo.latestTransactionId()).thenReturn(-100L);
        await().atMost(WAIT).until(() -> !reader.isAlive());
    }

    private static Transaction tx(long id) {
        return TestFixtures.transaction(id, "1011226111", TestFixtures.LOCAL_ROUTING,
            "1033623433", TestFixtures.LOCAL_ROUTING, 100);
    }

    @Test
    @DisplayName("Null callback is rejected before any thread starts")
    void nullCallbackRejected() {
        assertThatThrownBy(() -> reader.startWithCallback(null)).isInstanceOf(IllegalStateException.class);
        assertThat(reader.isAlive()).isTrue();
        verify(repo, never()).latestTransactionId();
    }

    @Test
    @DisplayName("Rows already in the ledger at startup are not replayed; only newer rows are delivered, in id order")
    void deliversOnlyRowsAfterStartupHead() {
        when(repo.latestTransactionId()).thenReturn(5L, 7L, 7L);
        when(repo.findLatest(5L)).thenReturn(List.of(tx(6), tx(7)));

        reader.startWithCallback(delivered::add);

        await().atMost(WAIT).until(() -> delivered.size() == 2);
        assertThat(delivered).extracting(Transaction::getTransactionId).containsExactly(6L, 7L);
        verify(repo, never()).findLatest(-1L);
        await().during(Duration.ofMillis(100)).atMost(WAIT).until(() -> delivered.size() == 2);
        assertThat(reader.isAlive()).isTrue();
    }

    @Test
    @DisplayName("Next poll resumes after the last delivered id (no gaps, no re-delivery)")
    void resumesAfterLastDeliveredId() {
        when(repo.latestTransactionId()).thenReturn(1L, 2L, 4L, 4L);
        when(repo.findLatest(1L)).thenReturn(List.of(tx(2)));
        when(repo.findLatest(2L)).thenReturn(List.of(tx(3), tx(4)));

        reader.startWithCallback(delivered::add);

        await().atMost(WAIT).until(() -> delivered.size() == 3);
        assertThat(delivered).extracting(Transaction::getTransactionId).containsExactly(2L, 3L, 4L);
    }

    @Test
    @DisplayName("Empty ledger (MAX id is NULL) starts from the beginning and delivers the first row")
    void emptyLedgerStartsFromBeginning() {
        when(repo.latestTransactionId()).thenReturn(null, 1L, 1L);
        when(repo.findLatest(-1L)).thenReturn(List.of(tx(1)));

        reader.startWithCallback(delivered::add);

        await().atMost(WAIT).until(() -> delivered.size() == 1);
        assertThat(delivered.get(0).getTransactionId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Ledger unreachable at startup: service still starts and catches up once the ledger returns")
    void unreachableAtStartupStillStarts() {
        when(repo.latestTransactionId())
            .thenThrow(new DataAccessResourceFailureException("down"))
            .thenReturn(1L);
        when(repo.findLatest(-1L)).thenReturn(List.of(tx(1)));

        reader.startWithCallback(delivered::add);

        await().atMost(WAIT).until(() -> delivered.size() == 1);
        assertThat(reader.isAlive()).isTrue();
    }

    @Test
    @DisplayName("Transient ledger outage while polling keeps the reader alive and it resumes afterwards")
    void transientOutageWhilePolling() {
        when(repo.latestTransactionId())
            .thenReturn(3L)
            .thenThrow(new ResourceAccessException("timeout"))
            .thenThrow(new DataAccessResourceFailureException("down"))
            .thenReturn(4L);
        when(repo.findLatest(3L)).thenReturn(List.of(tx(4)));

        reader.startWithCallback(delivered::add);

        await().atMost(WAIT).until(() -> delivered.size() == 1);
        assertThat(reader.isAlive()).isTrue();
        verify(repo, never()).findLatest(-1L);
    }

    @Test
    @DisplayName("Ledger head moving backwards (reset/restore) stops the reader so /healthy fails")
    void ledgerHeadBehindReaderStopsThread() {
        when(repo.latestTransactionId()).thenReturn(10L, 3L);

        reader.startWithCallback(delivered::add);

        await().atMost(WAIT).until(() -> !reader.isAlive());
        assertThat(delivered).isEmpty();
        verify(repo, atLeastOnce()).latestTransactionId();
        verify(repo, never()).findLatest(anyLong());
    }
}
