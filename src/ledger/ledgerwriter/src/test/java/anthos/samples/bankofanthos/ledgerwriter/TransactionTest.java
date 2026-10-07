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

package anthos.samples.bankofanthos.ledgerwriter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** JSON mapping of the transaction request body submitted by the frontend. */
class TransactionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Transaction parse(String json) throws Exception {
        return MAPPER.readValue(json, Transaction.class);
    }

    @Test
    @DisplayName("Frontend JSON fields map onto the ledger transaction")
    void mapsRequestFields() throws Exception {
        Transaction t = parse("{\"fromAccountNum\":\"1011226111\",\"fromRoutingNum\":\"883745000\","
                + "\"toAccountNum\":\"1033623433\",\"toRoutingNum\":\"808889588\",\"amount\":12345,"
                + "\"uuid\":\"req-1\"}");

        assertEquals("1011226111", t.getFromAccountNum());
        assertEquals("883745000", t.getFromRoutingNum());
        assertEquals("1033623433", t.getToAccountNum());
        assertEquals("808889588", t.getToRoutingNum());
        assertEquals(12345, t.getAmount());
        assertEquals("req-1", t.getRequestUuid());
    }

    @Test
    @DisplayName("Amounts are integer cents and render as dollars in the log representation")
    void rendersCentsAsDollars() throws Exception {
        Transaction t = parse("{\"fromAccountNum\":\"1011226111\",\"toAccountNum\":\"1033623433\",\"amount\":12345}");

        assertEquals("1011226111->$123.45->1033623433", t.toString());
    }

    @Test
    @DisplayName("A request without a UUID reports an empty UUID rather than null")
    void missingUuidIsEmpty() throws Exception {
        assertEquals("", parse("{\"amount\":1}").getRequestUuid());
    }
}
