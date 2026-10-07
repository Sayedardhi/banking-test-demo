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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.spec.InvalidKeySpecException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loading the JWT signer's public key from PUB_KEY_PATH. */
class JWTVerifierGeneratorTest {

    @TempDir
    Path dir;

    private final JWTVerifierGenerator generator = new JWTVerifierGenerator();

    @Test
    @DisplayName("A PEM public key yields a verifier that accepts tokens from the matching private key")
    void verifiesMatchingSignature() {
        JWTVerifier verifier = generator.generateJWTVerifier(TestTokens.writePublicKey(TestTokens.SIGNER).toString());

        assertThat(verifier.verify(TestTokens.token("1011226111")).getClaim("acct").asString())
            .isEqualTo("1011226111");
    }

    @Test
    @DisplayName("The verifier rejects tokens signed by any other key")
    void rejectsOtherSignature() {
        JWTVerifier verifier = generator.generateJWTVerifier(TestTokens.writePublicKey(TestTokens.SIGNER).toString());
        String forged = TestTokens.token(TestTokens.OTHER_SIGNER, "1011226111", Instant.now().plus(1, ChronoUnit.HOURS));

        assertThatThrownBy(() -> verifier.verify(forged)).isInstanceOf(SignatureVerificationException.class);
    }

    @Test
    @DisplayName("CRLF line endings and a single-line key body are accepted")
    void toleratesWhitespaceVariants() throws IOException {
        Path file = dir.resolve("crlf.pem");
        String body = Base64.getEncoder().encodeToString(TestTokens.SIGNER.getPublic().getEncoded());
        Files.writeString(file, "-----BEGIN PUBLIC KEY-----\r\n" + body + "\r\n-----END PUBLIC KEY-----\r\n");

        JWTVerifier verifier = generator.generateJWTVerifier(file.toString());

        assertThat(verifier.verify(TestTokens.token("1011226111"))).isNotNull();
    }

    @Test
    @DisplayName("A missing key file fails startup with GenerateKeyException")
    void missingFile() {
        assertThatThrownBy(() -> generator.generateJWTVerifier(dir.resolve("absent.pem").toString()))
            .isInstanceOf(JWTVerifierGenerator.GenerateKeyException.class)
            .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("Base64 content that is not an RSA public key fails startup with GenerateKeyException")
    void notAKey() throws IOException {
        Path file = dir.resolve("bogus.pem");
        Files.writeString(file, "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getEncoder().encodeToString("synthetic, not a key".getBytes()) + "\n-----END PUBLIC KEY-----\n");

        assertThatThrownBy(() -> generator.generateJWTVerifier(file.toString()))
            .isInstanceOf(JWTVerifierGenerator.GenerateKeyException.class)
            .hasCauseInstanceOf(InvalidKeySpecException.class);
    }
}
