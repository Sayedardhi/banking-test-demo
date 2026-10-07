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

import org.springframework.test.util.ReflectionTestUtils;

/** Builds synthetic ledger rows; Transaction has no public constructor. */
final class TestTransactions {

    private TestTransactions() {
    }

    static Transaction tx(long id, String fromAcct, String fromRoute,
        String toAcct, String toRoute, int amountCents) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "transactionId", id);
        ReflectionTestUtils.setField(t, "fromAccountNum", fromAcct);
        ReflectionTestUtils.setField(t, "fromRoutingNum", fromRoute);
        ReflectionTestUtils.setField(t, "toAccountNum", toAcct);
        ReflectionTestUtils.setField(t, "toRoutingNum", toRoute);
        ReflectionTestUtils.setField(t, "amount", amountCents);
        return t;
    }
}
