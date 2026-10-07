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

import java.nio.file.Path;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** Disposable PostgreSQL 16 ledger with the production schema from src/ledger/ledger-db/initdb (no demo seed data). */
final class LedgerDatabase {
    static final Path SCHEMA = Path.of("..", "ledger-db", "initdb", "0_init_tables.sql");

    private LedgerDatabase() {
    }

    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> container() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("postgresdb")
            .withUsername("balancereader-test")
            .withPassword("synthetic-password")
            .withCopyFileToContainer(MountableFile.forHostPath(SCHEMA.toAbsolutePath()),
                "/docker-entrypoint-initdb.d/0_init_tables.sql");
    }

    static void register(DynamicPropertyRegistry registry, PostgreSQLContainer<?> db) {
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
        registry.add("spring.datasource.hikari.connection-timeout", () -> "2000");
        registry.add("PUB_KEY_PATH", () -> TestTokens.writePublicKey(TestTokens.SIGNER).toString());
        registry.add("LOCAL_ROUTING_NUM", () -> TestTransactions.LOCAL_ROUTING);
        registry.add("VERSION", () -> "v-integration");
        registry.add("POLL_MS", () -> "50");
        registry.add("ENABLE_TRACING", () -> "false");
        registry.add("spring.cloud.gcp.core.enabled", () -> "false");
    }

    static void insert(JdbcTemplate jdbc, String from, String fromRoute, String to, String toRoute, int cents) {
        jdbc.update("INSERT INTO TRANSACTIONS (FROM_ACCT, TO_ACCT, FROM_ROUTE, TO_ROUTE, AMOUNT, TIMESTAMP) "
            + "VALUES (?, ?, ?, ?, ?, now())", from, to, fromRoute, toRoute, cents);
    }
}
