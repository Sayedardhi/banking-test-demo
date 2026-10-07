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

import static anthos.samples.bankofanthos.transactionhistory.TestFixtures.localTx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Background ledger polling. Each scenario ends with the documented out-of-sync shutdown
 * (remote id lower than processed id) so the polling thread terminates deterministically.
 */
class LedgerReaderTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String A = "1111111111";
    private static final String B = "2222222222";

    private TransactionRepository repo;
    private LedgerReader reader;
    private final List<Transaction> delivered = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        repo = mock(TransactionRepository.class);
        reader = new LedgerReader();
        ReflectionTestUtils.setField(reader, "dbRepo", repo);
        ReflectionTestUtils.setField(reader, "pollMs", 1);
        ReflectionTestUtils.setField(reader, "localRoutingNum", TestFixtures.LOCAL_ROUTING);
    }

    private void awaitStopped() throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (reader.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(reader.isAlive()).as("ledger reader thread stopped").isFalse();
    }

    @Test
    @DisplayName("Null callback is rejected and no polling starts")
    void nullCallbackRejected() {
        assertThatThrownBy(() -> reader.startWithCallback(null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repo);
        assertThat(reader.isAlive()).isTrue();
    }

    @Test
    @DisplayName("Only transactions after the startup position are delivered, once each, in ledger order")
    void deliversNewTransactionsInOrder() throws Exception {
        when(repo.latestTransactionId()).thenReturn(5L, 7L, 7L, 7L, 3L);
        when(repo.findLatest(5L)).thenReturn(List.of(localTx(6, A, B, 600), localTx(7, B, A, 700)));

        reader.startWithCallback(delivered::add);
        awaitStopped();

        assertThat(delivered).extracting(Transaction::getTransactionId).containsExactly(6L, 7L);
        InOrder order = inOrder(repo);
        order.verify(repo, times(2)).latestTransactionId();
        order.verify(repo).findLatest(5L);
        verify(repo, times(1)).findLatest(anyLong());
    }

    @Test
    @DisplayName("Empty ledger at startup reads from the beginning once entries appear")
    void emptyLedgerStartsFromBeginning() throws Exception {
        when(repo.latestTransactionId()).thenReturn(null, 1L, 0L);
        when(repo.findLatest(-1L)).thenReturn(List.of(localTx(1, A, B, 100)));

        reader.startWithCallback(delivered::add);
        awaitStopped();

        assertThat(delivered).extracting(Transaction::getTransactionId).containsExactly(1L);
        verify(repo).findLatest(-1L);
    }

    @Test
    @DisplayName("Ledger unreachable at startup does not fail startup; reader catches up when it returns")
    void unreachableAtStartupCatchesUp() throws Exception {
        when(repo.latestTransactionId())
            .thenThrow(new DataAccessResourceFailureException("down"))
            .thenReturn(2L, 1L);
        when(repo.findLatest(-1L)).thenReturn(List.of(localTx(1, A, B, 1), localTx(2, A, B, 2)));

        reader.startWithCallback(delivered::add);
        awaitStopped();

        assertThat(delivered).extracting(Transaction::getTransactionId).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("Transient ledger outage during polling keeps the reader alive and replays nothing")
    void transientOutageSurvived() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(repo.latestTransactionId()).thenAnswer(i -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                return 4L;
            }
            if (n <= 4) {
                throw new DataAccessResourceFailureException("down");
            }
            return n == 5 ? 4L : 1L;
        });

        reader.startWithCallback(delivered::add);
        awaitStopped();

        assertThat(calls.get()).isGreaterThanOrEqualTo(6);
        verify(repo, never()).findLatest(anyLong());
        assertThat(delivered).isEmpty();
    }

    @Test
    @DisplayName("Ledger id moving backwards stops the reader so /healthy reports failure")
    void outOfSyncStopsReader() throws Exception {
        when(repo.latestTransactionId()).thenReturn(10L, 3L);

        reader.startWithCallback(delivered::add);
        awaitStopped();

        assertThat(delivered).isEmpty();
        verify(repo, never()).findLatest(anyLong());
    }
}
