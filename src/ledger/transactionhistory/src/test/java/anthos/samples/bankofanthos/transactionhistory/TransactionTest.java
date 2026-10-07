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

package anthos.samples.bankofanthos.transactionhistory;

import static anthos.samples.bankofanthos.transactionhistory.TestFixtures.EXTERNAL_ROUTING;
import static anthos.samples.bankofanthos.transactionhistory.TestFixtures.LOCAL_ROUTING;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** JSON contract consumed by the frontend and the log-safe string form. */
class TransactionTest {

    private final Transaction deposit = TestFixtures.tx(42, "9999999999", EXTERNAL_ROUTING,
        "1111111111", LOCAL_ROUTING, 12345, Instant.parse("2026-01-15T10:00:00Z"));

    @Test
    @DisplayName("Serializes the fields the frontend renders, with the amount in integer cents")
    void jsonContract() {
        JsonNode json = new ObjectMapper().valueToTree(deposit);

        assertThat(json.get("fromAccountNum").asText()).isEqualTo("9999999999");
        assertThat(json.get("fromRoutingNum").asText()).isEqualTo(EXTERNAL_ROUTING);
        assertThat(json.get("toAccountNum").asText()).isEqualTo("1111111111");
        assertThat(json.get("toRoutingNum").asText()).isEqualTo(LOCAL_ROUTING);
        assertThat(json.get("amount").isInt()).isTrue();
        assertThat(json.get("amount").asInt()).isEqualTo(12345);
        assertThat(json.has("timestamp")).isTrue();
    }

    @Test
    @DisplayName("String form shows sender, dollar amount and recipient only")
    void stringForm() {
        assertThat(deposit.toString()).isEqualTo("9999999999->$123.45->1111111111");
    }
}
