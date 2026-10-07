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

import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.json;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.newRsaKeyPair;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.publicKeyPem;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Runs the packaged Spring Boot application on a random port against a throwaway PostgreSQL 16 container that is
 * initialised with the production ledger schema (src/ledger/ledger-db/initdb/0_init_tables.sql, no demo data).
 * Subclasses bind the application to a container via {@link #applicationEnvironment}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class LedgerWriterIntegrationSupport {

    static final String LEDGER_SCHEMA = "../ledger-db/initdb/0_init_tables.sql";
    static final KeyPair KEYS = newRsaKeyPair();
    static final BalanceReaderStub BALANCES = new BalanceReaderStub();

    static {
        BALANCES.start();
    }

    @LocalServerPort
    private int port;
    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    static PostgreSQLContainer<?> ledgerDatabase() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("postgresdb").withUsername("admin").withPassword("password")
                .withCopyFileToContainer(MountableFile.forHostPath(LEDGER_SCHEMA),
                        "/docker-entrypoint-initdb.d/0_init_tables.sql");
    }

    static Path writePublicKey(KeyPair keys) {
        try {
            Path file = Files.createTempFile("ledgerwriter-it-publickey", ".pem");
            file.toFile().deleteOnExit();
            return Files.writeString(file, publicKeyPem(keys));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void applicationEnvironment(DynamicPropertyRegistry registry, PostgreSQLContainer<?> db, Path publicKey) {
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
        registry.add("spring.datasource.hikari.connection-timeout", () -> "2000");
        registry.add("PUB_KEY_PATH", publicKey::toString);
        registry.add("LOCAL_ROUTING_NUM", () -> LedgerFixtures.LOCAL_ROUTING);
        registry.add("BALANCES_API_ADDR", BALANCES::address);
        registry.add("VERSION", () -> "integration-test");
        registry.add("PORT", () -> "0");
        registry.add("ENABLE_TRACING", () -> "false");
    }

    @BeforeEach
    void emptyLedger() {
        // TRUNCATE is not intercepted by the schema's PREVENT_DELETE rule.
        jdbc.execute("TRUNCATE TABLE transactions RESTART IDENTITY");
        BALANCES.reset();
    }

    HttpRequest request(String authorization, String contentType, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/transactions"))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        return builder.build();
    }

    HttpResponse<String> post(String authorization, String contentType, String body) {
        try {
            return http.send(request(authorization, contentType, body), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    HttpResponse<String> submit(String token, Map<String, Object> body) {
        return post("Bearer " + token, "application/json", json(body));
    }

    CompletableFuture<HttpResponse<String>> submitAsync(String token, Map<String, Object> body) {
        return http.sendAsync(request("Bearer " + token, "application/json", json(body)),
                HttpResponse.BodyHandlers.ofString());
    }

    List<Map<String, Object>> ledger() {
        return jdbc.queryForList("SELECT transaction_id, from_acct, from_route, to_acct, to_route, amount, timestamp"
                + " FROM transactions ORDER BY transaction_id");
    }
}
