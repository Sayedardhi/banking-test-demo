/*
 * Copyright 2026 Google LLC
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

package anthos.samples.bankofanthos.transactionhistory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionTest {

    @Test
    @DisplayName("toString renders cents as dollars with two decimals")
    void toStringFormatsCentsAsDollars() {
        Transaction t = TestFixtures.transaction(1, "1011226111", TestFixtures.LOCAL_ROUTING,
            "1033623433", TestFixtures.LOCAL_ROUTING, 1205);

        assertThat(t).hasToString("1011226111->$12.05->1033623433");
    }

    @Test
    @DisplayName("Single-cent and large amounts do not lose precision in toString")
    void toStringBoundaryAmounts() {
        assertThat(TestFixtures.transaction(1, "a", "r", "b", "r", 1).toString()).contains("$0.01");
        assertThat(TestFixtures.transaction(1, "a", "r", "b", "r", Integer.MAX_VALUE).toString())
            .contains("$21474836.47");
    }

    @Test
    @DisplayName("JSON exposes exactly the fields the frontend history table reads")
    void jsonContract() throws Exception {
        Transaction t = TestFixtures.transaction(42, "1011226111", TestFixtures.LOCAL_ROUTING,
            "9099791699", TestFixtures.EXTERNAL_ROUTING, 2500);

        JsonNode json = new ObjectMapper().valueToTree(t);

        assertThat(json.get("transactionId").asLong()).isEqualTo(42);
        assertThat(json.get("fromAccountNum").asText()).isEqualTo("1011226111");
        assertThat(json.get("fromRoutingNum").asText()).isEqualTo(TestFixtures.LOCAL_ROUTING);
        assertThat(json.get("toAccountNum").asText()).isEqualTo("9099791699");
        assertThat(json.get("toRoutingNum").asText()).isEqualTo(TestFixtures.EXTERNAL_ROUTING);
        assertThat(json.get("amount").asInt()).isEqualTo(2500);
        assertThat(json.get("timestamp").isNumber()).isTrue();
    }
}
