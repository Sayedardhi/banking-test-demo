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

import static anthos.samples.bankofanthos.ledgerwriter.TransactionValidatorRulesTest.AUTHED_ACCT;
import static anthos.samples.bankofanthos.ledgerwriter.TransactionValidatorRulesTest.EXTERNAL_ACCT;
import static anthos.samples.bankofanthos.ledgerwriter.TransactionValidatorRulesTest.EXTERNAL_ROUTING;
import static anthos.samples.bankofanthos.ledgerwriter.TransactionValidatorRulesTest.LOCAL_ROUTING;
import static anthos.samples.bankofanthos.ledgerwriter.TransactionValidatorRulesTest.OTHER_ACCT;
import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * HTTP -> controller -> JPA -> Postgres, using the repository's real ledger schema in a throwaway
 * container and real RS256 JWT verification. Only balancereader (a separate service, outside this
 * test's scope) is substituted, via MockRestServiceServer on the application's RestTemplate.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerWriterPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("../ledger-db/initdb/0_init_tables.sql")),
                    "/docker-entrypoint-initdb.d/0_init_tables.sql");

    private static final KeyPair KEYS = rsaKeyPair();
    private static final KeyPair ATTACKER_KEYS = rsaKeyPair();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path publicKey = Files.createTempFile("ledgerwriter-it", ".pem");
        publicKey.toFile().deleteOnExit();
        Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(KEYS.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("PUB_KEY_PATH", publicKey::toString);
        registry.add("LOCAL_ROUTING_NUM", () -> LOCAL_ROUTING);
        registry.add("BALANCES_API_ADDR", () -> "balancereader.test:8080");
        registry.add("VERSION", () -> "integration-test");
        registry.add("PORT", () -> "0");
        registry.add("ENABLE_TRACING", () -> "false");
    }

    @Autowired private TestRestTemplate http;
    @Autowired private RestTemplate applicationRestTemplate;
    @Autowired private JdbcTemplate jdbc;
    private MockRestServiceServer balancereader;

    @BeforeEach
    void freshLedger() {
        jdbc.execute("TRUNCATE TRANSACTIONS RESTART IDENTITY");
        balancereader = MockRestServiceServer.bindTo(applicationRestTemplate).ignoreExpectOrder(true).build();
    }

    @AfterEach
    void resetBalancereader() {
        balancereader.reset();
    }

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String token(KeyPair keys, String account, Date expiresAt) {
        return JWT.create().withSubject("it-user").withClaim("acct", account).withExpiresAt(expiresAt)
                .sign(Algorithm.RSA256(null, (RSAPrivateKey) keys.getPrivate()));
    }

    private static String validToken() {
        return token(KEYS, AUTHED_ACCT, new Date(System.currentTimeMillis() + 3_600_000));
    }

    private static Map<String, Object> body(String fromAcct, String fromRoute, String toAcct, Integer amount, String uuid) {
        Map<String, Object> body = new HashMap<>();
        body.put("fromAccountNum", fromAcct);
        body.put("fromRoutingNum", fromRoute);
        body.put("toAccountNum", toAcct);
        body.put("toRoutingNum", LOCAL_ROUTING);
        body.put("amount", amount);
        body.put("uuid", uuid);
        return body;
    }

    private static Map<String, Object> payment(int amount) {
        return body(AUTHED_ACCT, LOCAL_ROUTING, OTHER_ACCT, amount, UUID.randomUUID().toString());
    }

    private ResponseEntity<String> post(Map<String, Object> body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return http.exchange("/transactions", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private void senderBalanceIs(int cents) {
        balancereader.expect(manyTimes(), requestTo(endsWith("/balances/" + AUTHED_ACCT)))
                .andRespond(withSuccess(String.valueOf(cents), MediaType.APPLICATION_JSON));
    }

    private int ledgerRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM TRANSACTIONS", Integer.class);
    }

    @Test
    @DisplayName("Accepted payment is persisted as exactly one row with the submitted values")
    void paymentPersisted() {
        senderBalanceIs(10_000);

        ResponseEntity<String> response = post(payment(2_500), validToken());

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM TRANSACTIONS");
        assertEquals(AUTHED_ACCT, row.get("from_acct"));
        assertEquals(LOCAL_ROUTING, row.get("from_route"));
        assertEquals(OTHER_ACCT, row.get("to_acct"));
        assertEquals(LOCAL_ROUTING, row.get("to_route"));
        assertEquals(2_500, row.get("amount"));
        assertNotNull(row.get("timestamp"));
        assertEquals(1, ledgerRows());
    }

    @Test
    @DisplayName("Overdraft is rejected and nothing reaches the ledger")
    void overdraftNotPersisted() {
        senderBalanceIs(100);

        ResponseEntity<String> response = post(payment(101), validToken());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(0, ledgerRows());
    }

    @Test
    @DisplayName("Validation failures (zero amount, send-to-self, another customer's account) write nothing")
    void invalidTransactionsNotPersisted() {
        senderBalanceIs(10_000);
        String token = validToken();

        assertEquals(HttpStatus.BAD_REQUEST, post(payment(0), token).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, post(body(AUTHED_ACCT, LOCAL_ROUTING, AUTHED_ACCT, 100,
                UUID.randomUUID().toString()), token).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, post(body(OTHER_ACCT, LOCAL_ROUTING, AUTHED_ACCT, 100,
                UUID.randomUUID().toString()), token).getStatusCode());
        assertEquals(0, ledgerRows());
    }

    @Test
    @DisplayName("Forged, expired or missing tokens are rejected and nothing is written")
    void unauthenticatedRequestsNotPersisted() {
        senderBalanceIs(10_000);
        Date future = new Date(System.currentTimeMillis() + 3_600_000);

        assertEquals(HttpStatus.UNAUTHORIZED,
                post(payment(100), token(ATTACKER_KEYS, AUTHED_ACCT, future)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                post(payment(100), token(KEYS, AUTHED_ACCT, new Date(System.currentTimeMillis() - 60_000))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, post(payment(100), null).getStatusCode());
        assertEquals(0, ledgerRows());
    }

    @Test
    @DisplayName("A replayed deposit UUID produces one ledger row, not two")
    void replayedDepositWrittenOnce() {
        Map<String, Object> deposit = body(EXTERNAL_ACCT, EXTERNAL_ROUTING, AUTHED_ACCT, 50_000,
                UUID.randomUUID().toString());

        assertEquals(HttpStatus.CREATED, post(deposit, validToken()).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, post(deposit, validToken()).getStatusCode());
        assertEquals(1, ledgerRows());
    }

    @Test
    @DisplayName("Balance service failure returns 500 and nothing is written")
    void balanceServiceFailureNotPersisted() {
        balancereader.expect(manyTimes(), requestTo(endsWith("/balances/" + AUTHED_ACCT))).andRespond(withServerError());

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, post(payment(100), validToken()).getStatusCode());
        assertEquals(0, ledgerRows());
    }

    @Test
    @DisplayName("The ledger schema is append-only: updates and deletes have no effect")
    void ledgerIsAppendOnly() {
        senderBalanceIs(10_000);
        assertEquals(HttpStatus.CREATED, post(payment(700), validToken()).getStatusCode());

        assertEquals(0, jdbc.update("UPDATE TRANSACTIONS SET AMOUNT = 1"));
        assertEquals(0, jdbc.update("DELETE FROM TRANSACTIONS"));
        assertEquals(700, jdbc.queryForObject("SELECT AMOUNT FROM TRANSACTIONS", Integer.class));
    }

    @Test
    @DisplayName("DEFECT: a payment with no amount is a 400 validation error and writes nothing")
    void missingAmountRejectedAsBadRequest() {
        senderBalanceIs(10_000);
        Map<String, Object> noAmount = payment(100);
        noAmount.remove("amount");

        ResponseEntity<String> response = post(noAmount, validToken());

        assertEquals(0, ledgerRows());
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
