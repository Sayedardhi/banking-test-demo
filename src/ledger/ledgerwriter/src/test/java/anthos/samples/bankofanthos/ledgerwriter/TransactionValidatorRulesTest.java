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

import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_INVALID_AMOUNT;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_INVALID_NUMBER;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_NOT_AUTHENTICATED;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_SEND_TO_SELF;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.ALICE;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.BOB;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.EXTERNAL_ACCOUNT;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.EXTERNAL_ROUTING;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.LOCAL_ROUTING;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.body;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.transaction;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Business rules applied to every transaction before it may reach the ledger. Uses real Transaction objects. */
class TransactionValidatorRulesTest {

    private final TransactionValidator validator = new TransactionValidator();

    private void validate(String authedAccount, Map<String, Object> request) {
        validator.validateTransaction(LOCAL_ROUTING, authedAccount, transaction(request));
    }

    private static String rejection(Executable call) {
        return assertThrows(IllegalArgumentException.class, call).getMessage();
    }

    // Lengths 9/11, letters, padding, sign, non-ASCII digits, trailing newline (regex must match the whole value).
    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"", "101122611", "10112261111", "10112261aa", " 101122611", "101122611 ",
        "-101122611", "\uff11\uff10\uff11\uff11\uff12\uff12\uff16\uff11\uff11\uff11", "1011226111\n"})
    @DisplayName("Recipient account numbers that are not exactly 10 ASCII digits are rejected")
    void rejectsMalformedRecipientAccount(String account) {
        assertEquals(EXCEPTION_MESSAGE_INVALID_NUMBER,
                rejection(() -> validate(ALICE, body(ALICE, LOCAL_ROUTING, account, LOCAL_ROUTING, 100, "u"))));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"", "909979169", "90997916999", "909979169x", "9099791699\n"})
    @DisplayName("Sender account numbers that are not exactly 10 digits are rejected, also for external senders")
    void rejectsMalformedSenderAccount(String account) {
        assertEquals(EXCEPTION_MESSAGE_INVALID_NUMBER,
                rejection(() -> validate(ALICE, body(account, EXTERNAL_ROUTING, ALICE, LOCAL_ROUTING, 100, "u"))));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"", "88374500", "8837450000", "88374500a", "883745000\n", " 83745000"})
    @DisplayName("Routing numbers that are not exactly 9 digits are rejected on either side")
    void rejectsMalformedRoutingNumbers(String routing) {
        assertAll(
            () -> assertEquals(EXCEPTION_MESSAGE_INVALID_NUMBER,
                rejection(() -> validate(ALICE, body(EXTERNAL_ACCOUNT, routing, ALICE, LOCAL_ROUTING, 100, "u")))),
            () -> assertEquals(EXCEPTION_MESSAGE_INVALID_NUMBER,
                rejection(() -> validate(ALICE, body(ALICE, LOCAL_ROUTING, BOB, routing, 100, "u")))));
    }

    @Test
    @DisplayName("A local payment from an account other than the token's account is rejected")
    void rejectsLocalPaymentFromAnotherCustomersAccount() {
        assertEquals(EXCEPTION_MESSAGE_NOT_AUTHENTICATED,
                rejection(() -> validate(BOB, body(ALICE, LOCAL_ROUTING, BOB, LOCAL_ROUTING, 100, "u"))));
    }

    @Test
    @DisplayName("A local payment is rejected when the token carries no account claim")
    void rejectsLocalPaymentWhenTokenHasNoAccount() {
        assertEquals(EXCEPTION_MESSAGE_NOT_AUTHENTICATED,
                rejection(() -> validate(null, body(ALICE, LOCAL_ROUTING, BOB, LOCAL_ROUTING, 100, "u"))));
    }

    @Test
    @DisplayName("A deposit from an external bank does not require the sender to be the authenticated customer")
    void allowsExternalSenderThatIsNotTheAuthenticatedCustomer() {
        assertDoesNotThrow(() -> validate(ALICE, body(EXTERNAL_ACCOUNT, EXTERNAL_ROUTING, ALICE, LOCAL_ROUTING, 100, "u")));
    }

    @Test
    @DisplayName("Sending to the same account and routing number is rejected")
    void rejectsSendToSelf() {
        assertEquals(EXCEPTION_MESSAGE_SEND_TO_SELF,
                rejection(() -> validate(ALICE, body(ALICE, LOCAL_ROUTING, ALICE, LOCAL_ROUTING, 100, "u"))));
    }

    @Test
    @DisplayName("The same account number at a different bank is a different account and is allowed")
    void allowsSameAccountNumberAtDifferentBank() {
        assertDoesNotThrow(() -> validate(ALICE, body(ALICE, LOCAL_ROUTING, ALICE, EXTERNAL_ROUTING, 100, "u")));
    }

    @ParameterizedTest(name = "[{index}] amount={0}")
    @ValueSource(ints = {0, -1, -2550, Integer.MIN_VALUE})
    @DisplayName("Zero and negative amounts are rejected for payments and deposits")
    void rejectsNonPositiveAmounts(int amount) {
        assertAll(
            () -> assertEquals(EXCEPTION_MESSAGE_INVALID_AMOUNT,
                rejection(() -> validate(ALICE, body(ALICE, LOCAL_ROUTING, BOB, LOCAL_ROUTING, amount, "u")))),
            () -> assertEquals(EXCEPTION_MESSAGE_INVALID_AMOUNT,
                rejection(() -> validate(ALICE, body(EXTERNAL_ACCOUNT, EXTERNAL_ROUTING, ALICE, LOCAL_ROUTING,
                        amount, "u")))));
    }

    @Test
    @DisplayName("Format errors are reported before authorization and amount errors")
    void reportsFormatErrorFirst() {
        assertEquals(EXCEPTION_MESSAGE_INVALID_NUMBER,
                rejection(() -> validate(BOB, body(ALICE, LOCAL_ROUTING, "123", LOCAL_ROUTING, -5, "u"))));
    }

    @Test
    @DisplayName("A request missing a required field is rejected as a validation error, not a NullPointerException")
    void rejectsMissingFieldsAsValidationErrors() {
        assertAll(
            missing(body(null, LOCAL_ROUTING, BOB, LOCAL_ROUTING, 100, "u"), "fromAccountNum"),
            missing(body(ALICE, null, BOB, LOCAL_ROUTING, 100, "u"), "fromRoutingNum"),
            missing(body(ALICE, LOCAL_ROUTING, null, LOCAL_ROUTING, 100, "u"), "toAccountNum"),
            missing(body(ALICE, LOCAL_ROUTING, BOB, null, 100, "u"), "toRoutingNum"),
            missing(body(ALICE, LOCAL_ROUTING, BOB, LOCAL_ROUTING, null, "u"), "amount"));
    }

    private Executable missing(Map<String, Object> request, String field) {
        return () -> assertThrows(IllegalArgumentException.class, () -> validate(ALICE, request),
                "missing " + field + " must raise IllegalArgumentException (mapped to HTTP 400)");
    }
}
