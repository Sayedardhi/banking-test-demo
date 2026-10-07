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

import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.ALICE;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.newRsaKeyPair;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.publicKeyPem;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JWTVerifierGeneratorTest {

    private final KeyPair keys = newRsaKeyPair();
    private final JWTVerifierGenerator generator = new JWTVerifierGenerator();

    @TempDir
    Path dir;

    private JWTVerifier verifierFor(String fileContent) throws Exception {
        Path key = dir.resolve("publickey");
        Files.writeString(key, fileContent);
        return generator.generateJWTVerifier(key.toString());
    }

    @Test
    @DisplayName("A verifier built from the PEM public key accepts tokens signed by the matching private key")
    void acceptsTokenFromMatchingKey() throws Exception {
        JWTVerifier verifier = verifierFor(publicKeyPem(keys));
        assertEquals(ALICE, verifier.verify(token(keys, ALICE)).getClaim(LedgerWriterController.JWT_ACCOUNT_KEY)
                .asString());
    }

    @Test
    @DisplayName("PEM files with CRLF line endings are accepted")
    void acceptsCrlfPem() throws Exception {
        JWTVerifier verifier = verifierFor(publicKeyPem(keys).replace("\n", "\r\n"));
        assertEquals(ALICE, verifier.verify(token(keys, ALICE)).getClaim("acct").asString());
    }

    @Test
    @DisplayName("Tokens signed by a different key are rejected")
    void rejectsTokenFromOtherKey() throws Exception {
        JWTVerifier verifier = verifierFor(publicKeyPem(keys));
        assertThrows(SignatureVerificationException.class, () -> verifier.verify(token(newRsaKeyPair(), ALICE)));
    }

    @Test
    @DisplayName("Expired tokens are rejected")
    void rejectsExpiredToken() throws Exception {
        JWTVerifier verifier = verifierFor(publicKeyPem(keys));
        assertThrows(TokenExpiredException.class,
                () -> verifier.verify(token(keys, ALICE, Instant.now().minusSeconds(60))));
    }

    @Test
    @DisplayName("A missing key file fails startup with GenerateKeyException")
    void missingKeyFileFails() {
        assertThrows(JWTVerifierGenerator.GenerateKeyException.class,
                () -> generator.generateJWTVerifier(dir.resolve("absent").toString()));
    }

    @Test
    @DisplayName("Base64 content that is not an RSA public key fails startup with GenerateKeyException")
    void nonKeyContentFails() {
        String notAKey = Base64.getEncoder().encodeToString("synthetic, not a key".getBytes());
        assertThrows(JWTVerifierGenerator.GenerateKeyException.class,
                () -> verifierFor("-----BEGIN PUBLIC KEY-----\n" + notAKey + "\n-----END PUBLIC KEY-----\n"));
    }
}
