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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonMappingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Given the frontend JSON payload, all fields bind to the "
            + "transaction including the request uuid")
    void bindsFrontendPayload() throws Exception {
        Transaction t = mapper.readValue("{\"fromAccountNum\":\"1234567890\","
                + "\"fromRoutingNum\":\"883745000\",\"toAccountNum\":\"0987654321\","
                + "\"toRoutingNum\":\"883745000\",\"amount\":1999,"
                + "\"uuid\":\"8f7c\"}", Transaction.class);

        assertEquals("1234567890", t.getFromAccountNum());
        assertEquals("883745000", t.getFromRoutingNum());
        assertEquals("0987654321", t.getToAccountNum());
        assertEquals("883745000", t.getToRoutingNum());
        assertEquals(1999, t.getAmount());
        assertEquals("8f7c", t.getRequestUuid());
    }

    @Test
    @DisplayName("Given no uuid, getRequestUuid returns an empty string")
    void missingUuidIsEmptyString() throws Exception {
        Transaction t = mapper.readValue("{\"amount\":1}", Transaction.class);
        assertEquals("", t.getRequestUuid());
    }

    @Test
    @DisplayName("Given an amount larger than a 32-bit int, binding fails")
    void rejectsAmountOverflow() {
        assertThrows(JsonMappingException.class, () -> mapper.readValue(
                "{\"amount\":2147483648}", Transaction.class));
    }

    @Test
    @DisplayName("toString renders cents as dollars between sender and receiver")
    void toStringFormatsDollars() throws Exception {
        Transaction t = mapper.readValue("{\"fromAccountNum\":\"1234567890\","
                + "\"toAccountNum\":\"0987654321\",\"amount\":1999}",
                Transaction.class);
        assertEquals("1234567890->$19.99->0987654321", t.toString());
    }
}
