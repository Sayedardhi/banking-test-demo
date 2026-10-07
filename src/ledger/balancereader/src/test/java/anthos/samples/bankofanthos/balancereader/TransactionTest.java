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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionTest {

    @Test
    @DisplayName("Amounts are integer cents; toString renders dollars with two decimals")
    void centsAndDisplay() {
        Transaction t = TestTransactions.transaction(42, "1011226111", "883745000", "9099791699", "808889588", 123_405);

        assertThat(t.getTransactionId()).isEqualTo(42L);
        assertThat(t.getAmount()).isEqualTo(123_405);
        assertThat(t.getFromAccountNum()).isEqualTo("1011226111");
        assertThat(t.getFromRoutingNum()).isEqualTo("883745000");
        assertThat(t.getToAccountNum()).isEqualTo("9099791699");
        assertThat(t.getToRoutingNum()).isEqualTo("808889588");
        assertThat(t).hasToString("1011226111->$1234.05->9099791699");
    }
}
