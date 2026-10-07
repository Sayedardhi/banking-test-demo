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
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.BOB;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.deposit;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.payment;
import static anthos.samples.bankofanthos.ledgerwriter.LedgerFixtures.token;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Ledger database outage, against a dedicated container that the test stops. */
class LedgerDatabaseOutageIntegrationTest extends LedgerWriterIntegrationSupport {

    private static final PostgreSQLContainer<?> OUTAGE_DB = ledgerDatabase();
    private static final Path OUTAGE_KEY = writePublicKey(KEYS);

    static {
        OUTAGE_DB.start();
    }

    @DynamicPropertySource
    static void outageProperties(DynamicPropertyRegistry registry) {
        applicationEnvironment(registry, OUTAGE_DB, OUTAGE_KEY);
    }

    @AfterAll
    static void removeContainer() {
        OUTAGE_DB.stop();
    }

    @Override
    void emptyLedger() {
        BALANCES.reset();
    }

    @Test
    @DisplayName("Ledger database down: the write fails with 500 and the UUID is not burned")
    void databaseDown() {
        BALANCES.balance(ALICE, 10_000);
        String id = UUID.randomUUID().toString();
        OUTAGE_DB.stop();
        assertEquals(500, submit(token(KEYS, ALICE), payment(ALICE, BOB, 100, id)).statusCode());
        assertEquals(500, submit(token(KEYS, ALICE), deposit(ALICE, 100, UUID.randomUUID().toString())).statusCode());
    }
}
