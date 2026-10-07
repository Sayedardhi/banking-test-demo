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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.AlgorithmMismatchException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The JWT verifier built from the mounted public key is the service's only authentication control. */
class JWTVerifierGeneratorTest {

    private static final KeyPair SIGNER = rsaKeyPair();
    private static final KeyPair ATTACKER = rsaKeyPair();
    private static final Instant FIXED_NOW = Instant.parse("2030-01-01T00:00:00Z");

    @TempDir
    Path dir;

    private static KeyPair rsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Path pem(String body) throws Exception {
        Path file = dir.resolve("publickey");
        Files.writeString(file, "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n");
        return file;
    }

    private JWTVerifier verifierFor(KeyPair keys) throws Exception {
        return new JWTVerifierGenerator().generateJWTVerifier(
                pem(Base64.getMimeEncoder().encodeToString(keys.getPublic().getEncoded())).toString());
    }

    private static String token(KeyPair keys, Instant expiresAt) {
        return JWT.create().withClaim("acct", "1011226111").withExpiresAt(expiresAt)
                .sign(Algorithm.RSA256(null, (RSAPrivateKey) keys.getPrivate()));
    }

    @Test
    @DisplayName("A token signed by the bank's key is accepted and exposes the account claim")
    void acceptsTokenFromTrustedSigner() throws Exception {
        assertEquals("1011226111",
                verifierFor(SIGNER).verify(token(SIGNER, FIXED_NOW)).getClaim("acct").asString());
    }

    @Test
    @DisplayName("A token signed by any other key is rejected")
    void rejectsTokenFromUntrustedSigner() throws Exception {
        JWTVerifier verifier = verifierFor(SIGNER);
        assertThrows(SignatureVerificationException.class, () -> verifier.verify(token(ATTACKER, FIXED_NOW)));
    }

    @Test
    @DisplayName("An expired token is rejected")
    void rejectsExpiredToken() throws Exception {
        JWTVerifier verifier = verifierFor(SIGNER);
        assertThrows(TokenExpiredException.class,
                () -> verifier.verify(token(SIGNER, Instant.now().minusSeconds(60))));
    }

    @Test
    @DisplayName("Changing the account claim of a signed token invalidates it")
    void rejectsTamperedAccountClaim() throws Exception {
        String[] parts = token(SIGNER, FIXED_NOW).split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"acct\":\"1033623433\",\"exp\":" + FIXED_NOW.getEpochSecond() + "}")
                        .getBytes(StandardCharsets.UTF_8));
        JWTVerifier verifier = verifierFor(SIGNER);
        assertThrows(SignatureVerificationException.class,
                () -> verifier.verify(parts[0] + "." + forgedPayload + "." + parts[2]));
    }

    @Test
    @DisplayName("An HS256 token keyed with the public key (algorithm confusion) is rejected")
    void rejectsAlgorithmConfusion() throws Exception {
        String hmacToken = JWT.create().withClaim("acct", "1011226111").withExpiresAt(FIXED_NOW)
                .sign(Algorithm.HMAC256(SIGNER.getPublic().getEncoded()));
        JWTVerifier verifier = verifierFor(SIGNER);
        assertThrows(AlgorithmMismatchException.class, () -> verifier.verify(hmacToken));
    }

    @Test
    @DisplayName("A missing public key file fails startup with GenerateKeyException")
    void missingKeyFileFailsStartup() {
        assertThrows(JWTVerifierGenerator.GenerateKeyException.class,
                () -> new JWTVerifierGenerator().generateJWTVerifier(dir.resolve("absent").toString()));
    }

    @Test
    @DisplayName("A key file that is not an RSA public key fails startup with GenerateKeyException")
    void invalidKeyFailsStartup() throws Exception {
        String notAKey = Base64.getEncoder().encodeToString("synthetic-not-a-key".getBytes(StandardCharsets.UTF_8));
        Path file = pem(notAKey);
        assertThrows(JWTVerifierGenerator.GenerateKeyException.class,
                () -> new JWTVerifierGenerator().generateJWTVerifier(file.toString()));
    }
}
