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
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.deposit;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.payment;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** balancereader failures must fail closed: no payment is written when the balance cannot be confirmed. */
class DependencyFailureIntegrationTest extends SharedLedgerIntegrationSupport {

    private final String alice = token(KEYS, ALICE);

    @Test
    @DisplayName("balancereader answers 503: 500, nothing written")
    void balanceReaderError() {
        BALANCES.respondWith(503);
        assertEquals(500, submit(alice, payment(ALICE, BOB, 100, UUID.randomUUID().toString())).statusCode());
        assertEquals(List.of(), ledger());
    }

    @Test
    @DisplayName("balancereader offline: 500, nothing written; external deposits still succeed")
    void balanceReaderOffline() {
        BALANCES.stop();
        try {
            assertEquals(500, submit(alice, payment(ALICE, BOB, 100, UUID.randomUUID().toString())).statusCode());
            assertEquals(List.of(), ledger());
            assertEquals(201, submit(alice, deposit(ALICE, 100, UUID.randomUUID().toString())).statusCode());
            assertEquals(1, ledger().size());
        } finally {
            BALANCES.start();
        }
    }
}
