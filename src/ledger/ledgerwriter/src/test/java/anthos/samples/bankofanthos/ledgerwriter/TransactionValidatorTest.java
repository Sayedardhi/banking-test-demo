/*
 * Copyright 2020, Google LLC.
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_INVALID_AMOUNT;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_INVALID_NUMBER;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_NOT_AUTHENTICATED;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_SEND_TO_SELF;

class TransactionValidatorTest {

    private TransactionValidator transactionValidator;

    @Mock
    private Transaction transaction;

    private static final String LOCAL_ROUTING_NUM = "123456789";
    private static final String AUTHED_ACCOUNT_NUM = "1234567890";
    private static final String TO_ACCOUNT_NUM = "5678901234";
    private static final String TO_ROUTING_NUM = "567891234";
    private static final Integer VALID_AMOUNT = 3755;
    private static final String EXTERNAL_ROUTING_NUM = "987654321";
    private static final String OTHER_ACCOUNT_NUM = "1111111111";

    private static final Integer[] VALID_TRANSACTION_AMOUNT = {
        1, VALID_AMOUNT, Integer.MAX_VALUE
    };

    @BeforeEach
    void setUp() {
        initMocks(this);
        transactionValidator = new TransactionValidator();

        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getToAccountNum()).thenReturn(TO_ACCOUNT_NUM);
        when(transaction.getToRoutingNum()).thenReturn(TO_ROUTING_NUM);
        when(transaction.getAmount()).thenReturn(VALID_AMOUNT);
    }

    @Test
    @DisplayName("Given the transaction is validated and amount is valid, no exception is thrown")
    void validateTransactionSuccess() {
        for (int i = 0; i < VALID_TRANSACTION_AMOUNT.length; i++) {
            // Given
            when(transaction.getAmount()).thenReturn(VALID_TRANSACTION_AMOUNT[i]);

            // Then
            assertDoesNotThrow(() -> {
                transactionValidator.validateTransaction(
                    LOCAL_ROUTING_NUM, AUTHED_ACCOUNT_NUM, transaction);
            });
        }
    }

    private void assertRejected(String expectedMessage) {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> transactionValidator.validateTransaction(
                        LOCAL_ROUTING_NUM, AUTHED_ACCOUNT_NUM, transaction));
        assertEquals(expectedMessage, e.getMessage());
    }

    @ParameterizedTest(name = "account number \"{0}\" is rejected")
    @ValueSource(strings = {"", "123456789", "12345678901", "12345abcde",
        "123456789 ", " 123456789", "-123456789", "12345.6789",
        "１２３４５６７８９０"})
    @DisplayName("Given a malformed sender or receiver account number, "
            + "reject with invalid account details")
    void rejectsMalformedAccountNumbers(String account) {
        when(transaction.getToAccountNum()).thenReturn(account);
        assertRejected(EXCEPTION_MESSAGE_INVALID_NUMBER);

        when(transaction.getToAccountNum()).thenReturn(TO_ACCOUNT_NUM);
        when(transaction.getFromAccountNum()).thenReturn(account);
        assertRejected(EXCEPTION_MESSAGE_INVALID_NUMBER);
    }

    @ParameterizedTest(name = "routing number \"{0}\" is rejected")
    @ValueSource(strings = {"", "12345678", "1234567890", "12345678a",
        "12345678 ", "1234-5678"})
    @DisplayName("Given a malformed sender or receiver routing number, "
            + "reject with invalid account details")
    void rejectsMalformedRoutingNumbers(String route) {
        when(transaction.getToRoutingNum()).thenReturn(route);
        assertRejected(EXCEPTION_MESSAGE_INVALID_NUMBER);

        when(transaction.getToRoutingNum()).thenReturn(TO_ROUTING_NUM);
        when(transaction.getFromRoutingNum()).thenReturn(route);
        assertRejected(EXCEPTION_MESSAGE_INVALID_NUMBER);
    }

    @Test
    @DisplayName("Given a local sender that is not the authenticated account, "
            + "reject as not authenticated")
    void rejectsLocalSenderOtherThanAuthenticatedAccount() {
        when(transaction.getFromAccountNum()).thenReturn(OTHER_ACCOUNT_NUM);
        assertRejected(EXCEPTION_MESSAGE_NOT_AUTHENTICATED);
    }

    @Test
    @DisplayName("Given a format error and an unauthenticated sender, "
            + "the format error is reported first")
    void reportsFormatErrorBeforeAuthorization() {
        when(transaction.getFromAccountNum()).thenReturn("not-an-acct");
        assertRejected(EXCEPTION_MESSAGE_INVALID_NUMBER);
    }

    @Test
    @DisplayName("Given an external sender (deposit), the sender does not "
            + "need to be the authenticated account")
    void allowsExternalSenderThatIsNotAuthenticatedAccount() {
        when(transaction.getFromAccountNum()).thenReturn(OTHER_ACCOUNT_NUM);
        when(transaction.getFromRoutingNum()).thenReturn(EXTERNAL_ROUTING_NUM);
        when(transaction.getToAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getToRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);

        assertDoesNotThrow(() -> transactionValidator.validateTransaction(
                LOCAL_ROUTING_NUM, AUTHED_ACCOUNT_NUM, transaction));
    }

    @Test
    @DisplayName("Given sender and receiver are the same account and route, "
            + "reject as send to self")
    void rejectsSendToSelf() {
        when(transaction.getToAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getToRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        assertRejected(EXCEPTION_MESSAGE_SEND_TO_SELF);
    }

    @Test
    @DisplayName("Given the same account number at a different bank, "
            + "the transfer is allowed")
    void allowsSameAccountNumberAtDifferentRoute() {
        when(transaction.getToAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getToRoutingNum()).thenReturn(EXTERNAL_ROUTING_NUM);

        assertDoesNotThrow(() -> transactionValidator.validateTransaction(
                LOCAL_ROUTING_NUM, AUTHED_ACCOUNT_NUM, transaction));
    }

    @ParameterizedTest(name = "amount {0} is rejected")
    @ValueSource(ints = {0, -1, -3755, Integer.MIN_VALUE})
    @DisplayName("Given a zero or negative amount, reject as invalid amount")
    void rejectsNonPositiveAmounts(int amount) {
        when(transaction.getAmount()).thenReturn(amount);
        assertRejected(EXCEPTION_MESSAGE_INVALID_AMOUNT);
    }

}
