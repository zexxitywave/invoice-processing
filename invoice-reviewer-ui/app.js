// =============================================================================
// app.js – Invoice Reviewer UI (Single Page App)
// =============================================================================

const $      = id  => document.getElementById(id);
const show   = id  => $(id) && $(id).classList.remove('hidden');
const hide   = id  => $(id) && $(id).classList.add('hidden');
const setText = (id, val) => { if ($(id)) $(id).textContent = val ?? '—'; };
const setHTML = (id, val) => { if ($(id)) $(id).innerHTML  = val ?? '—'; };

// ── Badges & bars ─────────────────────────────────────────────────────────────
function aiBadge(s) {
  return { APPROVED:'<span class="badge badge-approved">APPROVED</span>',
           REVIEW_REQUIRED:'<span class="badge badge-review">REVIEW REQUIRED</span>',
           DUPLICATE:'<span class="badge badge-duplicate">DUPLICATE</span>' }[s]
      || `<span class="badge badge-unknown">${s??'—'}</span>`;
}
function humanBadge(d) {
  if (!d||d==='PENDING'||d==='undefined') return '<span class="badge badge-pending">PENDING</span>';
  if (d==='APPROVED') return '<span class="badge badge-human-approved">✅ APPROVED</span>';
  if (d==='REJECTED') return '<span class="badge badge-human-rejected">❌ REJECTED</span>';
  return `<span class="badge badge-unknown">${d}</span>`;
}
function riskBadge(r) {
  return { LOW:'<span class="badge badge-low">LOW</span>',
           MEDIUM:'<span class="badge badge-medium">MEDIUM</span>',
           HIGH:'<span class="badge badge-high">HIGH</span>' }[r]
      || `<span class="badge badge-unknown">${r??'—'}</span>`;
}
function confBar(raw) {
  const pct = parseFloat(raw);
  if (isNaN(pct)) return '<span style="color:#94a3b8">—</span>';
  const c = pct>=95?'#22c55e':pct>=80?'#f59e0b':'#ef4444';
  return `<div class="conf-wrap"><div class="conf-track">
    <div class="conf-fill" style="width:${Math.min(pct,100)}%;background:${c}"></div>
  </div><span class="conf-label">${pct.toFixed(1)}%</span></div>`;
}
function confText(raw) {
  const pct = parseFloat(raw);
  if (isNaN(pct)) return '—';
  const c = pct>=95?'#15803d':pct>=80?'#b45309':'#b91c1c';
  return `<span style="color:${c};font-weight:600">${pct.toFixed(1)}%</span>`;
}

// ── API ───────────────────────────────────────────────────────────────────────
async function apiFetch(path) {
  const res  = await fetch(API_BASE_URL + path);
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
}
async function apiPost(path, body) {
  const res  = await fetch(API_BASE_URL + path, {
    method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
}

// ══════════════════════════════════════════════════════════════════════════════
// DASHBOARD
// ══════════════════════════════════════════════════════════════════════════════
async function loadDashboard() {
  hide('dash-err'); show('dash-loading');
  try {
    const invoices = await apiFetch('/invoices');
    hide('dash-loading');
    const c = { APPROVED:0, REVIEW_REQUIRED:0, DUPLICATE:0 };
    invoices.forEach(i => { if (c[i.validationStatus]!==undefined) c[i.validationStatus]++; });
    setText('cnt-approved',  c.APPROVED);
    setText('cnt-review',    c.REVIEW_REQUIRED);
    setText('cnt-duplicate', c.DUPLICATE);
    setText('cnt-total',     invoices.length);

    const tbody = $('dash-tbody');
    if (!invoices.length) {
      tbody.innerHTML = '<tr><td colspan="9" class="empty-row">No invoices found.</td></tr>';
      return;
    }
    tbody.innerHTML = invoices.map(inv => `<tr>
      <td><code>${inv.invoiceId??'—'}</code></td>
      <td>${inv.vendorName??'—'}</td>
      <td>${inv.invoiceDate??'—'}</td>
      <td><strong>${inv.total??'—'}</strong></td>
      <td>${riskBadge(inv.risk)}</td>
      <td>${aiBadge(inv.validationStatus)}</td>
      <td>${humanBadge(inv.reviewDecision)}</td>
      <td>${confBar(inv.totalConfidence)}</td>
      <td>${inv.validationStatus==='REVIEW_REQUIRED'&&!inv.reviewDecision
        ? `<button class="btn btn-sm btn-open" onclick='openReviewFromDash(${JSON.stringify(JSON.stringify(inv))})'>Review</button>`
        : '<span style="color:#94a3b8;font-size:.8rem">—</span>'}</td>
    </tr>`).join('');
  } catch(e) {
    hide('dash-loading');
    const el=$('dash-err'); if(el){el.textContent='Error: '+e.message;show('dash-err');}
  }
}

function openReviewFromDash(jsonStr) {
  showPage('review');
  setTimeout(() => openDetail(JSON.parse(jsonStr)), 100);
}

// ══════════════════════════════════════════════════════════════════════════════
// REVIEW QUEUE
// ══════════════════════════════════════════════════════════════════════════════
async function loadQueue() {
  hide('rev-err'); show('rev-loading');
  try {
    const all   = await apiFetch('/invoices');
    const queue = all.filter(i => i.validationStatus==='REVIEW_REQUIRED');
    hide('rev-loading');

    const tbody = $('queue-tbody');
    if (!queue.length) {
      tbody.innerHTML='<tr><td colspan="7" class="empty-row">🎉 No invoices awaiting review.</td></tr>';
      return;
    }
    tbody.innerHTML = queue.map(inv => `<tr>
      <td><code>${inv.invoiceId??'—'}</code></td>
      <td>${inv.vendorName??'—'}</td>
      <td><strong>${inv.total??'—'}</strong></td>
      <td>${confBar(inv.totalConfidence)}</td>
      <td>${riskBadge(inv.risk)}</td>
      <td style="max-width:180px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap"
          title="${inv.comments??''}">${inv.comments??'—'}</td>
      <td><button class="btn btn-sm btn-open"
          onclick='openDetail(${JSON.stringify(inv)})'>Open</button></td>
    </tr>`).join('');
  } catch(e) {
    hide('rev-loading');
    const el=$('rev-err'); if(el){el.textContent='Error: '+e.message;show('rev-err');}
  }
}

function openDetail(inv) {
  setText('d-id',      inv.invoiceId);
  setText('d-vendor',  inv.vendorName);
  setText('d-date',    inv.invoiceDate);
  setText('d-subtotal',inv.subtotal);
  setText('d-total',   inv.total);
  setText('d-comments',inv.comments);
  const mf = Array.isArray(inv.missingFields)
    ? (inv.missingFields.length?inv.missingFields.join(', '):'None')
    : (inv.missingFields||'None');
  setText('d-missing', mf);
  setHTML('d-conf',           confBar(inv.totalConfidence));
  setHTML('d-avg',            confBar(inv.avgConfidence));
  setHTML('d-ai-status',      aiBadge(inv.validationStatus));
  setHTML('d-risk',           riskBadge(inv.risk));
  setHTML('d-human-decision', humanBadge(inv.reviewDecision));
  setText('d-reviewed-by', inv.reviewedBy||'—');
  setText('d-reviewed-at', inv.reviewedAt?new Date(inv.reviewedAt).toLocaleString():'—');

  const decided = inv.reviewDecision && inv.reviewDecision!=='undefined';
  if (decided) {
    hide('decision-form');
    const done=$('decision-done');
    done.className='decision-done '+(inv.reviewDecision==='APPROVED'?'ok':'bad');
    done.textContent=inv.reviewDecision==='APPROVED'
      ?'✅ Approved by '+(inv.reviewedBy||'reviewer')+'.'
      :'❌ Rejected by '+(inv.reviewedBy||'reviewer')+'.';
    show('decision-done');
  } else {
    show('decision-form'); hide('decision-done');
    if($('r-email'))$('r-email').value='';
    if($('r-note')) $('r-note').value='';
  }
  $('detail-card').dataset.invoiceId=inv.invoiceId;
  $('detail-title').textContent='Invoice: '+inv.invoiceId;
  show('detail-card');
  $('detail-card').scrollIntoView({behavior:'smooth',block:'start'});
}

function closeDetail() { hide('detail-card'); }

async function submitDecision(decision) {
  const invoiceId = $('detail-card').dataset.invoiceId;
  const reviewer  = $('r-email').value.trim();
  const note      = $('r-note').value.trim();
  if (!reviewer) { alert('Please enter your email.'); $('r-email').focus(); return; }
  document.querySelectorAll('.decision-btns .btn').forEach(b=>b.disabled=true);
  try {
    await apiPost('/invoices/review', {invoiceId,decision,reviewer,reason:note});
    hide('decision-form');
    const done=$('decision-done');
    done.className='decision-done '+(decision==='APPROVED'?'ok':'bad');
    done.textContent=decision==='APPROVED'
      ?`✅ Invoice ${invoiceId} approved. Email sent.`
      :`❌ Invoice ${invoiceId} rejected. Email sent.`;
    show('decision-done');
    setTimeout(loadQueue,1200);
  } catch(e) {
    alert('Error: '+e.message);
    document.querySelectorAll('.decision-btns .btn').forEach(b=>b.disabled=false);
  }
}

// ══════════════════════════════════════════════════════════════════════════════
// AUDIT REPORT
// ══════════════════════════════════════════════════════════════════════════════
let _auditAll = [];

async function loadAudit() {
  hide('audit-err'); show('audit-loading');
  try {
    const data = await apiFetch('/invoices');
    hide('audit-loading');
    _auditAll = Array.isArray(data)?data:[];
    renderAuditSummary(_auditAll);
    renderAuditTable(_auditAll);
    setText('report-meta','Generated: '+new Date().toLocaleString()+'  ·  '+_auditAll.length+' records');
  } catch(e) {
    hide('audit-loading');
    const el=$('audit-err'); if(el){el.textContent='Error: '+e.message;show('audit-err');}
  }
}

function renderAuditSummary(invoices) {
  const ai={APPROVED:0,REVIEW_REQUIRED:0,DUPLICATE:0}, hu={APPROVED:0,REJECTED:0};
  let tot=0, cnt=0;
  invoices.forEach(inv=>{
    if(ai[inv.validationStatus]!==undefined)ai[inv.validationStatus]++;
    if(inv.reviewDecision==='APPROVED')hu.APPROVED++;
    if(inv.reviewDecision==='REJECTED')hu.REJECTED++;
    const c=parseFloat(inv.avgConfidence||inv.totalConfidence);
    if(!isNaN(c)){tot+=c;cnt++;}
  });
  setText('s-total',    invoices.length);
  setText('s-approved', ai.APPROVED);
  setText('s-review',   ai.REVIEW_REQUIRED);
  setText('s-duplicate',ai.DUPLICATE);
  setText('s-h-approved',hu.APPROVED);
  setText('s-h-rejected',hu.REJECTED);
  setText('s-avg-conf', cnt?(tot/cnt).toFixed(1)+'%':'—');
}

function applyAuditFilter() {
  const status=$('f-status').value, risk=$('f-risk').value;
  const search=$('f-search').value.toLowerCase();
  const filtered=_auditAll.filter(inv=>{
    if(status&&inv.validationStatus!==status)return false;
    if(risk  &&inv.risk!==risk)return false;
    if(search&&!((inv.vendorName||'')+(inv.invoiceId||'')).toLowerCase().includes(search))return false;
    return true;
  });
  renderAuditTable(filtered);
}

function renderAuditTable(invoices) {
  const tbody=$('audit-tbody'), tfoot=$('audit-tfoot');
  if(!invoices.length){
    tbody.innerHTML='<tr><td colspan="10" class="empty-row">No records match filters.</td></tr>';
    tfoot.innerHTML=''; return;
  }
  let totalAmt=0;
  tbody.innerHTML=invoices.map((inv,idx)=>{
    const t=parseFloat((inv.total||'0').replace(/[^0-9.]/g,''));
    if(!isNaN(t))totalAmt+=t;
    const aiC=inv.validationStatus==='APPROVED'?'#15803d':inv.validationStatus==='REVIEW_REQUIRED'?'#b45309':'#6d28d9';
    const huC=inv.reviewDecision==='APPROVED'?'#15803d':inv.reviewDecision==='REJECTED'?'#be123c':'#94a3b8';
    return `<tr>
      <td style="color:#94a3b8">${idx+1}</td>
      <td><code>${inv.invoiceId??'—'}</code></td>
      <td>${inv.vendorName??'—'}</td>
      <td>${inv.invoiceDate??'—'}</td>
      <td><strong>${inv.total??'—'}</strong></td>
      <td>${riskBadge(inv.risk)}</td>
      <td><span style="color:${aiC};font-weight:700;font-size:.8rem">${inv.validationStatus??'—'}</span></td>
      <td><span style="color:${huC};font-weight:700;font-size:.8rem">${inv.reviewDecision??'PENDING'}</span></td>
      <td>${confText(inv.totalConfidence)}</td>
      <td style="max-width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:.8rem"
          title="${inv.comments??''}">${inv.comments??'—'}</td>
    </tr>`;
  }).join('');
  tfoot.innerHTML=`<tr>
    <td colspan="4" style="text-align:right;padding:.7rem 1rem">Total (filtered):</td>
    <td>₹${totalAmt.toLocaleString('en-IN',{minimumFractionDigits:2})}</td>
    <td colspan="5"></td>
  </tr>`;
}

function exportCSV() {
  const hdr=['Invoice ID','Vendor','Date','Total','Risk','AI Status','Human Decision','TOTAL Conf.','Avg Conf.','Comments'];
  const rows=_auditAll.map(inv=>[
    inv.invoiceId,inv.vendorName,inv.invoiceDate,inv.total,inv.risk,
    inv.validationStatus,inv.reviewDecision??'PENDING',
    inv.totalConfidence,inv.avgConfidence,inv.comments
  ].map(v=>'"'+String(v??'').replace(/"/g,'""')+'"').join(','));
  const csv=[hdr.join(','),...rows].join('\n');
  const a=document.createElement('a');
  a.href=URL.createObjectURL(new Blob([csv],{type:'text/csv'}));
  a.download='audit-report-'+new Date().toISOString().slice(0,10)+'.csv';
  a.click();
}

// ══════════════════════════════════════════════════════════════════════════════
// UPLOAD
// ══════════════════════════════════════════════════════════════════════════════
let fileQueue=[], nextId=0;

function onDragOver(e) { e.preventDefault(); $('drop-zone').classList.add('dragover'); }
function onDragLeave()  { $('drop-zone').classList.remove('dragover'); }
function onDrop(e)      { e.preventDefault(); $('drop-zone').classList.remove('dragover'); onFilesSelected(e.dataTransfer.files); }

function onFilesSelected(fileList) {
  Array.from(fileList).forEach(f=>{
    if((!f.type.includes('pdf')&&!f.name.endsWith('.pdf'))||f.size>10*1024*1024)return;
    if(fileQueue.find(e=>e.file.name===f.name&&e.file.size===f.size))return;
    fileQueue.push({file:f,id:nextId++,status:'pending',pct:0,msg:'Ready'});
  });
  renderQueue();
}

function renderQueue() {
  const container=$('file-queue'), btn=$('upload-btn');
  const count=$('queue-count'), dz=$('drop-zone');
  if(!fileQueue.length){
    container.innerHTML=''; if(btn)btn.disabled=true;
    if(count)count.textContent=''; dz&&dz.classList.remove('has-files'); return;
  }
  dz&&dz.classList.add('has-files');
  if(count)count.textContent=fileQueue.length+' file'+(fileQueue.length>1?'s':'')+' selected';
  if(btn)btn.disabled=!fileQueue.some(e=>e.status==='pending');
  container.innerHTML=fileQueue.map(e=>`
    <div class="file-row" id="row-${e.id}">
      <span>📄</span>
      <span class="f-name" title="${e.file.name}">${e.file.name}</span>
      <span class="f-size">${fmtBytes(e.file.size)}</span>
      <div class="f-bar-wrap"><div class="f-bar-fill" id="bar-${e.id}" style="width:${e.pct}%"></div></div>
      <span class="f-status status-${e.status}" id="fstatus-${e.id}">${e.msg}</span>
      ${e.status==='pending'?`<span class="f-remove" onclick="removeFile(${e.id})">✕</span>`:'<span style="width:1rem"></span>'}
    </div>`).join('');
}

function removeFile(id){ fileQueue=fileQueue.filter(e=>e.id!==id); renderQueue(); }
function clearAll()    { fileQueue=[]; $('file-input').value=''; renderQueue(); const r=$('upload-result'); if(r)r.className='upload-result'; }

async function uploadAll() {
  const pending=fileQueue.filter(e=>e.status==='pending');
  if(!pending.length)return;
  $('upload-btn').disabled=true;
  const r=$('upload-result'); if(r)r.className='upload-result';
  await Promise.allSettled(pending.map(e=>uploadOne(e)));
  const done=fileQueue.filter(e=>e.status==='done').length;
  const errs=fileQueue.filter(e=>e.status==='error').length;
  if(r){
    r.style.whiteSpace='pre-line';
    if(errs===0){ r.className='upload-result ok'; r.textContent=`✅ ${done} invoice${done>1?'s':''} uploaded!\nResults appear on Dashboard in ~30 seconds.`; }
    else        { r.className='upload-result err'; r.textContent=`⚠️ ${done} uploaded, ${errs} failed.`; }
  }
  if($('upload-btn'))$('upload-btn').disabled=!fileQueue.some(e=>e.status==='pending');
}

async function uploadOne(entry) {
  updEntry(entry.id,'uploading',5,'Getting URL…');
  try {
    const res=await fetch(API_BASE_URL+'/invoices/upload-url',{
      method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({fileName:entry.file.name})
    });
    if(!res.ok){const e=await res.json().catch(()=>({}));throw new Error(e.error||'HTTP '+res.status);}
    const {uploadUrl}=await res.json();
    updEntry(entry.id,'uploading',20,'Uploading…');
    await new Promise((resolve,reject)=>{
      const xhr=new XMLHttpRequest();
      xhr.upload.addEventListener('progress',e=>{if(e.lengthComputable)updEntry(entry.id,'uploading',20+Math.round(e.loaded/e.total*78),Math.round(e.loaded/e.total*100)+'%');});
      xhr.addEventListener('load',()=>xhr.status<300?resolve():reject(new Error('S3 '+xhr.status)));
      xhr.addEventListener('error',()=>reject(new Error('Network error')));
      xhr.open('PUT',uploadUrl); xhr.setRequestHeader('Content-Type','application/pdf'); xhr.send(entry.file);
    });
    updEntry(entry.id,'done',100,'✅ Done');
  } catch(e){ updEntry(entry.id,'error',0,'❌ '+e.message.substring(0,25)); }
}

function updEntry(id,status,pct,msg) {
  const e=fileQueue.find(e=>e.id===id); if(!e)return;
  e.status=status; e.pct=pct; e.msg=msg;
  const b=$('bar-'+id), s=$('fstatus-'+id);
  if(b)b.style.width=pct+'%';
  if(s){s.textContent=msg; s.className='f-status status-'+status;}
}

function fmtBytes(b){ return b<1024?b+' B':b<1048576?(b/1024).toFixed(1)+' KB':(b/1048576).toFixed(2)+' MB'; }
