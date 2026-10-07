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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.awaitility.Awaitility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Isolated ledger-db for integration tests: a throwaway postgres:16-alpine
 * container initialised with the production schema only
 * (src/ledger/ledger-db/initdb/0_init_tables.sql, no demo data).
 */
final class PostgresLedgerFixture {

    static final String LOCAL_ROUTING = "883745000";
    static final String EXTERNAL_ROUTING = "808889588";
    static final Instant FIXED_TIME = Instant.parse("2026-01-15T10:00:00Z");

    private static final AtomicLong NEXT_ACCOUNT = new AtomicLong(2_000_000_000L);

    private PostgresLedgerFixture() {
    }

    static PostgreSQLContainer<?> ledgerDb() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
            .withCopyFileToContainer(
                MountableFile.forHostPath(
                    Path.of("../ledger-db/initdb/0_init_tables.sql")),
                "/docker-entrypoint-initdb.d/0_init_tables.sql");
    }

    static void register(DynamicPropertyRegistry registry,
        PostgreSQLContainer<?> db, TestJwtKeys keys) throws Exception {
        Path keyDir = Files.createTempDirectory("balancereader-it-keys");
        Path publicKey = keys.writePublicKey(keyDir);
        registry.add("spring.datasource.url", () -> db.getJdbcUrl()
            + "&connectTimeout=2&socketTimeout=3");
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
        registry.add("spring.datasource.hikari.connection-timeout", () -> "2000");
        registry.add("PUB_KEY_PATH", publicKey::toString);
        registry.add("LOCAL_ROUTING_NUM", () -> LOCAL_ROUTING);
        registry.add("VERSION", () -> "integration-test");
        registry.add("PORT", () -> "0");
        registry.add("POLL_MS", () -> "50");
        registry.add("ENABLE_TRACING", () -> "false");
        registry.add("spring.cloud.gcp.trace.enabled", () -> "false");
    }

    /** Unique, deterministic 10-digit account number (ledger rows cannot be deleted). */
    static String newAccount() {
        return Long.toString(NEXT_ACCOUNT.incrementAndGet());
    }

    static void insert(JdbcTemplate jdbc, String fromAcct, String fromRoute,
        String toAcct, String toRoute, int amountCents) {
        jdbc.update("INSERT INTO TRANSACTIONS "
            + "(FROM_ACCT, TO_ACCT, FROM_ROUTE, TO_ROUTE, AMOUNT, TIMESTAMP) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
            fromAcct, toAcct, fromRoute, toRoute, amountCents,
            Timestamp.from(FIXED_TIME));
    }

    /**
     * Waits until the background poller has consumed every committed row,
     * so a following first balance read cannot race the poller.
     */
    static void awaitPollerCaughtUp(LedgerReader reader, JdbcTemplate jdbc) {
        Long max = jdbc.queryForObject(
            "SELECT MAX(TRANSACTION_ID) FROM TRANSACTIONS", Long.class);
        long target = max == null ? -1 : max;
        Awaitility.await("poller consumed all committed ledger rows")
            .atMost(Duration.ofSeconds(10)).until(() ->
            (long) ReflectionTestUtils.getField(reader, "latestTransactionId") >= target);
    }
}
