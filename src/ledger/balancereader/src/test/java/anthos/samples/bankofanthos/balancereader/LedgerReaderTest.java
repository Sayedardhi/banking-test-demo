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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Background ledger polling. The repository is substituted with a
 * controllable in-memory ledger; waits are condition-based.
 */
class LedgerReaderTest {

    private static final String LOCAL = "883745000";
    private static final String A = "1000000001";
    private static final String B = "1000000002";
    private static final Duration WAIT = Duration.ofSeconds(5);

    private TransactionRepository repository;
    private LedgerReader reader;
    private final AtomicLong remoteLatest = new AtomicLong(-1);
    private final AtomicBoolean ledgerDown = new AtomicBoolean(false);
    private final List<Transaction> ledger = new CopyOnWriteArrayList<>();
    private final List<Long> processed = new CopyOnWriteArrayList<>();
    private boolean started;

    @BeforeEach
    void setUp() {
        repository = mock(TransactionRepository.class);
        when(repository.latestTransactionId()).thenAnswer(inv -> {
            if (ledgerDown.get()) {
                throw new DataAccessResourceFailureException("ledger down");
            }
            long id = remoteLatest.get();
            return id < 0 && id != Long.MIN_VALUE ? null : id;
        });
        when(repository.findLatest(anyLong())).thenAnswer(inv -> {
            long after = inv.getArgument(0);
            return ledger.stream().filter(t -> t.getTransactionId() > after).toList();
        });
        reader = new LedgerReader();
        ReflectionTestUtils.setField(reader, "dbRepo", repository);
        ReflectionTestUtils.setField(reader, "pollMs", 10);
        ReflectionTestUtils.setField(reader, "localRoutingNum", LOCAL);
    }

    @AfterEach
    void stopReader() {
        // Driving the remote id below the local id is the reader's only exit path.
        if (!started) {
            return;
        }
        ledgerDown.set(false);
        remoteLatest.set(Long.MIN_VALUE);
        await().atMost(WAIT).until(() -> !reader.isAlive());
    }

    private void append(long id, int amount) {
        ledger.add(TestTransactions.tx(id, A, LOCAL, B, LOCAL, amount));
        remoteLatest.set(id);
    }

    private void start() {
        started = true;
        reader.startWithCallback(t -> processed.add(t.getTransactionId()));
    }

    @Test
    @DisplayName("A null callback is rejected")
    void nullCallbackIsRejected() {
        assertThrows(IllegalStateException.class, () -> reader.startWithCallback(null));
        assertTrue(reader.isAlive(), "no thread started");
    }

    @Test
    @DisplayName("Existing ledger history is not replayed on startup")
    void historyIsNotReplayedOnStartup() {
        append(1, 100);
        append(2, 200);
        start();
        append(3, 300);

        await().atMost(WAIT).until(() -> processed.contains(3L));
        assertEquals(List.of(3L), processed);
        verify(repository, never()).findLatest(-1L);
    }

    @Test
    @DisplayName("New transactions are delivered once each, in ledger order")
    void newTransactionsDeliveredOnceInOrder() throws Exception {
        start();
        append(1, 100);
        append(2, 200);
        await().atMost(WAIT).until(() -> processed.size() >= 2);
        append(3, 300);
        await().atMost(WAIT).until(() -> processed.contains(3L));

        Thread.sleep(100);
        assertEquals(List.of(1L, 2L, 3L), processed);
        assertTrue(reader.isAlive());
    }

    @Test
    @DisplayName("Ledger outage at startup does not prevent the reader from starting")
    void ledgerOutageAtStartupIsTolerated() {
        ledgerDown.set(true);
        start();
        assertTrue(reader.isAlive());

        ledgerDown.set(false);
        append(1, 100);
        await().atMost(WAIT).until(() -> processed.contains(1L));
    }

    @Test
    @DisplayName("Ledger outage while polling keeps the reader alive and resumes afterwards")
    void ledgerOutageWhilePollingIsTolerated() throws Exception {
        start();
        ledgerDown.set(true);
        append(1, 100);
        Thread.sleep(100);
        assertTrue(processed.isEmpty(), "nothing delivered while ledger unreachable");
        assertTrue(reader.isAlive());

        ledgerDown.set(false);
        await().atMost(WAIT).until(() -> processed.contains(1L));
        assertEquals(List.of(1L), processed);
    }

    @Test
    @DisplayName("Ledger moving backwards (out of sync) stops the reader and reports unhealthy")
    void outOfSyncLedgerStopsReader() {
        append(5, 100);
        start();
        remoteLatest.set(3);

        await().atMost(WAIT).until(() -> !reader.isAlive());
        assertFalse(reader.isAlive());
        assertTrue(processed.isEmpty());
    }
}
