/*
 * Copyright 2026, Google LLC.
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

package anthos.samples.bankofanthos.balancereader;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
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
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/** Synthetic RSA keys and JWTs shaped like the ones userservice issues (claims user, acct, name). */
final class TestTokens {

    static final KeyPair SIGNER = newKeyPair();
    static final KeyPair OTHER_SIGNER = newKeyPair();

    private TestTokens() {
    }

    static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static JWTVerifier verifier() {
        return JWT.require(Algorithm.RSA256((RSAPublicKey) SIGNER.getPublic(), null)).build();
    }

    static String token(String account) {
        return token(SIGNER, account, Instant.now().plus(1, ChronoUnit.HOURS));
    }

    static String token(KeyPair keys, String account, Instant expiresAt) {
        return JWT.create()
            .withClaim("user", "synthetic-user")
            .withClaim("acct", account)
            .withClaim("name", "Synthetic Customer")
            .withIssuedAt(Instant.now().minus(2, ChronoUnit.HOURS))
            .withExpiresAt(expiresAt)
            .sign(Algorithm.RSA256(null, (RSAPrivateKey) keys.getPrivate()));
    }

    static String tokenWithoutAccount() {
        return JWT.create()
            .withClaim("user", "synthetic-user")
            .withExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
            .sign(Algorithm.RSA256(null, (RSAPrivateKey) SIGNER.getPrivate()));
    }

    static String unsignedToken(String account) {
        return JWT.create().withClaim("acct", account)
            .withExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
            .sign(Algorithm.none());
    }

    /** Valid signature from SIGNER, but the payload is swapped for one that names another account. */
    static String tamperedToken(String signedFor, String claimed) {
        String[] genuine = token(signedFor).split("\\.");
        String[] forged = token(OTHER_SIGNER, claimed, Instant.now().plus(1, ChronoUnit.HOURS)).split("\\.");
        return genuine[0] + "." + forged[1] + "." + genuine[2];
    }

    static String bearer(String token) {
        return "Bearer " + token;
    }

    static String publicKeyPem(KeyPair keys) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }

    static Path writePublicKey(KeyPair keys) {
        try {
            Path file = Files.createTempFile("balancereader-test-publickey", ".pem");
            Files.writeString(file, publicKeyPem(keys));
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
