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

package anthos.samples.bankofanthos.balancereader;

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

/**
 * Synthetic RSA key pair and token factory mirroring the userservice
 * token format (RS256, "acct" claim). Keys are generated per test run.
 */
final class TestJwtKeys {

    private final KeyPair keyPair;

    private TestJwtKeys(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    static TestJwtKeys generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return new TestJwtKeys(generator.generateKeyPair());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    RSAPublicKey publicKey() {
        return (RSAPublicKey) keyPair.getPublic();
    }

    String publicKeyPem() {
        String body = Base64.getMimeEncoder(64, "\n".getBytes())
            .encodeToString(keyPair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + body
            + "\n-----END PUBLIC KEY-----\n";
    }

    Path writePublicKey(Path dir) {
        try {
            Path file = dir.resolve("jwtRS256.key.pub");
            Files.writeString(file, publicKeyPem());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String tokenFor(String accountNum) {
        return tokenFor(accountNum, Instant.now().plusSeconds(3600));
    }

    String tokenFor(String accountNum, Instant expiresAt) {
        return JWT.create()
            .withSubject("synthetic-user")
            .withClaim("user", "synthetic-user")
            .withClaim("acct", accountNum)
            .withIssuedAt(Instant.now().minusSeconds(60))
            .withExpiresAt(expiresAt)
            .sign(Algorithm.RSA256(publicKey(),
                (RSAPrivateKey) keyPair.getPrivate()));
    }

    String tokenWithoutAccountClaim() {
        return JWT.create()
            .withSubject("synthetic-user")
            .withExpiresAt(Instant.now().plusSeconds(3600))
            .sign(Algorithm.RSA256(publicKey(),
                (RSAPrivateKey) keyPair.getPrivate()));
    }
}
