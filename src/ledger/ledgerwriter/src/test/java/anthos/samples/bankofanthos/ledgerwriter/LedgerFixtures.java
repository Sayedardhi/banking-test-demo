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

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Clock;
import io.micrometer.stackdriver.StackdriverConfig;
import io.micrometer.stackdriver.StackdriverMeterRegistry;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/** Synthetic accounts, signed tokens and request bodies shared by the unit and integration tests. */
final class LedgerFixtures {

    static final String LOCAL_ROUTING = "883745000";
    static final String EXTERNAL_ROUTING = "808889588";
    static final String ALICE = "1011226111";
    static final String BOB = "1033623433";
    static final String EXTERNAL_ACCOUNT = "9099791699";

    private static final ObjectMapper JSON = new ObjectMapper();

    private LedgerFixtures() {
    }

    static KeyPair newRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** PEM encoding as written by {@code openssl rsa -pubout}, with 64-character lines. */
    static String publicKeyPem(KeyPair keys) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(keys.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }

    /** A token shaped like the ones userservice issues: subject, account claim and expiry. */
    static String token(KeyPair keys, String account) {
        return token(keys, account, Instant.now().plus(Duration.ofHours(1)));
    }

    static String token(KeyPair keys, String account, Instant expiresAt) {
        return JWT.create()
                .withSubject("synthetic-" + account)
                .withClaim(LedgerWriterController.JWT_ACCOUNT_KEY, account)
                .withIssuedAt(Date.from(expiresAt.minus(Duration.ofHours(2))))
                .withExpiresAt(Date.from(expiresAt))
                .sign(Algorithm.RSA256((RSAPublicKey) keys.getPublic(), (RSAPrivateKey) keys.getPrivate()));
    }

    /** JSON body as posted by the frontend. A null value is sent as an explicit JSON null. */
    static Map<String, Object> body(String fromAccount, String fromRouting, String toAccount,
                                    String toRouting, Object amount, String uuid) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromAccountNum", fromAccount);
        body.put("fromRoutingNum", fromRouting);
        body.put("toAccountNum", toAccount);
        body.put("toRoutingNum", toRouting);
        body.put("amount", amount);
        body.put("uuid", uuid);
        return body;
    }

    static Map<String, Object> payment(String from, String to, int amount, String uuid) {
        return body(from, LOCAL_ROUTING, to, LOCAL_ROUTING, amount, uuid);
    }

    static Map<String, Object> deposit(String to, int amount, String uuid) {
        return body(EXTERNAL_ACCOUNT, EXTERNAL_ROUTING, to, LOCAL_ROUTING, amount, uuid);
    }

    static Transaction transaction(Map<String, Object> body) {
        return JSON.convertValue(body, Transaction.class);
    }

    static String json(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Metrics export disabled, as in the existing controller tests. */
    static StackdriverMeterRegistry disabledMeterRegistry() {
        return new StackdriverMeterRegistry(new StackdriverConfig() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public String projectId() {
                return "test";
            }

            @Override
            public String get(String key) {
                return null;
            }
        }, Clock.SYSTEM);
    }
}
