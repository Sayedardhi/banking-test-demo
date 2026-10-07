/*
 * Copyright 2026 Google LLC
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

package anthos.samples.bankofanthos.transactionhistory;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.springframework.test.util.ReflectionTestUtils;

/** Synthetic keys, tokens and ledger rows shared by the unit and integration tests. */
final class TestFixtures {

    static final String LOCAL_ROUTING = "883745000";
    static final String EXTERNAL_ROUTING = "808889588";

    private TestFixtures() {
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

    /** Writes the public key in the PEM format the frontend/userservice key secret uses. */
    static Path writePublicKeyPem(KeyPair keys, Path dir) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes())
            .encodeToString(keys.getPublic().getEncoded());
        try {
            return Files.writeString(dir.resolve("publickey"),
                "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Token shaped like userservice's: RS256 with user, acct, name claims. */
    static String token(KeyPair keys, String account, Instant expiresAt) {
        return JWT.create()
            .withSubject("synthetic-user")
            .withClaim("user", "synthetic-user")
            .withClaim("acct", account)
            .withClaim("name", "Synthetic User")
            .withIssuedAt(Date.from(Instant.now().minusSeconds(5)))
            .withExpiresAt(Date.from(expiresAt))
            .sign(Algorithm.RSA256((RSAPublicKey) keys.getPublic(), (RSAPrivateKey) keys.getPrivate()));
    }

    static String validToken(KeyPair keys, String account) {
        return token(keys, account, Instant.now().plusSeconds(3600));
    }

    static Transaction transaction(long id, String fromAccount, String fromRouting,
            String toAccount, String toRouting, int amountCents) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "transactionId", id);
        ReflectionTestUtils.setField(t, "fromAccountNum", fromAccount);
        ReflectionTestUtils.setField(t, "fromRoutingNum", fromRouting);
        ReflectionTestUtils.setField(t, "toAccountNum", toAccount);
        ReflectionTestUtils.setField(t, "toRoutingNum", toRouting);
        ReflectionTestUtils.setField(t, "amount", amountCents);
        ReflectionTestUtils.setField(t, "timestamp", new Date(1_700_000_000_000L + id * 1000));
        return t;
    }
}
