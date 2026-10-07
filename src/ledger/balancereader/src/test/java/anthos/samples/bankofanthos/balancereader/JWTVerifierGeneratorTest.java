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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifier construction from the mounted public key (PUB_KEY_PATH). */
class JWTVerifierGeneratorTest {

    private static final TestJwtKeys KEYS = TestJwtKeys.generate();

    @TempDir
    Path tempDir;

    private JWTVerifier verifier() {
        return new JWTVerifierGenerator().generateJWTVerifier(
            KEYS.writePublicKey(tempDir).toString());
    }

    @Test
    @DisplayName("Token signed by the trusted private key is accepted with its acct claim")
    void acceptsTrustedToken() {
        assertEquals("1000000001",
            verifier().verify(KEYS.tokenFor("1000000001")).getClaim("acct").asString());
    }

    @Test
    @DisplayName("Token signed by a different RSA key is rejected")
    void rejectsUntrustedSigner() {
        String other = TestJwtKeys.generate().tokenFor("1000000001");
        assertThrows(JWTVerificationException.class, () -> verifier().verify(other));
    }

    @Test
    @DisplayName("HS256 token keyed with the public key (algorithm confusion) is rejected")
    void rejectsAlgorithmConfusion() {
        String hs = JWT.create().withClaim("acct", "1000000001")
            .sign(Algorithm.HMAC256(KEYS.publicKeyPem()));
        assertThrows(JWTVerificationException.class, () -> verifier().verify(hs));
    }

    @Test
    @DisplayName("Unsigned (alg=none) token is rejected")
    void rejectsUnsignedToken() {
        String none = JWT.create().withClaim("acct", "1000000001")
            .sign(Algorithm.none());
        assertThrows(JWTVerificationException.class, () -> verifier().verify(none));
    }

    @Test
    @DisplayName("Expired token is rejected")
    void rejectsExpiredToken() {
        String expired = KEYS.tokenFor("1000000001", Instant.now().minusSeconds(1));
        assertThrows(JWTVerificationException.class, () -> verifier().verify(expired));
    }

    @Test
    @DisplayName("Missing key file fails startup with GenerateKeyException")
    void missingKeyFileFailsStartup() {
        String missing = tempDir.resolve("absent.pub").toString();
        assertThrows(JWTVerifierGenerator.GenerateKeyException.class,
            () -> new JWTVerifierGenerator().generateJWTVerifier(missing));
    }

    @Test
    @DisplayName("Key file that is not an RSA public key fails startup with GenerateKeyException")
    void invalidKeyMaterialFailsStartup() throws Exception {
        Path bogus = tempDir.resolve("bogus.pub");
        Files.writeString(bogus, "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getEncoder().encodeToString("not a key".getBytes())
            + "\n-----END PUBLIC KEY-----\n");
        assertThrows(JWTVerifierGenerator.GenerateKeyException.class,
            () -> new JWTVerifierGenerator().generateJWTVerifier(bogus.toString()));
    }
}
