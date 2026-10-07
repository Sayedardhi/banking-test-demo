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

import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_DUPLICATE_TRANSACTION;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_INVALID_AMOUNT;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_INVALID_NUMBER;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_NOT_AUTHENTICATED;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_WHEN_AUTHORIZATION_HEADER_NULL;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.ALICE;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.BOB;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.LOCAL_ROUTING;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.deposit;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.disabledMeterRegistry;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.newRsaKeyPair;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.payment;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.token;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.transaction;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Controller decisions with real JWT verification and the real validator. Only the ledger repository and the
 * balancereader HTTP client are mocked, at the service boundary.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LedgerWriterControllerRulesTest {

    private static final String BALANCES = "http://balancereader:8080/balances";
    private static final KeyPair KEYS = newRsaKeyPair();

    @Mock
    private TransactionRepository repository;
    @Mock
    private RestTemplate balanceClient;

    private LedgerWriterController controller;
    private final String aliceToken = token(KEYS, ALICE);

    @BeforeEach
    void setUp() {
        controller = new LedgerWriterController(
                JWT.require(Algorithm.RSA256((RSAPublicKey) KEYS.getPublic(), null)).build(),
                disabledMeterRegistry(), repository, new TransactionValidator(), LOCAL_ROUTING, BALANCES, "test");
        controller.restTemplate = balanceClient;
    }

    private void balanceOf(String account, int cents) {
        when(balanceClient.exchange(eq(BALANCES + "/" + account), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(Integer.class))).thenReturn(ResponseEntity.ok(cents));
    }

    private ResponseEntity<?> submit(String authorization, Transaction t) {
        return controller.addTransaction(authorization, t);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private void assertRejected(ResponseEntity<?> response, HttpStatus status, String message) {
        assertEquals(status, response.getStatusCode());
        assertEquals(message, response.getBody());
        verify(repository, never()).save(any());
    }

    @Nested
    @DisplayName("Authentication")
    class Authentication {

        @Test
        @DisplayName("No Authorization header: 400, nothing is looked up or written")
        void missingHeader() {
            assertRejected(submit(null, transaction(payment(ALICE, BOB, 100, uuid()))),
                    HttpStatus.BAD_REQUEST, EXCEPTION_MESSAGE_WHEN_AUTHORIZATION_HEADER_NULL);
            verifyNoInteractions(balanceClient);
        }

        @Test
        @DisplayName("Token signed by another key: 401, nothing written")
        void forgedSignature() {
            assertRejected(submit("Bearer " + token(newRsaKeyPair(), ALICE), transaction(payment(ALICE, BOB, 100,
                    uuid()))), HttpStatus.UNAUTHORIZED, LedgerWriterController.UNAUTHORIZED_CODE);
            verifyNoInteractions(balanceClient);
        }

        @Test
        @DisplayName("Expired token: 401, nothing written")
        void expiredToken() {
            assertRejected(submit("Bearer " + token(KEYS, ALICE, Instant.now().minusSeconds(1)),
                    transaction(payment(ALICE, BOB, 100, uuid()))),
                    HttpStatus.UNAUTHORIZED, LedgerWriterController.UNAUTHORIZED_CODE);
        }

        @Test
        @DisplayName("Unsigned token (alg=none) carrying the right account: 401, nothing written")
        void unsignedToken() {
            String unsigned = JWT.create().withClaim("acct", ALICE).sign(Algorithm.none());
            assertRejected(submit("Bearer " + unsigned, transaction(payment(ALICE, BOB, 100, uuid()))),
                    HttpStatus.UNAUTHORIZED, LedgerWriterController.UNAUTHORIZED_CODE);
        }

        @Test
        @DisplayName("'Bearer ' with no token: 401 like any invalid credential, not an unhandled crash")
        void emptyBearerToken() {
            ResponseEntity<?> response = Assertions.assertDoesNotThrow(
                    () -> submit("Bearer ", transaction(payment(ALICE, BOB, 100, uuid()))),
                    "an empty bearer credential must be answered with an HTTP status");
            assertRejected(response, HttpStatus.UNAUTHORIZED, LedgerWriterController.UNAUTHORIZED_CODE);
            verifyNoInteractions(balanceClient, repository);
        }

        @Test
        @DisplayName("Token signed with HS256 using the public key as secret (key confusion): 401")
        void algorithmConfusion() {
            String confused = JWT.create().withClaim("acct", ALICE)
                    .sign(Algorithm.HMAC256(KEYS.getPublic().getEncoded()));
            assertRejected(submit("Bearer " + confused, transaction(payment(ALICE, BOB, 100, uuid()))),
                    HttpStatus.UNAUTHORIZED, LedgerWriterController.UNAUTHORIZED_CODE);
        }

        @Test
        @DisplayName("Valid token for Bob cannot move money out of Alice's account: 400, no balance lookup, nothing written")
        void otherCustomersAccount() {
            assertRejected(submit("Bearer " + token(KEYS, BOB), transaction(payment(ALICE, BOB, 100, uuid()))),
                    HttpStatus.BAD_REQUEST, EXCEPTION_MESSAGE_NOT_AUTHENTICATED);
            verifyNoInteractions(balanceClient);
        }
    }

    @Nested
    @DisplayName("Validation and overdraft")
    class Validation {

        @Test
        @DisplayName("Malformed recipient: 400 before any balance lookup")
        void malformedRecipient() {
            assertRejected(submit("Bearer " + aliceToken, transaction(payment(ALICE, "103362343", 100, uuid()))),
                    HttpStatus.BAD_REQUEST, EXCEPTION_MESSAGE_INVALID_NUMBER);
            verifyNoInteractions(balanceClient);
        }

        @Test
        @DisplayName("Negative deposit: 400, nothing written")
        void negativeDeposit() {
            assertRejected(submit("Bearer " + aliceToken, transaction(deposit(ALICE, -100, uuid()))),
                    HttpStatus.BAD_REQUEST, EXCEPTION_MESSAGE_INVALID_AMOUNT);
        }

        @Test
        @DisplayName("Payment one cent above the balance: 400 insufficient balance, nothing written")
        void overdraftByOneCent() {
            balanceOf(ALICE, 5000);
            assertRejected(submit("Bearer " + aliceToken, transaction(payment(ALICE, BOB, 5001, uuid()))),
                    HttpStatus.BAD_REQUEST, EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE);
        }

        @Test
        @DisplayName("Payment from an empty account: 400 insufficient balance")
        void overdraftFromZero() {
            balanceOf(ALICE, 0);
            assertRejected(submit("Bearer " + aliceToken, transaction(payment(ALICE, BOB, 1, uuid()))),
                    HttpStatus.BAD_REQUEST, EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE);
        }

        @Test
        @DisplayName("Payment of the exact balance: 201 and exactly that transaction is written")
        void exactBalance() {
            balanceOf(ALICE, 5000);
            Transaction t = transaction(payment(ALICE, BOB, 5000, uuid()));
            ResponseEntity<?> response = submit("Bearer " + aliceToken, t);
            assertEquals(HttpStatus.CREATED, response.getStatusCode());
            ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
            verify(repository).save(saved.capture());
            assertSame(t, saved.getValue());
        }

        @Test
        @DisplayName("Balance is fetched for the sender's account with the caller's bearer token")
        void balanceLookupUsesSenderAndToken() {
            balanceOf(ALICE, 5000);
            submit("Bearer " + aliceToken, transaction(payment(ALICE, BOB, 100, uuid())));
            @SuppressWarnings("unchecked")
            ArgumentCaptor<HttpEntity<?>> request = ArgumentCaptor.forClass(HttpEntity.class);
            verify(balanceClient).exchange(eq(BALANCES + "/" + ALICE), eq(HttpMethod.GET), request.capture(),
                    eq(Integer.class));
            assertEquals("Bearer " + aliceToken, request.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        }

        @Test
        @DisplayName("Deposit from an external bank is written without a balance lookup")
        void externalDepositSkipsBalance() {
            ResponseEntity<?> response = submit("Bearer " + aliceToken, transaction(deposit(ALICE, 12345, uuid())));
            assertEquals(HttpStatus.CREATED, response.getStatusCode());
            verify(repository).save(any());
            verifyNoInteractions(balanceClient);
        }
    }

    @Nested
    @DisplayName("Idempotency")
    class Idempotency {

        @Test
        @DisplayName("Replaying a request UUID: 400 duplicate, written once, balance checked once")
        void replayIsRejected() {
            balanceOf(ALICE, 5000);
            String id = uuid();
            assertEquals(HttpStatus.CREATED, submit("Bearer " + aliceToken,
                    transaction(payment(ALICE, BOB, 100, id))).getStatusCode());
            ResponseEntity<?> replay = submit("Bearer " + aliceToken, transaction(payment(ALICE, BOB, 100, id)));
            assertEquals(HttpStatus.BAD_REQUEST, replay.getStatusCode());
            assertEquals(EXCEPTION_MESSAGE_DUPLICATE_TRANSACTION, replay.getBody());
            verify(repository, times(1)).save(any());
            verify(balanceClient, times(1)).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class),
                    eq(Integer.class));
        }

        @Test
        @DisplayName("Identical details with a new UUID are a new payment and are written again")
        void sameDetailsNewUuid() {
            balanceOf(ALICE, 5000);
            submit("Bearer " + aliceToken, transaction(payment(ALICE, BOB, 100, uuid())));
            submit("Bearer " + aliceToken, transaction(payment(ALICE, BOB, 100, uuid())));
            verify(repository, times(2)).save(any());
        }

        @Test
        @DisplayName("A payment rejected for insufficient funds does not burn its UUID; the retry succeeds")
        void rejectedUuidCanBeRetried() {
            String id = uuid();
            balanceOf(ALICE, 50);
            assertEquals(HttpStatus.BAD_REQUEST, submit("Bearer " + aliceToken,
                    transaction(payment(ALICE, BOB, 100, id))).getStatusCode());
            balanceOf(ALICE, 500);
            assertEquals(HttpStatus.CREATED, submit("Bearer " + aliceToken,
                    transaction(payment(ALICE, BOB, 100, id))).getStatusCode());
            verify(repository, times(1)).save(any());
        }

        @Test
        @DisplayName("A failed ledger write does not burn its UUID; the retry is written")
        void failedWriteCanBeRetried() {
            String id = uuid();
            when(repository.save(any())).thenThrow(new CannotCreateTransactionException("ledger-db down"))
                    .thenAnswer(call -> call.getArgument(0));
            ResponseEntity<?> first = submit("Bearer " + aliceToken, transaction(deposit(ALICE, 100, id)));
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, first.getStatusCode());
            assertEquals(HttpStatus.CREATED, submit("Bearer " + aliceToken,
                    transaction(deposit(ALICE, 100, id))).getStatusCode());
            verify(repository, times(2)).save(any());
        }
    }

    @Nested
    @DisplayName("Dependency failures")
    class DependencyFailures {

        @Test
        @DisplayName("balancereader unreachable: 500, nothing written")
        void balanceReaderUnreachable() {
            when(balanceClient.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(Integer.class)))
                    .thenThrow(new ResourceAccessException("connection refused"));
            ResponseEntity<?> response = submit("Bearer " + aliceToken, transaction(payment(ALICE, BOB, 100, uuid())));
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("balancereader returns 503: 500, nothing written")
        void balanceReaderServerError() {
            when(balanceClient.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(Integer.class)))
                    .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
            ResponseEntity<?> response = submit("Bearer " + aliceToken, transaction(payment(ALICE, BOB, 100, uuid())));
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
            verify(repository, never()).save(any());
        }
    }
}
