// =============================================================================
// app.js – Invoice Reviewer UI
// Talks to:  GET  /invoices          → GetInvoiceHandler
//            GET  /invoices?id=XXX   → GetInvoiceHandler
//            POST /invoices/review   → ApproveRejectHandler
// =============================================================================

// ── DOM helpers ───────────────────────────────────────────────────────────────
const $  = id  => document.getElementById(id);
const show = id => $(id) && $(id).classList.remove('hidden');
const hide = id => $(id) && $(id).classList.add('hidden');
const setText = (id, val) => { if ($(id)) $(id).textContent = val ?? '—'; };
const setHTML = (id, val) => { if ($(id)) $(id).innerHTML  = val ?? '—'; };

function showErr(msg) {
  const el = $('err');
  if (!el) return;
  el.textContent = msg;
  show('err');
}
function clearErr() { hide('err'); }

// ── Badge helpers ─────────────────────────────────────────────────────────────
function aiBadge(status) {
  const map = {
    APPROVED:        '<span class="badge badge-approved">APPROVED</span>',
    REVIEW_REQUIRED: '<span class="badge badge-review">REVIEW REQUIRED</span>',
    DUPLICATE:       '<span class="badge badge-duplicate">DUPLICATE</span>',
  };
  return map[status] || `<span class="badge badge-unknown">${status ?? '—'}</span>`;
}

function humanBadge(decision) {
  if (!decision || decision === 'PENDING' || decision === 'undefined') {
    return '<span class="badge badge-pending">PENDING</span>';
  }
  if (decision === 'APPROVED') return '<span class="badge badge-human-approved">✅ APPROVED</span>';
  if (decision === 'REJECTED') return '<span class="badge badge-human-rejected">❌ REJECTED</span>';
  return `<span class="badge badge-unknown">${decision}</span>`;
}

function riskBadge(risk) {
  const map = {
    LOW:    '<span class="badge badge-low">LOW</span>',
    MEDIUM: '<span class="badge badge-medium">MEDIUM</span>',
    HIGH:   '<span class="badge badge-high">HIGH</span>',
  };
  return map[risk] || `<span class="badge badge-unknown">${risk ?? '—'}</span>`;
}

function confBar(raw) {
  const pct = parseFloat(raw);
  if (isNaN(pct)) return '<span style="color:#94a3b8">—</span>';
  const color = pct >= 95 ? '#22c55e' : pct >= 80 ? '#f59e0b' : '#ef4444';
  return `
    <div class="conf-wrap">
      <div class="conf-track">
        <div class="conf-fill" style="width:${Math.min(pct,100)}%;background:${color}"></div>
      </div>
      <span class="conf-label">${pct.toFixed(1)}%</span>
    </div>`;
}

// ── API calls ─────────────────────────────────────────────────────────────────
async function apiFetch(path) {
  const res  = await fetch(API_BASE_URL + path);
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
}

async function apiPost(path, body) {
  const res  = await fetch(API_BASE_URL + path, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify(body),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
}

// ── Dashboard ─────────────────────────────────────────────────────────────────
async function loadDashboard() {
  clearErr();
  show('loading');
  try {
    const invoices = await apiFetch('/invoices');
    hide('loading');
    renderStats(invoices);
    renderDashboardTable(invoices);
  } catch (e) {
    hide('loading');
    showErr('Could not load invoices. Check config.js → ' + e.message);
  }
}

function renderStats(invoices) {
  const c = { APPROVED: 0, REVIEW_REQUIRED: 0, DUPLICATE: 0 };
  invoices.forEach(i => { if (c[i.validationStatus] !== undefined) c[i.validationStatus]++; });
  setText('cnt-approved',  c.APPROVED);
  setText('cnt-review',    c.REVIEW_REQUIRED);
  setText('cnt-duplicate', c.DUPLICATE);
  setText('cnt-total',     invoices.length);
}

function renderDashboardTable(invoices) {
  const tbody = $('tbody');
  if (!invoices.length) {
    tbody.innerHTML = '<tr><td colspan="9" class="empty-row">No invoices found.</td></tr>';
    return;
  }
  tbody.innerHTML = invoices.map(inv => `
    <tr>
      <td><code>${inv.invoiceId ?? '—'}</code></td>
      <td>${inv.vendorName ?? '—'}</td>
      <td>${inv.invoiceDate ?? '—'}</td>
      <td><strong>${inv.total ?? '—'}</strong></td>
      <td>${riskBadge(inv.risk)}</td>
      <td>${aiBadge(inv.validationStatus)}</td>
      <td>${humanBadge(inv.reviewDecision)}</td>
      <td>${confBar(inv.totalConfidence)}</td>
      <td>
        ${inv.validationStatus === 'REVIEW_REQUIRED' && !inv.reviewDecision
          ? `<a href="review.html?id=${encodeURIComponent(inv.invoiceId)}" class="btn btn-sm btn-open">Review</a>`
          : '<span style="color:#94a3b8;font-size:.8rem">—</span>'
        }
      </td>
    </tr>`).join('');
}

// ── Review Queue ──────────────────────────────────────────────────────────────
async function loadQueue() {
  clearErr();
  show('loading');
  try {
    const all    = await apiFetch('/invoices');
    const queue  = all.filter(i => i.validationStatus === 'REVIEW_REQUIRED');
    hide('loading');
    renderQueueTable(queue);

    // Auto-open invoice from ?id= URL param
    const id = new URLSearchParams(window.location.search).get('id');
    if (id) {
      const inv = all.find(i => i.invoiceId === id) || await apiFetch('/invoices?id=' + encodeURIComponent(id));
      if (inv) openDetail(inv);
    }
  } catch (e) {
    hide('loading');
    showErr('Could not load queue. Check config.js → ' + e.message);
  }
}

function renderQueueTable(invoices) {
  const tbody = $('queue-tbody');
  if (!invoices.length) {
    tbody.innerHTML = '<tr><td colspan="7" class="empty-row">🎉 No invoices awaiting review.</td></tr>';
    return;
  }
  tbody.innerHTML = invoices.map(inv => `
    <tr>
      <td><code>${inv.invoiceId ?? '—'}</code></td>
      <td>${inv.vendorName ?? '—'}</td>
      <td><strong>${inv.total ?? '—'}</strong></td>
      <td>${confBar(inv.totalConfidence)}</td>
      <td>${riskBadge(inv.risk)}</td>
      <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap"
          title="${inv.comments ?? ''}">${inv.comments ?? '—'}</td>
      <td>
        <button class="btn btn-sm btn-open"
                onclick='openDetail(${JSON.stringify(inv)})'>Open</button>
      </td>
    </tr>`).join('');
}

// ── Detail Panel ──────────────────────────────────────────────────────────────
function openDetail(inv) {
  // Basic fields
  setText('d-id',      inv.invoiceId);
  setText('d-vendor',  inv.vendorName);
  setText('d-date',    inv.invoiceDate);
  setText('d-subtotal',inv.subtotal);
  setText('d-total',   inv.total);
  setText('d-comments',inv.comments);

  // Missing fields
  const mf = Array.isArray(inv.missingFields)
    ? (inv.missingFields.length ? inv.missingFields.join(', ') : 'None')
    : (inv.missingFields || 'None');
  setText('d-missing', mf);

  // Confidence bars
  setHTML('d-conf', confBar(inv.totalConfidence));
  setHTML('d-avg',  confBar(inv.avgConfidence));

  // AI verdict
  setHTML('d-ai-status', aiBadge(inv.validationStatus));
  setHTML('d-risk',      riskBadge(inv.risk));

  // Human decision (may be absent)
  setHTML('d-human-decision', humanBadge(inv.reviewDecision));
  setText('d-reviewed-by', inv.reviewedBy || '—');
  setText('d-reviewed-at', inv.reviewedAt
    ? new Date(inv.reviewedAt).toLocaleString()
    : '—');

  // Show or hide the decision form
  const decided = inv.reviewDecision && inv.reviewDecision !== 'undefined';
  if (decided) {
    hide('decision-form');
    const done = $('decision-done');
    done.className = 'decision-done ' + (inv.reviewDecision === 'APPROVED' ? 'ok' : 'bad');
    done.textContent = inv.reviewDecision === 'APPROVED'
      ? '✅ This invoice was approved by ' + (inv.reviewedBy || 'a reviewer') + '.'
      : '❌ This invoice was rejected by ' + (inv.reviewedBy || 'a reviewer') + '.';
    show('decision-done');
  } else {
    show('decision-form');
    hide('decision-done');
    // Clear previous input
    if ($('r-email')) $('r-email').value = '';
    if ($('r-note'))  $('r-note').value  = '';
  }

  // Store invoiceId for submitDecision
  $('detail-card').dataset.invoiceId = inv.invoiceId;
  $('detail-title').textContent = 'Invoice: ' + inv.invoiceId;
  show('detail-card');
  $('detail-card').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function closeDetail() { hide('detail-card'); }

// ── Submit Decision ───────────────────────────────────────────────────────────
async function submitDecision(decision) {
  const invoiceId = $('detail-card').dataset.invoiceId;
  const reviewer  = $('r-email').value.trim();
  const note      = $('r-note').value.trim();

  if (!reviewer) {
    alert('Please enter your email address.');
    $('r-email').focus();
    return;
  }

  // Lock buttons
  document.querySelectorAll('.decision-btns .btn')
    .forEach(b => b.disabled = true);

  try {
    await apiPost('/invoices/review', { invoiceId, decision, reviewer, reason: note });

    // Show result inline
    hide('decision-form');
    const done = $('decision-done');
    done.className = 'decision-done ' + (decision === 'APPROVED' ? 'ok' : 'bad');
    done.textContent = decision === 'APPROVED'
      ? `✅ Invoice ${invoiceId} approved. Confirmation email sent.`
      : `❌ Invoice ${invoiceId} rejected. Confirmation email sent.`;
    show('decision-done');

    // Refresh queue after short delay
    setTimeout(loadQueue, 1200);

  } catch (e) {
    alert('Error submitting decision: ' + e.message);
    document.querySelectorAll('.decision-btns .btn')
      .forEach(b => b.disabled = false);
  }
}
