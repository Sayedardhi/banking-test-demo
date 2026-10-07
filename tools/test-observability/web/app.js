const $ = selector => document.querySelector(selector);
const layers = { unit: 'Unit tests', integration: 'Integration tests', e2e: 'End-to-end tests' };
const statuses = {
  passed: 'Passed', failed: 'Failed', blocked: 'Blocked', running: 'Running',
  no_harness: 'No harness', not_run: 'Not run', not_configured: 'Not configured',
  not_applicable: 'Not applicable', skipped: 'Skipped'
};
const state = { data: null, current: '', baseline: '', followBaseline: true, layer: 'unit', metric: 'line', evidence: null };
const dateFormat = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });

function escapeHtml(value) {
  const entities = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  return String(value ?? '').replace(/[&<>"']/g, character => entities[character]);
}

function setHtml(selector, html) {
  const element = $(selector);
  if (element.innerHTML !== html) element.innerHTML = html;
}

function formatDate(value) {
  return dateFormat.format(new Date(value));
}

function currentRun() {
  return state.data.runs.find(run => run.id === state.current) || state.data.runs[0];
}

function baselineRun() {
  return state.data.runs.find(run => run.id === state.baseline);
}

function totals(run, layer) {
  const counts = { passed: 0, failed: 0, skipped: 0, total: 0 };
  for (const service of run?.services || []) {
    for (const [name, result] of Object.entries(service.layers)) {
      if (layer && layer !== name) continue;
      for (const key of Object.keys(counts)) counts[key] += result.tests?.[key] || 0;
    }
  }
  return counts;
}

function statusLabel(result) {
  return `<span class="status" data-status="${escapeHtml(result.status)}">${escapeHtml(statuses[result.status] || result.status)}</span>`;
}

function coverageCell(coverage, metric = state.metric) {
  const value = coverage?.[metric];
  if (value?.percent == null) return '<span class="unmeasured">Unmeasured</span>';
  const width = Math.max(0, Math.min(100, value.percent));
  return `<div class="coverage-top"><span class="coverage-value">${escapeHtml(value.percent)}%</span><span class="coverage-count" title="Covered / total">${value.covered}/${value.total}</span></div><div class="track" aria-hidden="true"><span style="width:${width}%"></span></div>`;
}

function coverageDelta(current, baseline, hasBaseline) {
  if (!hasBaseline || current?.[state.metric]?.percent == null) return '<span class="delta">—</span>';
  if (baseline?.[state.metric]?.percent == null) return '<span class="delta-note">New measurement</span>';
  if (current.format !== baseline.format || current.scopeHash !== baseline.scopeHash) return '<span class="delta-note">Scope changed</span>';
  const difference = current[state.metric].percent - baseline[state.metric].percent;
  const direction = difference > 0 ? 'up' : difference < 0 ? 'down' : 'same';
  return `<span class="delta" data-direction="${direction}">${difference > 0 ? '+' : ''}${difference.toFixed(1)} pp</span>`;
}

function runMetadata(run) {
  if (!run) return 'Select a saved run';
  return `<code>${escapeHtml(run.commit.slice(0, 8))}</code> · ${escapeHtml(run.branch)}${run.dirty ? ' · modified' : ''}`;
}

function renderSelectors() {
  const options = state.data.runs.map(run => `<option value="${escapeHtml(run.id)}">${formatDate(run.startedAt)} · ${escapeHtml(run.commit.slice(0, 7))}${run.id === state.data.baseline ? ' · baseline' : ''}</option>`).join('');
  setHtml('#current', '<option value="">Latest run</option>' + options);
  setHtml('#baseline', '<option value="">No baseline</option>' + options);
  $('#current').value = state.current;
  $('#baseline').value = state.baseline;
}

function showNotice(message) {
  $('#notice').textContent = message;
  $('#notice').hidden = !message;
}

function renderSummary(run, baseline) {
  const counts = totals(run);
  const allResults = run.services.flatMap(service => Object.values(service.layers));
  const services = run.services.filter(service => service.id !== 'journeys');
  const measured = services.filter(service => service.layers.unit.coverage?.line?.percent != null).length;
  const gaps = allResults.filter(result => result.status === 'no_harness').length;
  const failures = allResults.filter(result => ['failed', 'blocked'].includes(result.status)).length;
  const increase = baseline ? counts.passed - totals(baseline).passed : 0;
  const change = increase > 0 ? `<span class="metric-change">+${increase}</span>` : '';
  const metric = (label, number, note) => `<div class="metric"><div class="metric-label">${label}</div><div class="metric-number">${number}</div><div class="metric-foot">${note}</div></div>`;
  setHtml('#metrics',
    metric('Passing tests', counts.passed + change, `${counts.failed} failed · ${counts.skipped} skipped`) +
    metric('Services measured', `${measured}<span class="denominator"> / ${services.length}</span>`, 'With unit coverage reports') +
    metric('Missing unit harnesses', gaps, 'Services without a unit suite') +
    metric('Failed or blocked suites', failures, failures ? 'Open the evidence to investigate' : 'Across executed suites')
  );
}

function renderTable(run, baseline) {
  const services = run.services.filter(service => service.layers[state.layer].status !== 'not_applicable');
  setHtml('#services', services.map(service => {
    const result = service.layers[state.layer];
    const prior = baseline?.services.find(item => item.id === service.id)?.layers[state.layer];
    const name = service.id === 'journeys' ? 'Application journeys' : service.id;
    const count = result.tests;
    const tests = count ? `<span class="tests-number">${count.passed}<span class="tests-note">${count.failed ? count.failed + ' failed' : 'passed'}${count.skipped ? ' · ' + count.skipped + ' skipped' : ''}</span></span>` : '<span class="unmeasured">—</span>';
    return `<tr>
      <td><button class="service-button" data-service="${escapeHtml(service.id)}">${escapeHtml(name)}</button><span class="language">${escapeHtml(service.language)}</span><span class="service-description">${escapeHtml(service.name)}</span></td>
      <td>${statusLabel(result)}</td><td>${tests}</td>
      <td class="coverage-cell baseline-coverage">${baseline ? coverageCell(prior?.coverage) : '<span class="unmeasured">—</span>'}</td>
      <td class="coverage-cell">${coverageCell(result.coverage)}</td>
      <td>${coverageDelta(result.coverage, prior?.coverage, !!baseline)}</td>
      <td><button class="evidence-button" data-service="${escapeHtml(service.id)}" aria-label="View ${escapeHtml(name)} evidence">↗</button></td>
    </tr>`;
  }).join(''));
  const count = totals(run, state.layer);
  $('#table-summary').textContent = `${services.length} ${state.layer === 'e2e' ? 'services / journeys' : 'services'} · ${count.total} tests reported · ${state.metric === 'line' ? 'Line' : 'Branch'} coverage`;
  for (const layer of Object.keys(layers)) $(`#${layer}-count`).textContent = totals(run, layer).total;
}

function render() {
  const run = currentRun();
  if (!run) {
    showNotice('No test runs yet. Open Run instructions to collect the first results.');
    return;
  }
  const baseline = baselineRun();
  setHtml('#baseline-meta', runMetadata(baseline));
  setHtml('#current-meta', runMetadata(run));
  const isBaseline = baseline?.id === run.id;
  setHtml('#run-state', `${run.finishedAt ? isBaseline ? 'Baseline saved' : 'Run complete' : 'Collection in progress'}<small>${escapeHtml(run.branch)}</small>`);
  let notice = '';
  if (run.sourceChangedDuringRun) notice = 'Source changed during this run. Re-run before comparing results.';
  else if (run.sourceFingerprint !== state.data.currentFingerprint) notice = 'Historical results · Your checkout has changed since this run. Collect again to measure current code.';
  else if (!run.finishedAt) notice = 'Collection in progress. Results update as each suite finishes.';
  else if (!baseline) notice = 'Choose a baseline to compare runs. Use Run instructions to save your before state.';
  showNotice(notice);
  renderSummary(run, baseline);
  renderTable(run, baseline);
  if (state.evidence) renderEvidence();
}

function renderEvidence() {
  const { runId, serviceId, layer } = state.evidence;
  const run = state.data.runs.find(item => item.id === runId);
  const service = run.services.find(item => item.id === serviceId);
  const result = service.layers[layer];
  $('#evidence-title').textContent = serviceId === 'journeys' ? 'Application journeys' : serviceId;
  $('#evidence-meta').textContent = `${layers[layer]} · ${run.commit.slice(0, 8)} · ${formatDate(run.startedAt)}`;
  const summary = `<div class="evidence-summary">${statusLabel(result)}${result.durationSeconds != null ? `<span>${result.durationSeconds}s</span>` : ''}${result.exitCode != null ? `<span>Exit ${result.exitCode}</span>` : ''}</div>`;
  const description = result.description ? `<p class="evidence-note">${escapeHtml(result.description)}</p>` : '';
  const error = result.error ? `<p class="evidence-error">${escapeHtml(result.error)}</p>` : '';
  const coverage = result.coverage ? `<section class="drawer-section"><h3>Measured coverage</h3><div class="coverage-pair"><div><h4>Lines</h4>${coverageCell(result.coverage, 'line')}</div><div><h4>Branches</h4>${coverageCell(result.coverage, 'branch')}</div></div></section>` : '';
  const artifacts = (result.artifacts || []).map(path => `<a class="artifact" target="_blank" rel="noopener" href="/evidence/${path.split('/').map(encodeURIComponent).join('/')}"><span>${escapeHtml(path.split('/').pop())}</span><span aria-hidden="true">↗</span></a>`).join('');
  const reports = `<section class="drawer-section"><h3>Reports & logs</h3>${artifacts || '<p class="evidence-note">No execution artifacts for this suite.</p>'}</section>`;
  const command = result.command ? `<details class="drawer-section"><summary>Executed command</summary><pre>${escapeHtml(result.command.join(' '))}</pre></details>` : '';
  const cases = (result.tests?.cases || []).map(test => `<div class="test-case">${statusLabel(test)}<span class="test-name">${escapeHtml(test.name)}</span>${test.message ? `<pre>${escapeHtml(test.message)}</pre>` : ''}</div>`).join('');
  setHtml('#evidence-content', summary + description + error + coverage + reports + command + (cases ? `<section class="drawer-section"><h3>Test results · ${result.tests.total}</h3>${cases}</section>` : ''));
}

async function refresh() {
  try {
    const response = await fetch('/api/results', { cache: 'no-store' });
    if (!response.ok) throw new Error(`Results request failed: ${response.status}`);
    state.data = await response.json();
    if (state.followBaseline) state.baseline = state.data.baseline || '';
    renderSelectors();
    render();
    $('#connection').textContent = 'Collector connected';
    $('#connection').dataset.state = 'online';
    $('#updated').textContent = `Updated ${new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })} · every 5s`;
  } catch (error) {
    $('#connection').textContent = 'Connection lost';
    $('#connection').dataset.state = 'offline';
    showNotice('Cannot load results. Displayed evidence may be stale. Check that the dashboard server is running.');
    console.error(error);
  }
}

$('#current').addEventListener('change', event => {
  state.current = event.target.value;
  render();
});
$('#baseline').addEventListener('change', event => {
  state.baseline = event.target.value;
  state.followBaseline = false;
  render();
});
$('#layer-tabs').addEventListener('click', event => {
  const button = event.target.closest('button');
  if (!button) return;
  state.layer = button.dataset.layer;
  for (const tab of $('#layer-tabs').querySelectorAll('button')) tab.setAttribute('aria-pressed', tab === button);
  render();
});
$('#coverage-toggle').addEventListener('click', event => {
  const button = event.target.closest('button');
  if (!button) return;
  state.metric = button.dataset.metric;
  for (const option of $('#coverage-toggle').querySelectorAll('button')) option.setAttribute('aria-pressed', option === button);
  render();
});
$('#services').addEventListener('click', event => {
  const button = event.target.closest('button[data-service]');
  if (!button) return;
  state.evidence = { runId: currentRun().id, serviceId: button.dataset.service, layer: state.layer };
  renderEvidence();
  $('#evidence').showModal();
});
$('#close-evidence').addEventListener('click', () => $('#evidence').close());
$('#evidence').addEventListener('close', () => { state.evidence = null; });
$('#guide-toggle').addEventListener('click', () => {
  $('#guide').hidden = !$('#guide').hidden;
  $('#guide-toggle').setAttribute('aria-expanded', !$('#guide').hidden);
});
refresh();
setInterval(refresh, 5000);
