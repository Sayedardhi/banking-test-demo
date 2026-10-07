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

import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/** One shared ledger container per JVM; every test starts from an empty TRANSACTIONS table. */
abstract class SharedLedgerIntegrationSupport extends LedgerWriterIntegrationSupport {

    static final PostgreSQLContainer<?> LEDGER_DB = ledgerDatabase();
    private static final Path PUBLIC_KEY = writePublicKey(KEYS);

    static {
        LEDGER_DB.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        applicationEnvironment(registry, LEDGER_DB, PUBLIC_KEY);
    }
}
