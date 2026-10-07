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

import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_DUPLICATE_TRANSACTION;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_WHEN_AUTHORIZATION_HEADER_NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;
import static anthos.samples.bankofanthos.ledgerwriter.ExceptionMessages.EXCEPTION_MESSAGE_NOT_AUTHENTICATED;

import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.lang.Nullable;
import io.micrometer.stackdriver.StackdriverConfig;
import io.micrometer.stackdriver.StackdriverMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

class LedgerWriterControllerTest {

    private LedgerWriterController ledgerWriterController;

    @Mock
    private TransactionValidator transactionValidator;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private JWTVerifier verifier;
    @Mock
    private Transaction transaction;
    @Mock
    private DecodedJWT jwt;
    @Mock
    private Claim claim;
    @Mock
    private Clock clock;
    @Mock
    private RestTemplate restTemplate;

    private static final String VERSION = "v0.1.0";
    private static final String LOCAL_ROUTING_NUM = "123456789";
    private static final String NON_LOCAL_ROUTING_NUM = "987654321";
    private static final String BALANCES_API_ADDR = "balancereader:8080";
    private static final String AUTHED_ACCOUNT_NUM = "1234567890";
    private static final String BEARER_TOKEN = "Bearer abc";
    private static final String TOKEN = "abc";
    private static final String EXCEPTION_MESSAGE = "Invalid variable";
    private static final int SENDER_BALANCE = 40;
    private static final int LARGER_THAN_SENDER_BALANCE = 1000;
    private static final int SMALLER_THAN_SENDER_BALANCE = 10;

    @BeforeEach
    void setUp() {
        initMocks(this);
        StackdriverMeterRegistry meterRegistry = new StackdriverMeterRegistry(new StackdriverConfig() {
              @Override
              public boolean enabled() {
                return false;
              }

              @Override
              public String projectId() {
                return "test";
              }

              @Override
              @Nullable
              public String get(String key) {
                return null;
              }
          }, clock);

        ledgerWriterController = new LedgerWriterController(verifier,
                meterRegistry,
                transactionRepository, transactionValidator,
                LOCAL_ROUTING_NUM, BALANCES_API_ADDR, VERSION);

        when(verifier.verify(TOKEN)).thenReturn(jwt);
        when(jwt.getClaim(
                LedgerWriterController.JWT_ACCOUNT_KEY)).thenReturn(claim);
    }

    @Test
    @DisplayName("Given version number in the environment, " +
            "return a ResponseEntity with the version number")
    void version() {
        // When
        final ResponseEntity actualResult = ledgerWriterController.version();

        // Then
        assertNotNull(actualResult);
        assertEquals(VERSION, actualResult.getBody());
        assertEquals(HttpStatus.OK, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the server is serving requests, return HTTP Status 200")
    void readiness() {
        // When
        final ResponseEntity actualResult = ledgerWriterController.readiness();

        // Then
        assertNotNull(actualResult);
        assertEquals(ledgerWriterController.READINESS_CODE,
                actualResult.getBody());
        assertEquals(HttpStatus.OK, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the transaction is external, return HTTP Status 201")
    void addTransactionSuccessWhenDiffThanLocalRoutingNum(TestInfo testInfo) {
        // Given
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());

        // When
        final ResponseEntity actualResult =
                ledgerWriterController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(ledgerWriterController.READINESS_CODE,
                actualResult.getBody());
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the transaction is internal and the transaction amount == sender balance, " +
            "return HTTP Status 201")
    void addTransactionSuccessWhenAmountEqualToBalance(TestInfo testInfo) {
        // Given
        LedgerWriterController spyLedgerWriterController =
                spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(SENDER_BALANCE);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doReturn(SENDER_BALANCE).when(
                spyLedgerWriterController).getAvailableBalance(
                TOKEN, AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult =
                spyLedgerWriterController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(ledgerWriterController.READINESS_CODE,
                actualResult.getBody());
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the transaction is internal and the transaction amount < sender balance, " +
            "return HTTP Status 201")
    void addTransactionSuccessWhenAmountSmallerThanBalance(TestInfo testInfo) {
        // Given
        LedgerWriterController spyLedgerWriterController =
                spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(SMALLER_THAN_SENDER_BALANCE);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doReturn(SENDER_BALANCE).when(
                spyLedgerWriterController).getAvailableBalance(
                TOKEN, AUTHED_ACCOUNT_NUM);

        // When
        final ResponseEntity actualResult =
                spyLedgerWriterController.addTransaction(
                        BEARER_TOKEN, transaction);

        // Then
        assertNotNull(actualResult);
        assertEquals(ledgerWriterController.READINESS_CODE,
                actualResult.getBody());
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
    }

    private LedgerWriterController internalTransferWithBalance(
            String uuid, int amount, int balance) {
        LedgerWriterController spyController = spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getAmount()).thenReturn(amount);
        when(transaction.getRequestUuid()).thenReturn(uuid);
        doReturn(balance).when(spyController)
                .getAvailableBalance(TOKEN, AUTHED_ACCOUNT_NUM);
        return spyController;
    }

    @Test
    @DisplayName("Given the transaction is internal and the amount > sender balance, "
            + "return 400 insufficient balance and do not persist")
    void addTransactionFailsWhenAmountLargerThanBalance(TestInfo testInfo) {
        LedgerWriterController controller = internalTransferWithBalance(
                testInfo.getDisplayName(), LARGER_THAN_SENDER_BALANCE,
                SENDER_BALANCE);

        ResponseEntity<?> result = controller.addTransaction(
                BEARER_TOKEN, transaction);

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        assertEquals(EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE, result.getBody());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Given the transaction is internal, the balance is looked up "
            + "for the sender account with the caller's token")
    void addTransactionChecksSenderBalanceWithCallerToken(TestInfo testInfo) {
        LedgerWriterController controller = internalTransferWithBalance(
                testInfo.getDisplayName(), SMALLER_THAN_SENDER_BALANCE,
                SENDER_BALANCE);

        controller.addTransaction(BEARER_TOKEN, transaction);

        verify(controller).getAvailableBalance(TOKEN, AUTHED_ACCOUNT_NUM);
        verify(transactionValidator).validateTransaction(
                eq(LOCAL_ROUTING_NUM), any(), eq(transaction));
        verify(transactionRepository).save(transaction);
    }

    @Test
    @DisplayName("Given the transaction is external, the sender balance "
            + "is never looked up")
    void addTransactionSkipsBalanceCheckForExternalSender(TestInfo testInfo) {
        LedgerWriterController spyController = spy(ledgerWriterController);
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());

        ResponseEntity<?> result = spyController.addTransaction(
                BEARER_TOKEN, transaction);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        verify(spyController, never()).getAvailableBalance(anyString(),
                anyString());
    }

    @Test
    @DisplayName("Given the authenticated account claim, it is passed "
            + "to the validator as the authenticated sender")
    void addTransactionPassesJwtAccountClaimToValidator(TestInfo testInfo) {
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());

        ledgerWriterController.addTransaction(BEARER_TOKEN, transaction);

        verify(transactionValidator).validateTransaction(
                LOCAL_ROUTING_NUM, AUTHED_ACCOUNT_NUM, transaction);
    }

    @Test
    @DisplayName("Given a null Authorization header, return 400 "
            + "without verifying or persisting")
    void addTransactionFailsWhenBearerTokenNull() {
        ResponseEntity<?> result = ledgerWriterController.addTransaction(
                null, transaction);

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        assertEquals(EXCEPTION_MESSAGE_WHEN_AUTHORIZATION_HEADER_NULL,
                result.getBody());
        verify(verifier, never()).verify(anyString());
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("Given a token without the Bearer prefix, "
            + "the raw header value is verified")
    void addTransactionVerifiesRawTokenWithoutBearerPrefix(TestInfo testInfo) {
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());

        ResponseEntity<?> result = ledgerWriterController.addTransaction(
                TOKEN, transaction);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        verify(verifier).verify(TOKEN);
    }

    @Test
    @DisplayName("Given JWT verification fails, return 401 and do not "
            + "validate, check balance or persist")
    void addTransactionFailsWhenJwtVerificationFails() {
        when(verifier.verify(TOKEN)).thenThrow(
                new JWTVerificationException(EXCEPTION_MESSAGE));
        LedgerWriterController spyController = spy(ledgerWriterController);

        ResponseEntity<?> result = spyController.addTransaction(
                BEARER_TOKEN, transaction);

        assertEquals(HttpStatus.UNAUTHORIZED, result.getStatusCode());
        assertEquals(LedgerWriterController.UNAUTHORIZED_CODE,
                result.getBody());
        verifyNoInteractions(transactionValidator, transactionRepository);
        verify(spyController, never()).getAvailableBalance(anyString(),
                anyString());
    }

    @Test
    @DisplayName("Given the validator rejects the transaction, return 400 "
            + "with its message and do not check balance or persist")
    void addTransactionFailsWhenValidatorRejects(TestInfo testInfo) {
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doThrow(new IllegalArgumentException(
                EXCEPTION_MESSAGE_NOT_AUTHENTICATED))
                .when(transactionValidator)
                .validateTransaction(any(), any(), any());
        LedgerWriterController spyController = spy(ledgerWriterController);

        ResponseEntity<?> result = spyController.addTransaction(
                BEARER_TOKEN, transaction);

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        assertEquals(EXCEPTION_MESSAGE_NOT_AUTHENTICATED, result.getBody());
        verifyNoInteractions(transactionRepository);
        verify(spyController, never()).getAvailableBalance(anyString(),
                anyString());
    }

    @Test
    @DisplayName("Given a request uuid that already succeeded, return 400 "
            + "duplicate and persist only once")
    void addTransactionRejectsDuplicateUuid(TestInfo testInfo) {
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());

        ResponseEntity<?> first = ledgerWriterController.addTransaction(
                BEARER_TOKEN, transaction);
        ResponseEntity<?> second = ledgerWriterController.addTransaction(
                BEARER_TOKEN, transaction);

        assertEquals(HttpStatus.CREATED, first.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, second.getStatusCode());
        assertEquals(EXCEPTION_MESSAGE_DUPLICATE_TRANSACTION, second.getBody());
        verify(transactionRepository, times(1)).save(transaction);
    }

    @Test
    @DisplayName("Given a request rejected for insufficient balance, "
            + "a retry with the same uuid is not treated as a duplicate")
    void addTransactionDoesNotCacheUuidOfRejectedRequest(TestInfo testInfo) {
        LedgerWriterController controller = internalTransferWithBalance(
                testInfo.getDisplayName(), LARGER_THAN_SENDER_BALANCE,
                SENDER_BALANCE);
        controller.addTransaction(BEARER_TOKEN, transaction);

        doReturn(LARGER_THAN_SENDER_BALANCE).when(controller)
                .getAvailableBalance(TOKEN, AUTHED_ACCOUNT_NUM);
        ResponseEntity<?> retry = controller.addTransaction(
                BEARER_TOKEN, transaction);

        assertEquals(HttpStatus.CREATED, retry.getStatusCode());
        verify(transactionRepository, times(1)).save(transaction);
    }

    @Test
    @DisplayName("Given balancereader is unreachable, return 500 "
            + "and do not persist")
    void addTransactionFailsWhenBalanceReaderUnreachable(TestInfo testInfo) {
        LedgerWriterController controller = internalTransferWithBalance(
                testInfo.getDisplayName(), SMALLER_THAN_SENDER_BALANCE,
                SENDER_BALANCE);
        doThrow(new ResourceAccessException(EXCEPTION_MESSAGE))
                .when(controller).getAvailableBalance(TOKEN, AUTHED_ACCOUNT_NUM);

        ResponseEntity<?> result = controller.addTransaction(
                BEARER_TOKEN, transaction);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatusCode());
        assertEquals(EXCEPTION_MESSAGE, result.getBody());
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("Given balancereader returns a 5xx, return 500 "
            + "and do not persist")
    void addTransactionFailsWhenBalanceReaderErrors(TestInfo testInfo) {
        LedgerWriterController controller = internalTransferWithBalance(
                testInfo.getDisplayName(), SMALLER_THAN_SENDER_BALANCE,
                SENDER_BALANCE);
        doThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))
                .when(controller).getAvailableBalance(TOKEN, AUTHED_ACCOUNT_NUM);

        ResponseEntity<?> result = controller.addTransaction(
                BEARER_TOKEN, transaction);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatusCode());
        verifyNoInteractions(transactionRepository);
    }

    @Test
    @DisplayName("Given the database cannot start a transaction, return 500 "
            + "and allow a retry with the same uuid")
    void addTransactionFailsWhenDatabaseUnavailable(TestInfo testInfo) {
        when(transaction.getFromRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getRequestUuid()).thenReturn(testInfo.getDisplayName());
        doThrow(new CannotCreateTransactionException(EXCEPTION_MESSAGE))
                .doReturn(transaction)
                .when(transactionRepository).save(transaction);

        ResponseEntity<?> failed = ledgerWriterController.addTransaction(
                BEARER_TOKEN, transaction);
        ResponseEntity<?> retry = ledgerWriterController.addTransaction(
                BEARER_TOKEN, transaction);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, failed.getStatusCode());
        assertEquals(HttpStatus.CREATED, retry.getStatusCode());
        verify(transactionRepository, times(2)).save(transaction);
    }

    @Test
    @DisplayName("getAvailableBalance calls balancereader for the account "
            + "with a Bearer token and returns the balance")
    void getAvailableBalanceCallsBalanceReader() {
        ledgerWriterController.restTemplate = restTemplate;
        when(restTemplate.exchange(eq(BALANCES_API_ADDR + "/" + AUTHED_ACCOUNT_NUM), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(Integer.class)))
                .thenReturn(ResponseEntity.ok(SENDER_BALANCE));

        int balance = ledgerWriterController.getAvailableBalance(
                TOKEN, AUTHED_ACCOUNT_NUM);

        assertEquals(SENDER_BALANCE, balance);
        ArgumentCaptor<HttpEntity> entity =
                ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.GET),
                entity.capture(), eq(Integer.class));
        assertEquals("Bearer " + TOKEN,
                entity.getValue().getHeaders().getFirst("Authorization"));
    }

    @Test
    @DisplayName("Given balancereader returns 5xx, getAvailableBalance "
            + "propagates HttpServerErrorException")
    void getAvailableBalancePropagatesServerError() {
        ledgerWriterController.restTemplate = restTemplate;
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(Integer.class)))
                .thenThrow(new HttpServerErrorException(
                        HttpStatus.INTERNAL_SERVER_ERROR));

        HttpServerErrorException e = assertThrows(
                HttpServerErrorException.class,
                () -> ledgerWriterController.getAvailableBalance(TOKEN,
                        AUTHED_ACCOUNT_NUM));
        assertTrue(e.getStatusCode().is5xxServerError());
    }

}
