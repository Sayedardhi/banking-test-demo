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

import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Shared harness: the real Spring Boot application against a disposable postgres:16-alpine with the
 * production ledger schema (src/ledger/ledger-db/initdb/0_init_tables.sql). Each test class gets
 * its own container; the demo database is never touched. Rows and accounts are synthetic.
 */
abstract class LedgerIntegrationSupport {

    static final String L = TestFixtures.LOCAL_ROUTING;
    static final String X = TestFixtures.EXTERNAL_ROUTING;
    static final KeyPair KEYS = TestFixtures.newRsaKeyPair();
    static final Path SCHEMA =
        Path.of("../ledger-db/initdb/0_init_tables.sql").toAbsolutePath().normalize();

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LedgerReader ledgerReader;

    static PostgreSQLContainer<?> newLedgerDb() {
        PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("postgresdb").withUsername("admin").withPassword("password")
            .withCopyFileToContainer(MountableFile.forHostPath(SCHEMA),
                "/docker-entrypoint-initdb.d/0_init_tables.sql");
        db.start();
        return db;
    }

    static void register(DynamicPropertyRegistry r, PostgreSQLContainer<?> db, int pollMs, int historyLimit) {
        try {
            Path dir = Files.createTempDirectory("th-it-keys");
            r.add("PUB_KEY_PATH", () -> TestFixtures.writePublicKeyPem(KEYS, dir).toString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        // Short socket/connect timeouts so a paused database surfaces as an error, not a hang.
        r.add("spring.datasource.url", () -> db.getJdbcUrl() + "&socketTimeout=3&connectTimeout=3");
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
        r.add("spring.datasource.hikari.connection-timeout", () -> "2000");
        r.add("spring.datasource.hikari.validation-timeout", () -> "1000");
        r.add("LOCAL_ROUTING_NUM", () -> L);
        r.add("VERSION", () -> "it");
        r.add("PORT", () -> "0");
        r.add("ENABLE_TRACING", () -> "false");
        r.add("spring.cloud.gcp.core.enabled", () -> "false");
        r.add("POLL_MS", () -> String.valueOf(pollMs));
        r.add("HISTORY_LIMIT", () -> String.valueOf(historyLimit));
    }

    long insert(String from, String fromRoute, String to, String toRoute, int cents, Instant at) {
        return jdbc.queryForObject("INSERT INTO transactions (from_acct, to_acct, from_route, to_route, amount, timestamp) "
            + "VALUES (?, ?, ?, ?, ?, ?) RETURNING transaction_id", Long.class,
            from, to, fromRoute, toRoute, cents, Timestamp.from(at));
    }

    int ledgerRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class);
    }

    long readerPosition() {
        return (Long) ReflectionTestUtils.getField(ledgerReader, "latestTransactionId");
    }

    /** Waits until the background reader has consumed every row committed so far. */
    void awaitReaderCaughtUp() {
        Long head = jdbc.queryForObject("SELECT MAX(transaction_id) FROM transactions", Long.class);
        long target = head == null ? -1 : head;
        await().atMost(Duration.ofSeconds(15)).until(() -> readerPosition() >= target);
    }

    static String bearer(String account) {
        return "Bearer " + TestFixtures.validToken(KEYS, account);
    }
}
