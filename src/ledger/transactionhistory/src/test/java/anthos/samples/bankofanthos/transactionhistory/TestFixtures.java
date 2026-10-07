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

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import io.micrometer.core.instrument.Clock;
import io.micrometer.stackdriver.StackdriverConfig;
import io.micrometer.stackdriver.StackdriverMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.springframework.test.util.ReflectionTestUtils;

/** Synthetic test data shared by unit and integration tests. */
final class TestFixtures {

    static final String LOCAL_ROUTING = "123456789";
    static final String EXTERNAL_ROUTING = "987654321";
    static final Instant BASE_TIME = Instant.parse("2026-01-15T10:00:00Z");

    private TestFixtures() {
    }

    static Transaction tx(long id, String fromAcct, String fromRouting,
            String toAcct, String toRouting, int amountCents, Instant timestamp) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "transactionId", id);
        ReflectionTestUtils.setField(t, "fromAccountNum", fromAcct);
        ReflectionTestUtils.setField(t, "fromRoutingNum", fromRouting);
        ReflectionTestUtils.setField(t, "toAccountNum", toAcct);
        ReflectionTestUtils.setField(t, "toRoutingNum", toRouting);
        ReflectionTestUtils.setField(t, "amount", amountCents);
        ReflectionTestUtils.setField(t, "timestamp", Date.from(timestamp));
        return t;
    }

    static Transaction localTx(long id, String fromAcct, String toAcct, int amountCents) {
        return tx(id, fromAcct, LOCAL_ROUTING, toAcct, LOCAL_ROUTING, amountCents,
            BASE_TIME.plusSeconds(id));
    }

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

    static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Path writePublicKeyPem(KeyPair keys, Path file) throws IOException {
        Files.writeString(file, "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getMimeEncoder().encodeToString(keys.getPublic().getEncoded())
            + "\n-----END PUBLIC KEY-----\n");
        return file;
    }

    static String token(KeyPair keys, String account, Instant expiresAt) {
        return JWT.create()
            .withSubject("synthetic-user")
            .withClaim("acct", account)
            .withExpiresAt(Date.from(expiresAt))
            .sign(Algorithm.RSA256(null, (RSAPrivateKey) keys.getPrivate()));
    }

    static String validToken(KeyPair keys, String account) {
        return token(keys, account, Instant.now().plusSeconds(3600));
    }
}
