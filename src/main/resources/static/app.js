const form = document.getElementById('evaluate-form');
const modeRadios = form.querySelectorAll('input[name="mode"]');
const filePanel = document.getElementById('file-panel');
const samplePanel = document.getElementById('sample-panel');
const fileInput = document.getElementById('statement-file');
const pickFileBtn = document.getElementById('pick-file');
const uploadZone = document.querySelector('.upload-zone');
const selectedFileEl = document.getElementById('selected-file');
const sampleLabel = document.getElementById('sample-label');
const identityBanner = document.getElementById('identity-banner');
const statusEl = document.getElementById('status');
const submitBtn = document.getElementById('submit-btn');
const submitSpinner = submitBtn.querySelector('.submit-spinner');
const results = document.getElementById('results');
const resultsEmpty = document.getElementById('results-empty');
const ringFill = document.getElementById('ring-fill');
const creditScoreEl = document.getElementById('credit-score');
const verdictBadge = document.getElementById('verdict-badge');
const verdictReason = document.getElementById('verdict-reason');
const findingsEl = document.getElementById('findings');
const rawJson = document.getElementById('raw-json');
const auditBlock = document.getElementById('audit-block');
const auditMeta = document.getElementById('audit-meta');
const reconstructBtn = document.getElementById('reconstruct-btn');
const reconstructSteps = document.getElementById('reconstruct-steps');

let selectedSample = null;
let lastApplicationId = null;

const RING_CIRCUMFERENCE = 327;

function kes(value) {
  return `KES ${Number(value || 0).toLocaleString('en-KE')}`;
}

function currentMode() {
  return [...modeRadios].find((r) => r.checked)?.value || 'file';
}

function syncModeUi() {
  const fileMode = currentMode() === 'file';
  filePanel.classList.toggle('hidden', !fileMode);
  samplePanel.classList.toggle('hidden', fileMode);
}

function clearIdentity() {
  form.applicantName.value = '';
  form.msisdn.value = '';
  identityBanner.classList.add('hidden');
  identityBanner.textContent = '';
}

function applyIdentity(preview) {
  if (preview.customerName) form.applicantName.value = preview.customerName;
  if (preview.msisdn) form.msisdn.value = preview.msisdn;

  const parts = [];
  if (preview.customerName) parts.push(`<strong>${preview.customerName}</strong>`);
  if (preview.msisdn) parts.push(preview.msisdn);
  if (preview.transactionCount) parts.push(`${preview.transactionCount} transactions`);
  if (preview.periodStart && preview.periodEnd) {
    parts.push(`${preview.periodStart} → ${preview.periodEnd}`);
  }

  if (parts.length) {
    identityBanner.innerHTML = `Detected from statement: ${parts.join(' · ')}`;
    identityBanner.classList.remove('hidden');
  }
}

async function parseStatementFile(file, password) {
  const body = new FormData();
  body.append('statement', file, file.name);
  if (password) body.append('statementPassword', password);

  const response = await fetch('/api/v1/parse', { method: 'POST', body });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || 'Failed to read statement');
  return data;
}

modeRadios.forEach((radio) => {
  radio.addEventListener('change', () => {
    syncModeUi();
    selectedSample = null;
    sampleLabel.textContent = 'Pick a sample above';
    document.querySelectorAll('.sample-card').forEach((c) => c.classList.remove('selected'));
    clearIdentity();
  });
});

pickFileBtn.addEventListener('click', () => fileInput.click());

async function onFileSelected(file) {
  if (!file) {
    selectedFileEl.textContent = 'No file selected';
    clearIdentity();
    return;
  }

  selectedFileEl.textContent = `${file.name} · ${Math.round(file.size / 1024)} KB`;
  statusEl.className = 'status';
  statusEl.textContent = 'Reading applicant details from statement…';

  try {
    const preview = await parseStatementFile(file, form.statementPassword.value);
    applyIdentity(preview);
    statusEl.className = 'status ok';
    statusEl.textContent = 'Applicant details loaded from statement.';
  } catch (err) {
    clearIdentity();
    statusEl.className = 'status error';
    statusEl.textContent = err.message || 'Could not parse statement header.';
  }
}

fileInput.addEventListener('change', () => onFileSelected(fileInput.files[0]));

uploadZone.addEventListener('dragover', (e) => {
  e.preventDefault();
  uploadZone.classList.add('dragover');
});

uploadZone.addEventListener('dragleave', () => uploadZone.classList.remove('dragover'));

uploadZone.addEventListener('drop', (e) => {
  e.preventDefault();
  uploadZone.classList.remove('dragover');
  const file = e.dataTransfer?.files?.[0];
  if (file) {
    fileInput.files = e.dataTransfer.files;
    form.querySelector('input[name="mode"][value="file"]').checked = true;
    syncModeUi();
    onFileSelected(file);
  }
});

form.statementPassword.addEventListener('change', () => {
  if (fileInput.files[0]) onFileSelected(fileInput.files[0]);
});

document.querySelectorAll('.sample-card').forEach((card) => {
  card.addEventListener('click', async () => {
    document.querySelectorAll('.sample-card').forEach((c) => c.classList.remove('selected'));
    card.classList.add('selected');
    selectedSample = card.dataset.sample;
    sampleLabel.textContent = `Selected: ${selectedSample}`;
    form.querySelector('input[name="mode"][value="sample"]').checked = true;
    syncModeUi();
    clearIdentity();
    statusEl.textContent = 'Sample CSV has no applicant header — using demo data only.';
  });
});

function setLoading(loading) {
  submitBtn.disabled = loading;
  submitSpinner.classList.toggle('hidden', !loading);
}

function animateScore(score) {
  const clamped = Math.max(0, Math.min(100, score));
  const offset = RING_CIRCUMFERENCE - (RING_CIRCUMFERENCE * clamped) / 100;
  ringFill.style.strokeDashoffset = String(offset);

  if (clamped >= 70) ringFill.style.stroke = 'var(--approve)';
  else if (clamped >= 40) ringFill.style.stroke = 'var(--refer)';
  else ringFill.style.stroke = 'var(--decline)';

  let current = 0;
  const step = Math.max(1, Math.round(clamped / 24));
  const timer = setInterval(() => {
    current = Math.min(clamped, current + step);
    creditScoreEl.textContent = String(current);
    if (current >= clamped) clearInterval(timer);
  }, 20);
}

function renderFindings(findings) {
  findingsEl.innerHTML = '';
  (findings || []).forEach((f) => {
    const li = document.createElement('li');
    li.className = `finding ${f.passed ? 'pass' : 'fail'}`;
    li.innerHTML = `
      <span class="finding-dot"></span>
      <div class="finding-body">
        <strong>${f.name}</strong>
        <span>${f.summary} · ${f.actual} (expected ${f.expected})</span>
      </div>
    `;
    findingsEl.appendChild(li);
  });
}

function renderDecision(data) {
  resultsEmpty.classList.add('hidden');
  results.classList.remove('hidden');

  if (data.applicantName) form.applicantName.value = data.applicantName;
  if (data.msisdn) form.msisdn.value = data.msisdn;

  animateScore(data.creditScore);

  const verdict = (data.verdict || '').toLowerCase();
  verdictBadge.textContent = data.verdict || '—';
  verdictBadge.className = `verdict-badge ${verdict}`;
  verdictReason.textContent = data.reason || '';

  document.getElementById('max-loan').textContent = kes(data.maxLoanKes);
  document.getElementById('repayment-cap').textContent = kes(data.monthlyRepaymentCapacityKes);
  document.getElementById('verified-inflow').textContent = kes(data.features?.monthlyVerifiedInflowKes);
  document.getElementById('net-surplus').textContent = kes(data.features?.monthlyNetSurplusKes);
  document.getElementById('tenure').textContent = `${data.features?.tenureMonths ?? 0} mo`;
  document.getElementById('requested-amount').textContent = kes(data.requestedAmountKes);

  renderFindings(data.findings);
  rawJson.textContent = JSON.stringify(data, null, 2);

  lastApplicationId = data.applicationId || null;
  auditBlock.classList.add('hidden');
  reconstructSteps.innerHTML = '';
  auditMeta.textContent = '';
}

function renderReconstruct(data) {
  auditBlock.classList.remove('hidden');
  const name = data.applicantName ? `${data.applicantName} · ` : '';
  auditMeta.textContent = `${name}${data.applicationId} · verdict ${data.verdict || '—'}`;
  reconstructSteps.innerHTML = '';

  (data.steps || []).forEach((step) => {
    const li = document.createElement('li');
    li.innerHTML = `<strong>${step.kind}</strong>${step.label || '—'}<span>${step.detail || ''}</span>`;
    reconstructSteps.appendChild(li);
  });
}

async function loadReconstruct(applicationId) {
  reconstructBtn.disabled = true;
  auditMeta.textContent = 'Loading FlexVertex audit trail…';

  try {
    const response = await fetch(`/api/v1/applications/${encodeURIComponent(applicationId)}/reconstruct`);
    const data = await response.json();
    if (!response.ok) {
      throw new Error(data.error || `Reconstruct failed (${response.status})`);
    }
    renderReconstruct(data);
  } catch (err) {
    auditMeta.textContent = err.message || 'Could not load audit trail.';
  } finally {
    reconstructBtn.disabled = false;
  }
}

reconstructBtn.addEventListener('click', () => {
  if (!lastApplicationId) {
    auditMeta.textContent = 'Run an evaluation first to generate an application ID.';
    auditBlock.classList.remove('hidden');
    return;
  }
  loadReconstruct(lastApplicationId);
});

async function buildFormData() {
  const body = new FormData();
  body.append('applicantName', form.applicantName.value || '');
  body.append('msisdn', form.msisdn.value || '');
  body.append('requestedAmountKes', form.requestedAmountKes.value);
  body.append('projectedMonthlyRepaymentKes', form.projectedMonthlyRepaymentKes.value);
  body.append('activeLoanCount', form.activeLoanCount.value);

  if (currentMode() === 'sample') {
    if (!selectedSample) throw new Error('Choose a sample statement first.');
    const res = await fetch(`/samples/${selectedSample}`);
    if (!res.ok) throw new Error('Sample file not found.');
    const text = await res.text();
    body.append('statement', new Blob([text], { type: 'text/csv' }), selectedSample);
    return body;
  }

  const file = fileInput.files[0];
  if (!file) throw new Error('Choose a CSV or PDF statement.');
  body.append('statement', file, file.name);
  const password = form.statementPassword.value;
  if (password) body.append('statementPassword', password);
  return body;
}

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  statusEl.className = 'status';
  statusEl.textContent = 'Parsing statement and running scorecard…';
  setLoading(true);

  try {
    const body = await buildFormData();
    const response = await fetch('/api/v1/evaluate', { method: 'POST', body });
    const data = await response.json();

    if (!response.ok) {
      throw new Error(data.error || `Request failed (${response.status})`);
    }

    renderDecision(data);
    statusEl.className = 'status ok';
    statusEl.textContent = data.eligible
      ? 'Eligible — review recommended loan and findings below.'
      : 'Not eligible — see failed rules below.';
  } catch (err) {
    statusEl.className = 'status error';
    statusEl.textContent = err.message || 'Something went wrong.';
  } finally {
    setLoading(false);
  }
});

syncModeUi();
