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

import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_DUPLICATE_TRANSACTION;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_INVALID_AMOUNT;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_NOT_AUTHENTICATED;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_WHEN_AUTHORIZATION_HEADER_NULL;
import static anthos.samples.bankofanthos.ledgerwriter.TransactionValidatorRulesTest.AUTHED_ACCT;
import static anthos.samples.bankofanthos.ledgerwriter.TransactionValidatorRulesTest.EXTERNAL_ACCT;
import static anthos.samples.bankofanthos.ledgerwriter.TransactionValidatorRulesTest.EXTERNAL_ROUTING;
import static anthos.samples.bankofanthos.ledgerwriter.TransactionValidatorRulesTest.LOCAL_ROUTING;
import static anthos.samples.bankofanthos.ledgerwriter.TransactionValidatorRulesTest.OTHER_ACCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Clock;
import io.micrometer.stackdriver.StackdriverConfig;
import io.micrometer.stackdriver.StackdriverMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.auth0.jwt.exceptions.TokenExpiredException;
import java.time.Instant;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Transaction submission rules. The real TransactionValidator is used; the JWT verifier,
 * balance service (RestTemplate) and repository are mocked at their boundaries.
 */
class LedgerWriterControllerRulesTest {

    private static final String BALANCES_URI = "http://balancereader:8080/balances";
    private static final String GOOD_TOKEN = "good-token";
    private static final String SENDER_BALANCE_URI = BALANCES_URI + "/" + AUTHED_ACCT;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TransactionRepository repository;
    private RestTemplate restTemplate;
    private Claim accountClaim;
    private LedgerWriterController controller;

    @BeforeEach
    void setUp() {
        JWTVerifier verifier = mock(JWTVerifier.class);
        DecodedJWT jwt = mock(DecodedJWT.class);
        accountClaim = mock(Claim.class);
        repository = mock(TransactionRepository.class);
        restTemplate = mock(RestTemplate.class);
        when(verifier.verify(GOOD_TOKEN)).thenReturn(jwt);
        when(verifier.verify("forged-token")).thenThrow(new JWTVerificationException("bad signature"));
        when(jwt.getClaim(LedgerWriterController.JWT_ACCOUNT_KEY)).thenReturn(accountClaim);
        when(accountClaim.asString()).thenReturn(AUTHED_ACCT);

        StackdriverMeterRegistry registry = new StackdriverMeterRegistry(new StackdriverConfig() {
            @Override public boolean enabled() { return false; }
            @Override public String projectId() { return "test"; }
            @Override public String get(String key) { return null; }
        }, Clock.SYSTEM);
        controller = new LedgerWriterController(verifier, registry, repository,
                new TransactionValidator(), LOCAL_ROUTING, BALANCES_URI, "test");
        controller.restTemplate = restTemplate;
    }

    private static Transaction tx(String fromAcct, String fromRoute, String toAcct, int amount, String uuid) {
        return MAPPER.convertValue(Map.of("fromAccountNum", fromAcct, "fromRoutingNum", fromRoute,
                "toAccountNum", toAcct, "toRoutingNum", LOCAL_ROUTING, "amount", amount, "uuid", uuid),
                Transaction.class);
    }

    private static Transaction payment(int amount, String uuid) {
        return tx(AUTHED_ACCT, LOCAL_ROUTING, OTHER_ACCT, amount, uuid);
    }

    private void senderBalanceIs(int cents) {
        when(restTemplate.exchange(eq(SENDER_BALANCE_URI), eq(HttpMethod.GET), any(HttpEntity.class), eq(Integer.class)))
                .thenReturn(new ResponseEntity<>(cents, HttpStatus.OK));
    }

    private ResponseEntity<?> submit(Transaction transaction) {
        return controller.addTransaction("Bearer " + GOOD_TOKEN, transaction);
    }

    @Test
    @DisplayName("Payment within balance is saved exactly once and the caller's token is forwarded to balancereader")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void paymentWithinBalanceIsSaved() {
        senderBalanceIs(5000);
        Transaction payment = payment(2500, "p-1");

        ResponseEntity<?> response = submit(payment);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(repository, times(1)).save(payment);
        ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(SENDER_BALANCE_URI), eq(HttpMethod.GET), request.capture(), eq(Integer.class));
        assertEquals("Bearer " + GOOD_TOKEN, request.getValue().getHeaders().getFirst("Authorization"));
    }

    @Test
    @DisplayName("Payment of one cent more than the balance is rejected and nothing is written")
    void overdraftRejected() {
        senderBalanceIs(5000);

        ResponseEntity<?> response = submit(payment(5001, "p-2"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE, response.getBody());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("A rejected payment does not consume its UUID, so a corrected retry succeeds")
    void rejectedPaymentCanBeRetried() {
        senderBalanceIs(100);
        assertEquals(HttpStatus.BAD_REQUEST, submit(payment(500, "p-3")).getStatusCode());

        senderBalanceIs(1000);
        assertEquals(HttpStatus.CREATED, submit(payment(500, "p-3")).getStatusCode());
        verify(repository, times(1)).save(any());
    }

    @Test
    @DisplayName("Replaying an accepted UUID is rejected as a duplicate and written only once")
    void duplicateUuidWrittenOnce() {
        senderBalanceIs(5000);
        assertEquals(HttpStatus.CREATED, submit(payment(100, "p-4")).getStatusCode());

        ResponseEntity<?> replay = submit(payment(100, "p-4"));

        assertEquals(HttpStatus.BAD_REQUEST, replay.getStatusCode());
        assertEquals(EXCEPTION_MESSAGE_DUPLICATE_TRANSACTION, replay.getBody());
        verify(repository, times(1)).save(any());
    }

    @Test
    @DisplayName("A forged or expired token returns 401 without touching balances or the ledger")
    void invalidTokenRejected() {
        ResponseEntity<?> response = controller.addTransaction("Bearer forged-token", payment(100, "p-5"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verifyNoInteractions(restTemplate, repository);
    }

    @Test
    @DisplayName("A missing Authorization header is rejected")
    void missingAuthorizationRejected() {
        ResponseEntity<?> response = controller.addTransaction(null, payment(100, "p-6"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(EXCEPTION_MESSAGE_WHEN_AUTHORIZATION_HEADER_NULL, response.getBody());
        verifyNoInteractions(restTemplate, repository);
    }

    @Test
    @DisplayName("A valid token for one customer cannot debit another customer's account")
    void tokenForDifferentAccountRejected() {
        when(accountClaim.asString()).thenReturn(OTHER_ACCT);

        ResponseEntity<?> response = submit(payment(100, "p-7"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(EXCEPTION_MESSAGE_NOT_AUTHENTICATED, response.getBody());
        verifyNoInteractions(restTemplate, repository);
    }

    @Test
    @DisplayName("External deposits are saved without a sender balance check")
    void externalDepositSkipsBalanceCheck() {
        Transaction deposit = tx(EXTERNAL_ACCT, EXTERNAL_ROUTING, AUTHED_ACCT, 100000, "d-1");

        assertEquals(HttpStatus.CREATED, submit(deposit).getStatusCode());
        verify(repository).save(deposit);
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("Invalid amounts are rejected before the balance lookup or ledger write")
    void invalidAmountRejectedEarly() {
        ResponseEntity<?> response = submit(payment(0, "p-8"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(EXCEPTION_MESSAGE_INVALID_AMOUNT, response.getBody());
        verifyNoInteractions(restTemplate, repository);
    }

    @Test
    @DisplayName("Balance service unreachable returns 500 and nothing is written")
    void balanceServiceUnreachable() {
        when(restTemplate.exchange(eq(SENDER_BALANCE_URI), eq(HttpMethod.GET), any(HttpEntity.class), eq(Integer.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, submit(payment(100, "p-9")).getStatusCode());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Balance service error returns 500 and nothing is written")
    void balanceServiceError() {
        when(restTemplate.exchange(eq(SENDER_BALANCE_URI), eq(HttpMethod.GET), any(HttpEntity.class), eq(Integer.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, submit(payment(100, "p-10")).getStatusCode());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Ledger database outage returns 500 and the UUID stays usable for a retry")
    void databaseOutageDoesNotConsumeUuid() {
        senderBalanceIs(5000);
        when(repository.save(any()))
                .thenThrow(new CannotCreateTransactionException("db down"))
                .thenAnswer(inv -> inv.getArgument(0));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, submit(payment(100, "p-11")).getStatusCode());
        assertEquals(HttpStatus.CREATED, submit(payment(100, "p-11")).getStatusCode());
        verify(repository, times(2)).save(any());
    }

    @Test
    @DisplayName("Paying exactly the available balance is allowed (boundary)")
    void paymentOfEntireBalanceAccepted() {
        senderBalanceIs(5000);

        assertEquals(HttpStatus.CREATED, submit(payment(5000, "p-12")).getStatusCode());
        verify(repository).save(any());
    }

    /** Submits and returns the HTTP status, or null if the controller let an exception escape to Spring. */
    private HttpStatus statusOrNullIfThrown(Transaction transaction) {
        try {
            return HttpStatus.valueOf(submit(transaction).getStatusCode().value());
        } catch (RuntimeException escapedToSpringErrorHandler) {
            return null;
        }
    }

    @Test
    @DisplayName("An expired token returns 401 without touching balances or the ledger")
    void expiredTokenRejected() {
        JWTVerifier expiring = mock(JWTVerifier.class);
        when(expiring.verify(GOOD_TOKEN)).thenThrow(new TokenExpiredException("expired", Instant.EPOCH));
        StackdriverMeterRegistry registry = new StackdriverMeterRegistry(new StackdriverConfig() {
            @Override public boolean enabled() { return false; }
            @Override public String projectId() { return "test"; }
            @Override public String get(String key) { return null; }
        }, Clock.SYSTEM);
        LedgerWriterController expiredController = new LedgerWriterController(expiring, registry, repository,
                new TransactionValidator(), LOCAL_ROUTING, BALANCES_URI, "test");
        expiredController.restTemplate = restTemplate;

        ResponseEntity<?> response = expiredController.addTransaction("Bearer " + GOOD_TOKEN, payment(100, "e-1"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(LedgerWriterController.UNAUTHORIZED_CODE, response.getBody());
        verifyNoInteractions(restTemplate, repository);
    }

    @Test
    @DisplayName("A token without an account claim cannot debit a local account")
    void tokenWithoutAccountClaimRejected() {
        when(accountClaim.asString()).thenReturn(null);

        ResponseEntity<?> response = submit(payment(100, "a-1"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(EXCEPTION_MESSAGE_NOT_AUTHENTICATED, response.getBody());
        verifyNoInteractions(restTemplate, repository);
    }

    @Test
    @DisplayName("Two identical payments with different UUIDs are both legitimate and both saved")
    void identicalPaymentsWithDistinctUuidsBothSaved() {
        senderBalanceIs(5000);

        assertEquals(HttpStatus.CREATED, submit(payment(100, "i-1")).getStatusCode());
        assertEquals(HttpStatus.CREATED, submit(payment(100, "i-2")).getStatusCode());
        verify(repository, times(2)).save(any());
    }

    @Test
    @DisplayName("A replayed UUID is rejected before the balance lookup, so replays cannot probe balances")
    void replayRejectedBeforeBalanceLookup() {
        senderBalanceIs(5000);
        assertEquals(HttpStatus.CREATED, submit(payment(100, "r-1")).getStatusCode());

        assertEquals(HttpStatus.BAD_REQUEST, submit(payment(999_999, "r-1")).getStatusCode());
        verify(restTemplate, times(1)).exchange(eq(SENDER_BALANCE_URI), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(Integer.class));
        verify(repository, times(1)).save(any());
    }

    @Test
    @DisplayName("Two concurrent submissions with the same UUID write the ledger only once")
    void concurrentDuplicateWrittenOnce() throws Exception {
        senderBalanceIs(5000);
        CountDownLatch firstSaveStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstSave = new CountDownLatch(1);
        AtomicInteger saveCalls = new AtomicInteger();
        when(repository.save(any())).thenAnswer(invocation -> {
            if (saveCalls.incrementAndGet() == 1) {
                firstSaveStarted.countDown();
                releaseFirstSave.await(5, TimeUnit.SECONDS);
            }
            return invocation.getArgument(0);
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<?>> first = pool.submit(() -> submit(payment(100, "race-1")));
            assertTrue(firstSaveStarted.await(5, TimeUnit.SECONDS), "first submission never reached the ledger write");
            Future<ResponseEntity<?>> second = pool.submit(() -> submit(payment(100, "race-1")));
            try {
                second.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException blockedUntilFirstCompletes) {
                // Acceptable: an implementation may serialize submissions for the same UUID.
            }
            releaseFirstSave.countDown();
            List<HttpStatus> statuses = new ArrayList<>();
            statuses.add(HttpStatus.valueOf(first.get(5, TimeUnit.SECONDS).getStatusCode().value()));
            statuses.add(HttpStatus.valueOf(second.get(5, TimeUnit.SECONDS).getStatusCode().value()));

            verify(repository, times(1)).save(any());
            assertTrue(statuses.contains(HttpStatus.CREATED) && statuses.contains(HttpStatus.BAD_REQUEST),
                    "expected one accepted and one duplicate, got " + statuses);
        } finally {
            releaseFirstSave.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Balance service rejecting the request (4xx) never results in a ledger write")
    void balanceServiceClientErrorNotPersisted() {
        when(restTemplate.exchange(eq(SENDER_BALANCE_URI), eq(HttpMethod.GET), any(HttpEntity.class), eq(Integer.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        HttpStatus status = statusOrNullIfThrown(payment(100, "b-4xx"));

        assertFalse(status != null && status.is2xxSuccessful(), "4xx from balancereader must not be a success");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Balance service returning an empty body never results in a ledger write")
    void balanceServiceEmptyBodyNotPersisted() {
        when(restTemplate.exchange(eq(SENDER_BALANCE_URI), eq(HttpMethod.GET), any(HttpEntity.class), eq(Integer.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        HttpStatus status = statusOrNullIfThrown(payment(100, "b-null"));

        assertFalse(status != null && status.is2xxSuccessful(), "an unknown balance must not be treated as sufficient");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Balance lookup targets the sender's account, never the recipient's")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void balanceLookupUsesSenderAccount() {
        senderBalanceIs(5000);

        submit(payment(100, "s-1"));

        verify(restTemplate).exchange(eq(SENDER_BALANCE_URI), eq(HttpMethod.GET), any(HttpEntity.class), eq(Integer.class));
        verify(restTemplate, never()).exchange(eq(BALANCES_URI + "/" + OTHER_ACCT), any(HttpMethod.class),
                any(HttpEntity.class), any(Class.class));
    }
}
