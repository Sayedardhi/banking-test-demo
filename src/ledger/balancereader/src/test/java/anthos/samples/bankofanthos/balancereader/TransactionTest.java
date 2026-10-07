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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionTest {

    @Test
    @DisplayName("Ledger amounts are integer cents and render as dollars")
    void rendersCentsAsDollars() {
        Transaction t = TestTransactions.tx(7, "1000000001", "883745000",
            "1000000002", "883745000", 123_456);

        assertEquals(123_456, t.getAmount());
        assertEquals("1000000001->$1234.56->1000000002", t.toString());
    }
}
