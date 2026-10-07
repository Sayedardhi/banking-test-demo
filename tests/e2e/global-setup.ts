import { ledgerWriterVersion } from './support/ledger';

/** Fails fast unless the running ledgerwriter is the one built from this checkout. */
export default async function globalSetup() {
  const expected = process.env.E2E_EXPECTED_LEDGERWRITER_VERSION;
  const actual = ledgerWriterVersion();
  if (!actual.startsWith('source-') || (expected && actual !== expected)) {
    throw new Error(`ledgerwriter is not source-built: VERSION=${actual}, expected ${expected ?? 'source-*'}. ` +
      'Start it with: docker compose -f compose.yaml -f compose.ledgerwriter-source.yaml up -d --wait');
  }
}
