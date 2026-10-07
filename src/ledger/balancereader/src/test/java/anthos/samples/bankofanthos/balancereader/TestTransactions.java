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

import org.springframework.test.util.ReflectionTestUtils;

/** Builds Transaction entities (no public constructor or setters) for unit tests. */
final class TestTransactions {

    static final String LOCAL_ROUTING = "883745000";
    static final String EXTERNAL_ROUTING = "808889588";

    private TestTransactions() {
    }

    static Transaction transaction(long id, String fromAccount, String fromRouting,
        String toAccount, String toRouting, int amountCents) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "transactionId", id);
        ReflectionTestUtils.setField(t, "fromAccountNum", fromAccount);
        ReflectionTestUtils.setField(t, "fromRoutingNum", fromRouting);
        ReflectionTestUtils.setField(t, "toAccountNum", toAccount);
        ReflectionTestUtils.setField(t, "toRoutingNum", toRouting);
        ReflectionTestUtils.setField(t, "amount", amountCents);
        return t;
    }

    static Transaction local(long id, String from, String to, int amountCents) {
        return transaction(id, from, LOCAL_ROUTING, to, LOCAL_ROUTING, amountCents);
    }
}
