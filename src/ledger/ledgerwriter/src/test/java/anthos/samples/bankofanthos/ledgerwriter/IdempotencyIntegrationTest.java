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

package anthos.samples.bankofanthos.ledgerwriter;

import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.ALICE;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.BOB;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.payment;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Request-UUID idempotency against the real ledger: a retried payment must move money once. */
class IdempotencyIntegrationTest extends SharedLedgerIntegrationSupport {

    private final String alice = token(KEYS, ALICE);

    @Test
    @DisplayName("Sequential replay of a request UUID: 400 duplicate, one row, one balance lookup")
    void sequentialReplay() {
        BALANCES.balance(ALICE, 10_000);
        String id = UUID.randomUUID().toString();
        assertEquals(201, submit(alice, payment(ALICE, BOB, 700, id)).statusCode());
        HttpResponse<String> replay = submit(alice, payment(ALICE, BOB, 700, id));
        assertEquals(400, replay.statusCode());
        assertEquals("duplicate transaction uuid", replay.body());
        assertEquals(1, ledger().size());
        assertEquals(1, BALANCES.lookups().size());
    }

    @Test
    @DisplayName("A UUID rejected for insufficient funds can be retried once funded")
    void rejectedUuidRetried() {
        String id = UUID.randomUUID().toString();
        BALANCES.balance(ALICE, 100);
        assertEquals(400, submit(alice, payment(ALICE, BOB, 700, id)).statusCode());
        BALANCES.balance(ALICE, 1000);
        assertEquals(201, submit(alice, payment(ALICE, BOB, 700, id)).statusCode());
        assertEquals(1, ledger().size());
    }

    @Test
    @DisplayName("Control: two concurrent payments with different UUIDs are both written")
    void concurrentDistinctPayments() {
        BALANCES.balance(ALICE, 10_000);
        BALANCES.holdUntilConcurrent(2);
        List<CompletableFuture<HttpResponse<String>>> calls = List.of(
                submitAsync(alice, payment(ALICE, BOB, 700, UUID.randomUUID().toString())),
                submitAsync(alice, payment(ALICE, BOB, 700, UUID.randomUUID().toString())));
        assertEquals(List.of(201, 201), calls.stream().map(c -> c.join().statusCode()).toList());
        assertEquals(2, ledger().size());
    }

    @Test
    @DisplayName("Two concurrent submissions of the same UUID (double-click / client retry) write one row")
    void concurrentDuplicate() {
        BALANCES.balance(ALICE, 10_000);
        BALANCES.holdUntilConcurrent(2);
        String id = UUID.randomUUID().toString();
        List<CompletableFuture<HttpResponse<String>>> calls = List.of(
                submitAsync(alice, payment(ALICE, BOB, 700, id)),
                submitAsync(alice, payment(ALICE, BOB, 700, id)));
        List<Integer> statuses = calls.stream().map(c -> c.join().statusCode()).sorted().toList();
        assertEquals(1, ledger().size(), "rows written for one request UUID; statuses " + statuses);
        assertEquals(List.of(201, 400), statuses);
    }
}
