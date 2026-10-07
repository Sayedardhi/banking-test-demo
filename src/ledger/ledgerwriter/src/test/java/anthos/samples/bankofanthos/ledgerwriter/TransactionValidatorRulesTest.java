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

import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_INVALID_AMOUNT;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_INVALID_NUMBER;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_NOT_AUTHENTICATED;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_SEND_TO_SELF;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Validation rules for ledger transactions, exercised on real Transaction objects. */
class TransactionValidatorRulesTest {

    static final String LOCAL_ROUTING = "883745000";
    static final String EXTERNAL_ROUTING = "808889588";
    static final String AUTHED_ACCT = "1011226111";
    static final String OTHER_ACCT = "1033623433";
    static final String EXTERNAL_ACCT = "9099791699";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TransactionValidator validator = new TransactionValidator();

    static Transaction transaction(String fromAcct, String fromRoute,
            String toAcct, String toRoute, Integer amount) {
        Map<String, Object> json = new HashMap<>();
        json.put("fromAccountNum", fromAcct);
        json.put("fromRoutingNum", fromRoute);
        json.put("toAccountNum", toAcct);
        json.put("toRoutingNum", toRoute);
        json.put("amount", amount);
        return MAPPER.convertValue(json, Transaction.class);
    }

    static Transaction payment(int amount) {
        return transaction(AUTHED_ACCT, LOCAL_ROUTING, OTHER_ACCT, LOCAL_ROUTING, amount);
    }

    private void assertRejected(Transaction transaction, String expectedMessage) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> validator.validateTransaction(LOCAL_ROUTING, AUTHED_ACCT, transaction));
        assertEquals(expectedMessage, e.getMessage());
    }

    private void assertAccepted(Transaction transaction) {
        assertDoesNotThrow(() -> validator.validateTransaction(LOCAL_ROUTING, AUTHED_ACCT, transaction));
    }

    @ParameterizedTest(name = "sender account \"{0}\"")
    @ValueSource(strings = {"101122611", "10112261111", "10112261a1", "", " 1011226111", "1011226111\n", "-101122611"})
    @DisplayName("Malformed sender account numbers are rejected")
    void rejectsMalformedSenderAccount(String account) {
        assertRejected(transaction(account, LOCAL_ROUTING, OTHER_ACCT, LOCAL_ROUTING, 100),
                EXCEPTION_MESSAGE_INVALID_NUMBER);
    }

    @ParameterizedTest(name = "recipient account \"{0}\"")
    @ValueSource(strings = {"103362343", "10336234333", "103362343x", ""})
    @DisplayName("Malformed recipient account numbers are rejected")
    void rejectsMalformedRecipientAccount(String account) {
        assertRejected(transaction(AUTHED_ACCT, LOCAL_ROUTING, account, LOCAL_ROUTING, 100),
                EXCEPTION_MESSAGE_INVALID_NUMBER);
    }

    @ParameterizedTest(name = "sender routing \"{0}\"")
    @ValueSource(strings = {"88374500", "8837450000", "88374500x", ""})
    @DisplayName("Malformed sender routing numbers are rejected")
    void rejectsMalformedSenderRouting(String routing) {
        assertRejected(transaction(EXTERNAL_ACCT, routing, AUTHED_ACCT, LOCAL_ROUTING, 100),
                EXCEPTION_MESSAGE_INVALID_NUMBER);
    }

    @ParameterizedTest(name = "recipient routing \"{0}\"")
    @ValueSource(strings = {"88374500", "8837450000", "88374500x", ""})
    @DisplayName("Malformed recipient routing numbers are rejected")
    void rejectsMalformedRecipientRouting(String routing) {
        assertRejected(transaction(AUTHED_ACCT, LOCAL_ROUTING, OTHER_ACCT, routing, 100),
                EXCEPTION_MESSAGE_INVALID_NUMBER);
    }

    @Test
    @DisplayName("A customer cannot move money out of another customer's local account")
    void rejectsInternalTransferFromAnotherCustomersAccount() {
        assertRejected(transaction(OTHER_ACCT, LOCAL_ROUTING, AUTHED_ACCT, LOCAL_ROUTING, 100),
                EXCEPTION_MESSAGE_NOT_AUTHENTICATED);
    }

    @Test
    @DisplayName("External deposits into the authenticated account are allowed")
    void allowsExternalDepositIntoAuthenticatedAccount() {
        assertAccepted(transaction(EXTERNAL_ACCT, EXTERNAL_ROUTING, AUTHED_ACCT, LOCAL_ROUTING, 100));
    }

    @Test
    @DisplayName("Sending to the same account and routing number is rejected")
    void rejectsSendToSelf() {
        assertRejected(transaction(AUTHED_ACCT, LOCAL_ROUTING, AUTHED_ACCT, LOCAL_ROUTING, 100),
                EXCEPTION_MESSAGE_SEND_TO_SELF);
    }

    @Test
    @DisplayName("Same account number at a different bank is a different account")
    void allowsSameAccountNumberAtDifferentBank() {
        assertAccepted(transaction(AUTHED_ACCT, LOCAL_ROUTING, AUTHED_ACCT, EXTERNAL_ROUTING, 100));
    }

    @ParameterizedTest(name = "amount {0}")
    @ValueSource(ints = {0, -1, -10000, Integer.MIN_VALUE})
    @DisplayName("Zero and negative amounts are rejected")
    void rejectsNonPositiveAmounts(int amount) {
        assertRejected(payment(amount), EXCEPTION_MESSAGE_INVALID_AMOUNT);
    }

    @Test
    @DisplayName("One cent is the smallest valid amount")
    void acceptsOneCent() {
        assertAccepted(payment(1));
    }

    @Test
    @DisplayName("Malformed account details are reported before authorization checks")
    void malformedDetailsReportedFirst() {
        assertRejected(transaction(OTHER_ACCT, LOCAL_ROUTING, "bad", LOCAL_ROUTING, -5),
                EXCEPTION_MESSAGE_INVALID_NUMBER);
    }

    @Test
    @DisplayName("A transaction without an amount is rejected as an invalid amount, not a crash")
    void rejectsMissingAmountAsValidationError() {
        assertRejected(transaction(AUTHED_ACCT, LOCAL_ROUTING, OTHER_ACCT, LOCAL_ROUTING, null),
                EXCEPTION_MESSAGE_INVALID_AMOUNT);
    }

    @Test
    @DisplayName("A transaction without a recipient account is rejected as invalid details, not a crash")
    void rejectsMissingRecipientAsValidationError() {
        assertRejected(transaction(AUTHED_ACCT, LOCAL_ROUTING, null, LOCAL_ROUTING, 100),
                EXCEPTION_MESSAGE_INVALID_NUMBER);
    }
}
