import { execFileSync } from 'node:child_process';
import path from 'node:path';

const repoRoot = path.resolve(__dirname, '../../..');

function compose(...args: string[]): string {
  return execFileSync('docker', ['compose', '-f', 'compose.yaml', '-f', 'compose.ledgerwriter-source.yaml', ...args],
    { cwd: repoRoot, encoding: 'utf8' }).trim();
}

export interface LedgerRow {
  fromAcct: string;
  fromRoute: string;
  toAcct: string;
  toRoute: string;
  amount: number;
}

/** Reads ledger-db directly so assertions cover what was persisted, not just what the UI shows. */
export function ledgerRowsInvolving(account: string): LedgerRow[] {
  if (!/^\d{10}$/.test(account)) throw new Error(`not an account number: ${account}`);
  const out = compose('exec', '-T', 'ledger-db', 'psql', '-U', 'admin', '-d', 'postgresdb', '-At', '-F', '|', '-c',
    `SELECT from_acct, from_route, to_acct, to_route, amount FROM transactions ` +
    `WHERE from_acct = '${account}' OR to_acct = '${account}' ORDER BY transaction_id`);
  return out ? out.split('\n').map((line) => {
    const [fromAcct, fromRoute, toAcct, toRoute, amount] = line.split('|');
    return { fromAcct, fromRoute, toAcct, toRoute, amount: Number(amount) };
  }) : [];
}

export function ledgerWriterVersion(): string {
  return compose('exec', '-T', 'ledgerwriter', 'curl', '-fsS', 'http://localhost:8080/version');
}
