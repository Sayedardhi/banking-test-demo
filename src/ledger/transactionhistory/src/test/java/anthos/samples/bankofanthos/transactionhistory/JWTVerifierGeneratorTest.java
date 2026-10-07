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
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real RS256 verification built from a PEM public key file, as mounted from the jwt-key secret. */
class JWTVerifierGeneratorTest {

    private static final String ACCOUNT = "1111111111";
    private static KeyPair signer;
    private static KeyPair attacker;

    @TempDir
    Path dir;

    @BeforeAll
    static void keys() {
        signer = TestFixtures.rsaKeyPair();
        attacker = TestFixtures.rsaKeyPair();
    }

    private JWTVerifier verifier() throws Exception {
        Path pem = TestFixtures.writePublicKeyPem(signer, dir.resolve("publickey"));
        return new JWTVerifierGenerator().generateJWTVerifier(pem.toString());
    }

    @Test
    @DisplayName("Token signed by the bank's private key verifies and exposes the account claim")
    void acceptsTokenFromSigner() throws Exception {
        String token = TestFixtures.validToken(signer, ACCOUNT);

        assertThat(verifier().verify(token).getClaim("acct").asString()).isEqualTo(ACCOUNT);
    }

    @Test
    @DisplayName("Token signed by a different key is rejected")
    void rejectsForeignSignature() throws Exception {
        String forged = TestFixtures.validToken(attacker, ACCOUNT);

        assertThatThrownBy(() -> verifier().verify(forged))
            .isInstanceOf(SignatureVerificationException.class);
    }

    @Test
    @DisplayName("Expired token is rejected")
    void rejectsExpired() throws Exception {
        String expired = TestFixtures.token(signer, ACCOUNT, Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> verifier().verify(expired)).isInstanceOf(TokenExpiredException.class);
    }

    @Test
    @DisplayName("Unsigned (alg=none) and HMAC tokens are rejected (algorithm confusion)")
    void rejectsAlgorithmConfusion() throws Exception {
        JWTVerifier verifier = verifier();
        String none = JWT.create().withClaim("acct", ACCOUNT).sign(Algorithm.none());
        String hmac = JWT.create().withClaim("acct", ACCOUNT)
            .sign(Algorithm.HMAC256(signer.getPublic().getEncoded()));

        assertThatThrownBy(() -> verifier.verify(none)).isInstanceOf(AlgorithmMismatchException.class);
        assertThatThrownBy(() -> verifier.verify(hmac)).isInstanceOf(AlgorithmMismatchException.class);
    }

    @Test
    @DisplayName("Missing public key file fails startup with GenerateKeyException")
    void missingKeyFileFailsFast() {
        String missing = dir.resolve("absent.pem").toString();

        assertThatThrownBy(() -> new JWTVerifierGenerator().generateJWTVerifier(missing))
            .isInstanceOf(JWTVerifierGenerator.GenerateKeyException.class);
    }

    @Test
    @DisplayName("PEM that does not contain an RSA public key fails startup with GenerateKeyException")
    void invalidKeyFailsFast() throws Exception {
        Path pem = Files.writeString(dir.resolve("bad.pem"), "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getEncoder().encodeToString("not-a-key".getBytes()) + "\n-----END PUBLIC KEY-----\n");

        assertThatThrownBy(() -> new JWTVerifierGenerator().generateJWTVerifier(pem.toString()))
            .isInstanceOf(JWTVerifierGenerator.GenerateKeyException.class);
    }
}
