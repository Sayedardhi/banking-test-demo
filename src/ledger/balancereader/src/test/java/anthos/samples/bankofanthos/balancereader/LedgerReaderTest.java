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

import static anthos.samples.bankofanthos.balancereader.TestTransactions.local;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

/** LedgerReader polling loop against a substituted repository (the real database is covered by integration tests). */
class LedgerReaderTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String A = "1011226111";
    private static final String B = "1033623433";

    private TransactionRepository repository;
    private LedgerReader reader;
    private final List<Long> delivered = new CopyOnWriteArrayList<>();
    private final LedgerReaderCallback collect = t -> delivered.add(t.getTransactionId());

    @BeforeEach
    void setUp() {
        repository = mock(TransactionRepository.class);
        reader = new LedgerReader();
        ReflectionTestUtils.setField(reader, "dbRepo", repository);
        ReflectionTestUtils.setField(reader, "pollMs", 5);
        ReflectionTestUtils.setField(reader, "localRoutingNum", TestTransactions.LOCAL_ROUTING);
    }

    @Test
    @DisplayName("Starting without a callback is rejected")
    void nullCallbackRejected() {
        assertThatThrownBy(() -> reader.startWithCallback(null))
            .isInstanceOf(IllegalStateException.class).hasMessage("callback is null");
    }

    @Test
    @DisplayName("A reader that has not started reports alive (no background thread to monitor yet)")
    void aliveBeforeStart() {
        assertThat(reader.isAlive()).isTrue();
    }

    @Test
    @DisplayName("Starts after the latest ledger id: history is not replayed, new rows are delivered once and in order")
    void deliversOnlyNewTransactionsInOrder() {
        when(repository.latestTransactionId()).thenReturn(10L, 12L);
        when(repository.findLatest(10L)).thenReturn(List.of(local(11, A, B, 100), local(12, B, A, 50)));

        reader.startWithCallback(collect);

        await().atMost(WAIT).until(() -> delivered.size() == 2);
        verify(repository, after(200).times(1)).findLatest(anyLong());
        verify(repository, never()).findLatest(-1L);
        assertThat(delivered).containsExactly(11L, 12L);
        assertThat(reader.isAlive()).isTrue();
    }

    @Test
    @DisplayName("An empty ledger starts at -1 and delivers the first transaction when it appears")
    void emptyLedgerStartsFromBeginning() {
        when(repository.latestTransactionId()).thenReturn(null, null, 1L);
        when(repository.findLatest(-1L)).thenReturn(List.of(local(1, A, B, 100)));

        reader.startWithCallback(collect);

        await().atMost(WAIT).until(() -> delivered.contains(1L));
        assertThat(delivered).containsExactly(1L);
    }

    @Test
    @DisplayName("Database unreachable at startup: the reader still starts and keeps polling")
    void outageAtStartup() {
        when(repository.latestTransactionId())
            .thenThrow(new DataAccessResourceFailureException("ledger-db down"))
            .thenReturn(3L);
        when(repository.findLatest(-1L)).thenReturn(List.of(local(3, A, B, 100)));

        reader.startWithCallback(collect);

        await().atMost(WAIT).until(() -> delivered.contains(3L));
        assertThat(reader.isAlive()).isTrue();
    }

    @Test
    @DisplayName("A transient outage while polling for the latest id is tolerated and the next row is delivered once")
    void transientOutageWhilePolling() {
        when(repository.latestTransactionId())
            .thenReturn(5L)
            .thenThrow(new DataAccessResourceFailureException("ledger-db down"))
            .thenThrow(new ResourceAccessException("connection reset"))
            .thenReturn(6L);
        when(repository.findLatest(5L)).thenReturn(List.of(local(6, A, B, 100)));

        reader.startWithCallback(collect);

        await().atMost(WAIT).until(() -> delivered.contains(6L));
        verify(repository, after(200).times(1)).findLatest(5L);
        assertThat(delivered).containsExactly(6L);
        assertThat(reader.isAlive()).isTrue();
    }

    @Test
    @DisplayName("Ledger id moving backwards (database reset) stops the reader and liveness reports it")
    void idRegressionStopsReader() {
        when(repository.latestTransactionId()).thenReturn(5L, 3L);

        reader.startWithCallback(collect);

        await().atMost(WAIT).until(() -> !reader.isAlive());
        verify(repository, never()).findLatest(anyLong());
        assertThat(delivered).isEmpty();
    }

    @Test
    @DisplayName("An outage while fetching new rows never leaves a silently stalled reader")
    void outageWhileFetchingRowsIsNeverSilent() {
        when(repository.latestTransactionId()).thenReturn(5L, 6L);
        when(repository.findLatest(5L))
            .thenThrow(new DataAccessResourceFailureException("ledger-db down"))
            .thenReturn(List.of(local(6, A, B, 100)));

        reader.startWithCallback(collect);

        // Either the reader recovers and delivers the row, or liveness reports it dead so it is restarted.
        await().dontCatchUncaughtExceptions().atMost(WAIT)
            .until(() -> delivered.contains(6L) || !reader.isAlive());
    }
}
