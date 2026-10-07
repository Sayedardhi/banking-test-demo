// Markdown job summary: per-layer test counts and line/branch coverage (covered/total).
// Usage: node scripts/ci-summary.mjs <results-dir>   (expects unit/, integration/, service/ subfolders)
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const dir = process.argv[2] ?? 'test-results';
const tests = layer => {
  const file = join(dir, layer, 'junit.xml');
  if (!existsSync(file)) return null;
  const cases = readFileSync(file, 'utf8').match(/<testcase\b[^>]*?(\/>|>[\s\S]*?<\/testcase>)/g) ?? [];
  const failed = cases.filter(c => /<(failure|error)\b/.test(c)).length;
  const skipped = cases.filter(c => /<skipped\b/.test(c)).length;
  return { total: cases.length, failed, skipped, passed: cases.length - failed - skipped };
};
const coverage = layer => {
  const file = join(dir, layer, 'coverage.xml');
  if (!existsSync(file)) return null;
  const root = readFileSync(file, 'utf8').match(/<coverage\b[^>]*>/)?.[0] ?? '';
  const attr = name => Number(root.match(new RegExp(`${name}="(\\d+)"`))?.[1]);
  return { line: [attr('lines-covered'), attr('lines-valid')], branch: [attr('branches-covered'), attr('branches-valid')] };
};
const ratio = ([hit, total]) => total ? `${hit}/${total} (${(100 * hit / total).toFixed(1)}%)` : 'n/a';

const out = ['## audit (TypeScript): Audit logging & sensitive-data minimization', '',
  '| Layer | Tests | Passed | Failed | Skipped | Line covered/total | Branch covered/total |', '|---|---|---|---|---|---|---|'];
for (const layer of ['unit', 'integration']) {
  const t = tests(layer), c = coverage(layer);
  out.push(`| ${layer} | ${t ? t.total : 'not run'} | ${t?.passed ?? '-'} | ${t?.failed ?? '-'} | ${t?.skipped ?? '-'} | ${c ? ratio(c.line) : 'unmeasured'} | ${c ? ratio(c.branch) : 'unmeasured'} |`);
}
const s = coverage('service');
out.push(`| **service (unit + integration union)** | | | | | ${s ? ratio(s.line) : 'unmeasured'} | ${s ? ratio(s.branch) : 'unmeasured'} |`, '',
  'Scope: every file in `src/audit/src` (`c8 --all`, TypeScript via source maps; tests excluded). Integration tests spawn the compiled server against a temporary SQLite file; child-process coverage is included.',
  'For a file a layer never loads (server.ts in the unit layer), V8 reports its lines but only one branch, so unit branch totals are lower than the service total. Compare branch % using the service row.',
  'Baseline on demo-baseline: no harness / unmeasured.');
console.log(out.join('\n'));
