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
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.transaction;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionTest {

    @Test
    @DisplayName("The frontend's JSON field names map onto the ledger fields")
    void mapsFrontendJson() throws Exception {
        Transaction t = new ObjectMapper().readValue("{\"fromAccountNum\":\"1011226111\",\"fromRoutingNum\":"
                + "\"883745000\",\"toAccountNum\":\"1033623433\",\"toRoutingNum\":\"883745000\","
                + "\"amount\":2550,\"uuid\":\"7d3c\"}", Transaction.class);
        assertEquals("1011226111", t.getFromAccountNum());
        assertEquals("883745000", t.getFromRoutingNum());
        assertEquals("1033623433", t.getToAccountNum());
        assertEquals("883745000", t.getToRoutingNum());
        assertEquals(2550, t.getAmount());
        assertEquals("7d3c", t.getRequestUuid());
    }

    @Test
    @DisplayName("A missing request UUID reads as an empty string, never null")
    void missingUuidIsEmpty() {
        assertEquals("", transaction(payment(ALICE, BOB, 1, null)).getRequestUuid());
    }

    @Test
    @DisplayName("String form shows the amount in dollars with two decimals, cents preserved")
    void formatsAmountInDollars() {
        assertEquals(ALICE + "->$25.50->" + BOB, transaction(payment(ALICE, BOB, 2550, "u")).toString());
        assertEquals(ALICE + "->$0.01->" + BOB, transaction(payment(ALICE, BOB, 1, "u")).toString());
    }
}
