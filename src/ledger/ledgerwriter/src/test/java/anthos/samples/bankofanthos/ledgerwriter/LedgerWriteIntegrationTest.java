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
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.EXTERNAL_ACCOUNT;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.EXTERNAL_ROUTING;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.LOCAL_ROUTING;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.body;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.deposit;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.json;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.newRsaKeyPair;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.payment;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.token;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** POST /transactions through HTTP, Spring, JPA and a real PostgreSQL ledger with the production schema. */
class LedgerWriteIntegrationTest extends SharedLedgerIntegrationSupport {

    private final String alice = token(KEYS, ALICE);

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private void assertRejected(HttpResponse<String> response, int status, String message) {
        assertAll(
                () -> assertEquals(status, response.statusCode(), "status"),
                () -> assertEquals(message, response.body(), "body"),
                () -> assertEquals(List.of(), ledger(), "ledger must be unchanged"));
    }

    @Test
    @DisplayName("Payment within balance: 201 and exactly one ledger row with the submitted details")
    void paymentPersistsOneRow() {
        BALANCES.balance(ALICE, 10_000);
        Instant before = Instant.now();
        HttpResponse<String> response = submit(alice, payment(ALICE, BOB, 2550, uuid()));
        Instant after = Instant.now();

        assertEquals(201, response.statusCode());
        assertEquals("ok", response.body());
        List<Map<String, Object>> rows = ledger();
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertAll(
                () -> assertEquals(ALICE, row.get("from_acct")),
                () -> assertEquals(LOCAL_ROUTING, row.get("from_route")),
                () -> assertEquals(BOB, row.get("to_acct")),
                () -> assertEquals(LOCAL_ROUTING, row.get("to_route")),
                () -> assertEquals(2550, row.get("amount")),
                () -> {
                    Instant written = ((Timestamp) row.get("timestamp")).toInstant();
                    assertTrue(!written.isBefore(before.minus(Duration.ofMinutes(1)))
                            && !written.isAfter(after.plus(Duration.ofMinutes(1))), "timestamp " + written);
                });
        assertEquals(List.of(new BalanceReaderStub.Lookup("/balances/" + ALICE, "Bearer " + alice)),
                BALANCES.lookups(), "balance checked once, for the sender, with the caller's token");
    }

    @Test
    @DisplayName("External deposit: written without a balance lookup")
    void externalDeposit() {
        assertEquals(201, submit(alice, deposit(ALICE, 100_000, uuid())).statusCode());
        Map<String, Object> row = ledger().get(0);
        assertEquals(EXTERNAL_ACCOUNT, row.get("from_acct"));
        assertEquals(EXTERNAL_ROUTING, row.get("from_route"));
        assertEquals(100_000, row.get("amount"));
        assertEquals(List.of(), BALANCES.lookups());
    }

    @Test
    @DisplayName("Overdraft by one cent is rejected and leaves the ledger empty; the exact balance is accepted")
    void overdraftBoundary() {
        BALANCES.balance(ALICE, 5000);
        assertRejected(submit(alice, payment(ALICE, BOB, 5001, uuid())), 400, "insufficient balance");
        assertEquals(201, submit(alice, payment(ALICE, BOB, 5000, uuid())).statusCode());
        assertEquals(5000, ledger().get(0).get("amount"));
    }

    @Test
    @DisplayName("A valid token for Bob cannot debit Alice: 400, nothing written, no balance lookup")
    void cannotDebitAnotherCustomer() {
        assertRejected(submit(token(KEYS, BOB), payment(ALICE, BOB, 100, uuid())), 400,
                "sender not authenticated");
        assertEquals(List.of(), BALANCES.lookups());
    }

    @ParameterizedTest(name = "amount {0}")
    @ValueSource(ints = {0, -1, -10_000, Integer.MIN_VALUE})
    @DisplayName("Non-positive amounts are rejected without writing")
    void nonPositiveAmounts(int amount) {
        BALANCES.balance(ALICE, 10_000);
        assertRejected(submit(alice, payment(ALICE, BOB, amount, uuid())), 400, "invalid amount");
    }

    @Test
    @DisplayName("Self-transfer is rejected without writing")
    void selfTransfer() {
        assertRejected(submit(alice, payment(ALICE, ALICE, 100, uuid())), 400, "can't send to self");
    }

    @Test
    @DisplayName("Malformed recipient account is rejected without writing")
    void malformedRecipient() {
        assertRejected(submit(alice, payment(ALICE, "10336234330", 100, uuid())), 400, "invalid account details");
    }

    @Test
    @DisplayName("Missing Authorization header: 400, nothing written")
    void missingAuthorization() {
        assertEquals(400, post(null, "application/json", json(payment(ALICE, BOB, 100, uuid()))).statusCode());
        assertEquals(List.of(), ledger());
    }

    @Test
    @DisplayName("Token signed by an unknown key: 401, nothing written")
    void forgedToken() {
        assertRejected(submit(token(newRsaKeyPair(), ALICE), deposit(ALICE, 100, uuid())), 401,
                LedgerWriterController.UNAUTHORIZED_CODE);
    }

    @Test
    @DisplayName("Expired token: 401, nothing written")
    void expiredToken() {
        assertRejected(submit(token(KEYS, ALICE, Instant.now().minusSeconds(5)), deposit(ALICE, 100, uuid())), 401,
                LedgerWriterController.UNAUTHORIZED_CODE);
    }

    @Test
    @DisplayName("Authorization header without a token is an authentication failure (401), not a 500")
    void emptyBearer() {
        HttpResponse<String> response = post("Bearer ", "application/json", json(deposit(ALICE, 100, uuid())));
        assertEquals(401, response.statusCode());
        assertEquals(List.of(), ledger());
    }

    @Test
    @DisplayName("Non-JSON content type: 415, nothing written")
    void wrongContentType() {
        assertEquals(415, post("Bearer " + alice, "text/plain", json(deposit(ALICE, 100, uuid()))).statusCode());
        assertEquals(List.of(), ledger());
    }

    @Test
    @DisplayName("Malformed JSON: 400, nothing written")
    void malformedJson() {
        assertEquals(400, post("Bearer " + alice, "application/json", "{\"amount\": 100,").statusCode());
        assertEquals(List.of(), ledger());
    }

    @Test
    @DisplayName("Amount above the 32-bit cent range: 400, nothing written")
    void amountOverflow() {
        String overflow = json(deposit(ALICE, 1, uuid())).replace("\"amount\":1", "\"amount\":2147483648");
        assertEquals(400, post("Bearer " + alice, "application/json", overflow).statusCode());
        assertEquals(List.of(), ledger());
    }

    @Test
    @DisplayName("Fractional cents are rejected (400), not silently truncated into the ledger")
    void fractionalCents() {
        HttpResponse<String> response = submit(alice,
                body(EXTERNAL_ACCOUNT, EXTERNAL_ROUTING, ALICE, LOCAL_ROUTING, 2550.75, uuid()));
        assertAll(
                () -> assertEquals(400, response.statusCode(), "status"),
                () -> assertEquals(List.of(), ledger(), "ledger must be unchanged"));
    }

    @ParameterizedTest(name = "without {0}")
    @ValueSource(strings = {"fromAccountNum", "fromRoutingNum", "toAccountNum", "toRoutingNum", "amount"})
    @DisplayName("A request missing a required field is a 400 validation error, nothing written")
    void missingField(String field) {
        Map<String, Object> request = payment(ALICE, BOB, 100, uuid());
        request.remove(field);
        BALANCES.balance(ALICE, 10_000);
        HttpResponse<String> response = submit(alice, request);
        assertAll(
                () -> assertEquals(400, response.statusCode(), "status"),
                () -> assertEquals(List.of(), ledger(), "ledger must be unchanged"));
    }

    @Test
    @DisplayName("The ledger is append-only: UPDATE and DELETE leave a written transaction intact")
    void ledgerIsAppendOnly() {
        assertEquals(201, submit(alice, deposit(ALICE, 4200, uuid())).statusCode());
        assertEquals(0, jdbc.update("UPDATE transactions SET amount = 1"));
        assertEquals(0, jdbc.update("DELETE FROM transactions"));
        assertEquals(4200, ledger().get(0).get("amount"));
    }
}
