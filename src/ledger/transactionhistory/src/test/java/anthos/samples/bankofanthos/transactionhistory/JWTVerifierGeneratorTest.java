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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.AlgorithmMismatchException;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JWTVerifierGeneratorTest {

    private static KeyPair signer;
    private static KeyPair attacker;

    @TempDir
    Path dir;

    @BeforeAll
    static void keys() {
        signer = TestFixtures.newRsaKeyPair();
        attacker = TestFixtures.newRsaKeyPair();
    }

    private JWTVerifier verifierFor(KeyPair keys) {
        return new JWTVerifierGenerator()
            .generateJWTVerifier(TestFixtures.writePublicKeyPem(keys, dir).toString());
    }

    @Test
    @DisplayName("PEM public key yields a verifier that accepts tokens from the matching private key")
    void acceptsTokenSignedByMatchingKey() {
        String token = TestFixtures.validToken(signer, "1011226111");

        assertThat(verifierFor(signer).verify(token).getClaim("acct").asString())
            .isEqualTo("1011226111");
    }

    @Test
    @DisplayName("Token signed by a different RSA key is rejected (forged identity)")
    void rejectsTokenSignedByOtherKey() {
        String forged = TestFixtures.validToken(attacker, "1011226111");

        assertThatThrownBy(() -> verifierFor(signer).verify(forged))
            .isInstanceOf(SignatureVerificationException.class);
    }

    @Test
    @DisplayName("Expired token is rejected")
    void rejectsExpiredToken() {
        String expired = TestFixtures.token(signer, "1011226111", Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> verifierFor(signer).verify(expired))
            .isInstanceOf(TokenExpiredException.class);
    }

    @Test
    @DisplayName("Unsigned (alg=none) and HMAC tokens are rejected: only RS256 is accepted")
    void rejectsAlgorithmDowngrade() {
        JWTVerifier verifier = verifierFor(signer);
        String unsigned = JWT.create().withClaim("acct", "1011226111").sign(Algorithm.none());
        String hmac = JWT.create().withClaim("acct", "1011226111").sign(Algorithm.HMAC256("guessable"));

        assertThatThrownBy(() -> verifier.verify(unsigned)).isInstanceOf(AlgorithmMismatchException.class);
        assertThatThrownBy(() -> verifier.verify(hmac)).isInstanceOf(AlgorithmMismatchException.class);
    }

    @Test
    @DisplayName("Missing key file fails startup with GenerateKeyException")
    void missingKeyFileFailsFast() {
        String path = dir.resolve("absent").toString();

        assertThatThrownBy(() -> new JWTVerifierGenerator().generateJWTVerifier(path))
            .isInstanceOf(JWTVerifierGenerator.GenerateKeyException.class)
            .hasMessageContaining("Cannot generate key");
    }

    @Test
    @DisplayName("Base64 content that is not an X.509 RSA key fails startup with GenerateKeyException")
    void invalidKeySpecFailsFast() throws Exception {
        Path bogus = Files.writeString(dir.resolve("bogus"),
            "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n");

        assertThatThrownBy(() -> new JWTVerifierGenerator().generateJWTVerifier(bogus.toString()))
            .isInstanceOf(JWTVerifierGenerator.GenerateKeyException.class);
    }
}
