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

import static anthos.samples.bankofanthos.transactionhistory.TestFixtures.LOCAL_ROUTING;
import static anthos.samples.bankofanthos.transactionhistory.TestFixtures.localTx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.cache.LoadingCache;
import java.time.Instant;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Authorization and read-path decisions of GET /transactions/{accountId}. Uses the real Guava
 * cache built by {@link TransactionCache}; only the JWT verifier, the ledger reader thread and
 * the database repository are substituted.
 */
class TransactionHistoryControllerAuthorizationTest {

    private static final String OWNER = "1111111111";
    private static final String OTHER = "2222222222";
    private static final int HISTORY_LIMIT = 100;

    private JWTVerifier verifier;
    private TransactionRepository repo;
    private LoadingCache<String, Deque<Transaction>> cache;
    private TransactionHistoryController controller;

    @BeforeEach
    void setUp() {
        verifier = mock(JWTVerifier.class);
        repo = mock(TransactionRepository.class);
        TransactionCache cacheConfig = new TransactionCache();
        ReflectionTestUtils.setField(cacheConfig, "dbRepo", repo);
        cache = cacheConfig.initializeCache(1000, 60, LOCAL_ROUTING, HISTORY_LIMIT);
        controller = new TransactionHistoryController(mock(LedgerReader.class),
            TestFixtures.disabledMeterRegistry(), verifier, "unused.pem", cache,
            LOCAL_ROUTING, "test");
        ReflectionTestUtils.setField(controller, "historyLimit", HISTORY_LIMIT);
    }

    private void tokenFor(String token, String account) {
        DecodedJWT jwt = mock(DecodedJWT.class);
        Claim claim = mock(Claim.class);
        when(claim.asString()).thenReturn(account);
        when(jwt.getClaim("acct")).thenReturn(claim);
        when(verifier.verify(token)).thenReturn(jwt);
    }

    @Test
    @DisplayName("Owner receives their ledger history newest-first, loaded once for the local routing number")
    void ownerReceivesHistoryFromLedger() {
        tokenFor("owner-token", OWNER);
        LinkedList<Transaction> ledger = new LinkedList<>(List.of(
            localTx(3, OWNER, OTHER, 300), localTx(2, OTHER, OWNER, 200), localTx(1, OWNER, OTHER, 100)));
        when(repo.findForAccount(OWNER, LOCAL_ROUTING, PageRequest.of(0, HISTORY_LIMIT))).thenReturn(ledger);

        ResponseEntity<?> response = controller.getTransactions("Bearer owner-token", OWNER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Collection<Transaction>) response.getBody())
            .extracting(Transaction::getTransactionId).containsExactly(3L, 2L, 1L);
        verify(repo, times(1)).findForAccount(OWNER, LOCAL_ROUTING, PageRequest.of(0, HISTORY_LIMIT));
    }

    @Test
    @DisplayName("Repeated reads are served from the cache without re-querying the ledger")
    void repeatedReadsServedFromCache() {
        tokenFor("owner-token", OWNER);
        when(repo.findForAccount(anyString(), anyString(), any())).thenReturn(new LinkedList<>());

        controller.getTransactions("Bearer owner-token", OWNER);
        controller.getTransactions("Bearer owner-token", OWNER);

        verify(repo, times(1)).findForAccount(anyString(), anyString(), any());
        assertThat(cache.stats().hitCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Token without the 'Bearer ' prefix is verified as-is")
    void rawTokenIsVerified() {
        tokenFor("raw-token", OWNER);
        when(repo.findForAccount(anyString(), anyString(), any())).thenReturn(new LinkedList<>());

        ResponseEntity<?> response = controller.getTransactions("raw-token", OWNER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(verifier).verify("raw-token");
    }

    @Test
    @DisplayName("Valid token for another account is rejected with 401 before any ledger read")
    void otherAccountRejectedWithoutLedgerRead() {
        tokenFor("owner-token", OWNER);

        ResponseEntity<?> response = controller.getTransactions("Bearer owner-token", OTHER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo("not authorized");
        verify(repo, never()).findForAccount(anyString(), anyString(), any());
        assertThat(cache.asMap()).doesNotContainKey(OTHER);
    }

    @Test
    @DisplayName("Token without an 'acct' claim is rejected with 401")
    void missingAccountClaimRejected() {
        tokenFor("no-acct-token", null);

        ResponseEntity<?> response = controller.getTransactions("Bearer no-acct-token", OWNER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(repo, never()).findForAccount(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Forged signature is rejected with 401 and nothing is cached")
    void forgedSignatureRejected() {
        when(verifier.verify("forged")).thenThrow(new SignatureVerificationException(null));

        ResponseEntity<?> response = controller.getTransactions("Bearer forged", OWNER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo("not authorized");
        assertThat(cache.asMap()).isEmpty();
        verify(repo, never()).findForAccount(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Expired token is rejected with 401")
    void expiredTokenRejected() {
        when(verifier.verify("expired")).thenThrow(new TokenExpiredException("expired", Instant.EPOCH));

        ResponseEntity<?> response = controller.getTransactions("Bearer expired", OWNER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(repo, never()).findForAccount(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Ledger database failure returns 500 'cache error' and the failure is not cached")
    void ledgerFailureReturns500AndIsRetried() {
        tokenFor("owner-token", OWNER);
        when(repo.findForAccount(anyString(), anyString(), any()))
            .thenThrow(new DataAccessResourceFailureException("ledger-db down"))
            .thenReturn(new LinkedList<>(List.of(localTx(9, OWNER, OTHER, 900))));

        ResponseEntity<?> failed = controller.getTransactions("Bearer owner-token", OWNER);
        ResponseEntity<?> recovered = controller.getTransactions("Bearer owner-token", OWNER);

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(failed.getBody()).isEqualTo("cache error");
        assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Collection<Transaction>) recovered.getBody())
            .extracting(Transaction::getTransactionId).containsExactly(9L);
    }
}
