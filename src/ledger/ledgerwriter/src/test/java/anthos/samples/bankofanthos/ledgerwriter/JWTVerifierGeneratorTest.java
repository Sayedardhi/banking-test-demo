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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JWTVerifierGeneratorTest {

    private static KeyPair trustedKeys;
    private static KeyPair otherKeys;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        trustedKeys = generator.generateKeyPair();
        otherKeys = generator.generateKeyPair();
    }

    private Path writePem(KeyPair keys) throws Exception {
        String body = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(keys.getPublic().getEncoded());
        Path file = tempDir.resolve("publickey");
        Files.writeString(file, "-----BEGIN PUBLIC KEY-----\n" + body
                + "\n-----END PUBLIC KEY-----\n");
        return file;
    }

    private static String token(KeyPair keys, Instant expiresAt) {
        return JWT.create().withClaim("acct", "1234567890")
                .withExpiresAt(expiresAt)
                .sign(Algorithm.RSA256((RSAPublicKey) keys.getPublic(),
                        (RSAPrivateKey) keys.getPrivate()));
    }

    @Test
    @DisplayName("Given a PEM public key, the verifier accepts tokens signed "
            + "by the matching private key and exposes the acct claim")
    void verifiesTokenSignedByTrustedKey() throws Exception {
        JWTVerifier verifier = new JWTVerifierGenerator()
                .generateJWTVerifier(writePem(trustedKeys).toString());

        String acct = verifier.verify(
                token(trustedKeys, Instant.now().plusSeconds(60)))
                .getClaim("acct").asString();

        assertEquals("1234567890", acct);
    }

    @Test
    @DisplayName("Given a token signed by a different key, verification fails")
    void rejectsTokenSignedByOtherKey() throws Exception {
        JWTVerifier verifier = new JWTVerifierGenerator()
                .generateJWTVerifier(writePem(trustedKeys).toString());

        assertThrows(JWTVerificationException.class, () -> verifier.verify(
                token(otherKeys, Instant.now().plusSeconds(60))));
    }

    @Test
    @DisplayName("Given an expired token, verification fails")
    void rejectsExpiredToken() throws Exception {
        JWTVerifier verifier = new JWTVerifierGenerator()
                .generateJWTVerifier(writePem(trustedKeys).toString());

        assertThrows(JWTVerificationException.class, () -> verifier.verify(
                token(trustedKeys, Instant.now().minusSeconds(60))));
    }

    @Test
    @DisplayName("Given a missing key file, startup fails with GenerateKeyException")
    void failsWhenKeyFileMissing() {
        assertThrows(JWTVerifierGenerator.GenerateKeyException.class,
                () -> new JWTVerifierGenerator().generateJWTVerifier(
                        tempDir.resolve("missing").toString()));
    }

    @Test
    @DisplayName("Given a key file that is not an RSA public key, startup "
            + "fails with GenerateKeyException")
    void failsWhenKeyIsNotRsaPublicKey() throws Exception {
        Path file = tempDir.resolve("publickey");
        Files.writeString(file, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString("not a key".getBytes())
                + "\n-----END PUBLIC KEY-----\n");

        assertThrows(JWTVerifierGenerator.GenerateKeyException.class,
                () -> new JWTVerifierGenerator().generateJWTVerifier(
                        file.toString()));
    }
}
