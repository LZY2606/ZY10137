'use strict';

const state = {
  overview: null,
  hypId: null,
  hypothesis: null,
  assignments: [],
  candidatesCache: {},
  activeTab: 'timeline',
};

const $ = (sel, root = document) => root.querySelector(sel);
const el = (tag, cls, text) => {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text !== undefined) n.textContent = text;
  return n;
};

async function api(path, options = {}) {
  const res = await fetch('/api' + path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  const text = await res.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch (_) { body = text; }
  if (!res.ok) {
    const err = new Error((body && body.error) || ('HTTP ' + res.status));
    err.status = res.status;
    err.body = body;
    throw err;
  }
  return body;
}

function toast(message, isError = false) {
  const t = $('#toast');
  t.textContent = message;
  t.className = 'toast' + (isError ? ' err' : '');
  clearTimeout(toast._timer);
  toast._timer = setTimeout(() => t.classList.add('hidden'), 6000);
}

function esc(s) {
  return String(s ?? '').replace(/[&<>"]/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

function fmt(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  return d.toLocaleString('ja-JP', { hour12: false, timeZone: 'Asia/Tokyo' });
}

function periodOf(epoch) {
  return state.overview.periods.find(p => epoch >= p.startEpoch && epoch < p.endEpoch);
}

function assignmentFor(obsId) {
  return state.assignments.find(a => a.observationId === obsId && a.decision === 'CONFIRMED');
}

function denialFor(obsId) {
  return state.assignments.find(a => a.observationId === obsId && a.decision === 'DENIED');
}

function indLabel(code) {
  if (!code) return '';
  if (code === '#NEW#') return '可能的新个体';
  const ind = state.overview.individuals.find(i => i.code === code);
  return ind ? `${ind.code} ${ind.label}` : code;
}

// ------------------------------------------------------------- bootstrap

async function boot() {
  state.overview = await api('/overview');
  renderHypSelect();
  $('#hypSelect').value = state.overview.defaultHypothesisId;
  await loadHypothesis(state.overview.defaultHypothesisId);
  bindEvents();
  renderRuleBar();
  renderTimeline();
}

function renderRuleBar() {
  const o = state.overview;
  $('#ruleBar').innerHTML =
    `调查时段为<strong>半开区间 [起, 止)</strong>，换班时刻观测只属于后一期；` +
    `同体不可在时空重叠或速度 &gt; ${o.unreachableSpeedKmh} km/h 不可达的两地点同现；` +
    `候选被降权/拒绝，但<strong>原始观测不会被删除</strong>。捕获矩阵：1=捕获，0=有调查未捕获，NA=未调查（缺失值）。`;
}

function renderHypSelect() {
  const sel = $('#hypSelect');
  sel.innerHTML = '';
  for (const h of state.overview.hypotheses) {
    const opt = el('option', null,
      `${h.lineageId} · ${h.title} · ${h.status === 'DRAFT' ? `草稿 rev${h.revision}` : `v${h.version}`}`);
    opt.value = h.id;
    sel.appendChild(opt);
  }
}

async function loadHypothesis(id) {
  state.hypId = id;
  const data = await api(`/hypotheses/${encodeURIComponent(id)}`);
  state.hypothesis = data.hypothesis;
  state.assignments = data.assignments;
  state.candidatesCache = {};
  $('#hypMeta').textContent =
    `${data.hypothesis.status === 'DRAFT' ? '草稿（基础版本 revision=' + data.hypothesis.revision + '）'
      : '已发布 v' + data.hypothesis.version}` +
    ` · 已确认 ${data.hypothesis.confirmedCount} · 否定 ${data.hypothesis.deniedCount}` +
    ` · 未决 ${data.hypothesis.unresolvedCount}`;
  $('#csvLink').href = `/api/hypotheses/${encodeURIComponent(id)}/capture-history.csv`;
  $('#btnPublish').disabled = data.hypothesis.status !== 'DRAFT';
  renderActiveTab();
}

function bindEvents() {
  $('#hypSelect').addEventListener('change', e => loadHypothesis(e.target.value));
  document.querySelectorAll('.tabs button').forEach(b =>
    b.addEventListener('click', () => {
      state.activeTab = b.dataset.tab;
      document.querySelectorAll('.tabs button').forEach(x =>
        x.classList.toggle('active', x === b));
      document.querySelectorAll('.tab').forEach(t =>
        t.classList.toggle('active', t.id === 'tab-' + b.dataset.tab));
      renderActiveTab();
    }));
  $('#btnPublish').addEventListener('click', publish);
  $('#btnNewDraft').addEventListener('click', newDraft);
  $('#modalCancel').addEventListener('click', closeModal);
}

function renderActiveTab() {
  if (state.activeTab === 'timeline') renderTimeline();
  if (state.activeTab === 'candidates') renderCandidates();
  if (state.activeTab === 'conflicts') renderConflicts();
  if (state.activeTab === 'capture') renderCapture();
  if (state.activeTab === 'import') renderImport();
}

// -------------------------------------------------------------- timeline

async function renderTimeline() {
  const root = $('#tab-timeline');
  root.innerHTML = '';
  const obs = await api('/observations');
  const periods = state.overview.periods;
  for (const p of periods) {
    const panel = el('div', 'panel');
    const inP = obs.filter(o => o.observedEpoch >= p.startEpoch && o.observedEpoch < p.endEpoch);
    const sessions = state.overview.sessions.filter(s => s.periodCode === p.code);
    const zeroSessions = sessions.filter(s => s.countZero).length;
    panel.appendChild(el('h3', null,
      `${p.code} ${p.name}  [${fmt(p.startAt)} , ${fmt(p.endAt)})  —  ${inP.length} 条观测` +
      (sessions.length ? ` · ${sessions.length} 个调查会话（含 ${zeroSessions} 个零目击）`
        : ' · 无调查会话（未调查）')));
    const list = el('div');
    if (inP.length === 0) {
      list.appendChild(el('div', 'muted',
        sessions.length ? '本时段有调查努力但没有观测记录。' : '本时段完全没有调查：这是“未调查”，不是捕获为零。'));
    }
    for (const o of inP) list.appendChild(timelineNode(o));
    panel.appendChild(list);
    root.appendChild(panel);
  }
}

function timelineNode(o) {
  const wrap = el('div', 'timeline-item' + (o.zero ? ' zero' : ''));
  const head = el('div');
  const site = state.overview.sites.find(s => s.code === o.siteCode);
  head.append(
    el('span', 'mono', o.id + '  '),
    el('span', null, fmt(o.observedAt) + '  '),
    el('span', 'muted', `${site ? site.name : o.siteCode} · 观察者 ${o.observer || '-'} · `),
    el('span', null, `置信度 ${Math.round(o.confidence * 100)}%`));
  if (o.zero) head.appendChild(el('span', 'badge zero', '观察为零（有努力）'));
  wrap.appendChild(head);

  if (!o.zero) {
    const body = el('div', 'reason');
    body.innerHTML = `标记片段 <code>${esc(o.markFragment || '—')}</code> · 花纹：${esc(o.patternSummary || '—')}`;
    if (o.note) body.innerHTML += ` · ${esc(o.note)}`;
    wrap.appendChild(body);
  } else if (o.note) {
    wrap.appendChild(el('div', 'reason', o.note));
  }

  const confirmed = assignmentFor(o.id);
  const denied = denialFor(o.id);
  const status = el('div', 'reason');
  if (confirmed) {
    status.innerHTML = `当前假设确认：<strong>${esc(indLabel(confirmed.individualCode))}</strong>` +
      ` <span class="muted">[${confirmed.reasonCode || ''}]</span>`;
  } else if (denied) {
    status.innerHTML = `已否定候选 <span class="mono">${esc(denied.individualCode || '')}</span>` +
      ` <span class="badge hard">${denied.reasonCode}</span> ${esc(denied.reasonNote || '')}`;
  } else if (!o.zero) {
    status.appendChild(el('span', 'badge unresolved', '未决（保留全部候选）'));
  }
  wrap.appendChild(status);

  if (!o.zero) {
    const acts = el('div', 'row-actions');
    const btnCand = el('button', 'small ghost', '查看候选/特征对比');
    btnCand.addEventListener('click', () => openCandidates(o.id));
    acts.appendChild(btnCand);
    if (state.hypothesis.status === 'DRAFT') {
      const btnDeny = el('button', 'small danger', '否定…');
      btnDeny.addEventListener('click', () => openDeny(o));
      acts.appendChild(btnDeny);
    }
    wrap.appendChild(acts);
  }
  return wrap;
}

// ------------------------------------------------------------ candidates

async function candidatesFor(obsId) {
  if (!state.candidatesCache[obsId]) {
    state.candidatesCache[obsId] = await api(
      `/observations/${encodeURIComponent(obsId)}/candidates?hypothesisId=${encodeURIComponent(state.hypId)}`);
  }
  return state.candidatesCache[obsId];
}

async function renderCandidates() {
  const root = $('#tab-candidates');
  root.innerHTML = '';
  const obs = (await api('/observations')).filter(o => !o.zero);
  for (const o of obs) {
    const data = await candidatesFor(o.id);
    const panel = el('div', 'panel');
    const h = el('h3');
    h.innerHTML = `<span class="mono">${esc(o.id)}</span> ${esc(fmt(o.observedAt))} ` +
      `${esc(o.siteCode)} — 标记 <code>${esc(o.markFragment)}</code>`;
    panel.appendChild(h);

    const table = el('table');
    table.innerHTML = '<tr><th>候选个体</th><th>原始分</th><th>冲突调整后</th>' +
      '<th>标记分量</th><th>花纹分量</th><th>状态</th><th>说明</th><th>操作</th></tr>';
    for (const c of data.candidates) {
      const tr = el('tr');
      const isNew = c.individualCode === '#NEW#';
      tr.appendChild(td(isNew ? el('span', 'badge new', '新个体') : el('span', null, indLabel(c.individualCode))));
      tr.appendChild(td(scoreBar(c.rawScore)));
      tr.appendChild(td(scoreBar(c.adjustedScore)));
      tr.appendChild(td(scoreBar(c.markComponent)));
      tr.appendChild(td(scoreBar(c.patternComponent)));
      const statusBadge = c.status === 'REJECTED' ? 'badge hard'
        : c.status === 'SUSPICIOUS' ? 'badge soft' : 'badge ok';
      tr.appendChild(td(el('span', statusBadge, c.status)));
      tr.appendChild(td(el('span', 'reason', c.reason || '')));
      const ops = el('div', 'row-actions');
      if (state.hypothesis.status === 'DRAFT') {
        if (isNew) {
          const b = el('button', 'small', '登记并确认新个体');
          b.addEventListener('click', () => openNewIndividual(o));
          ops.appendChild(b);
        } else {
          const b = el('button', 'small', '确认');
          b.disabled = c.status === 'REJECTED';
          b.title = c.status === 'REJECTED' ? '存在不可达冲突，需在弹窗中强制覆盖' : '';
          b.addEventListener('click', () => openConfirm(o, c));
          ops.appendChild(b);
        }
      }
      tr.appendChild(td(ops));
      table.appendChild(tr);
    }
    panel.appendChild(table);

    const compare = el('div', 'featgrid');
    compare.appendChild(featureCard('观测特征', o.markFragment, o.patternSummary));
    for (const c of data.candidates) {
      if (c.individualCode === '#NEW#') continue;
      const ind = state.overview.individuals.find(i => i.code === c.individualCode);
      if (ind) compare.appendChild(featureCard(ind.code + ' ' + ind.label, ind.marking, ind.patternSummary));
    }
    panel.appendChild(compare);
    root.appendChild(panel);
  }
}

function td(node) {
  const t = el('td');
  if (node) t.appendChild(node);
  return t;
}

function scoreBar(v) {
  const wrap = el('span');
  const bar = el('span', 'scorebar');
  const fill = el('span');
  fill.style.width = Math.round(v * 100) + '%';
  bar.appendChild(fill);
  wrap.append(bar, el('span', 'mono', ' ' + v.toFixed(2)));
  return wrap;
}

function featureCard(title, marking, pattern) {
  const card = el('div', 'feat');
  card.innerHTML = `<h4>${esc(title)}</h4><div>标记：<code>${esc(marking || '—')}</code></div>` +
    `<div class="muted">花纹：${esc(pattern || '—')}</div>`;
  return card;
}

// --------------------------------------------------------------- modals

let modalSubmit = null;
function openModal(title, contentNode, onSubmit) {
  $('#modalTitle').textContent = title;
  const body = $('#modalBody');
  body.innerHTML = '';
  body.appendChild(contentNode);
  modalSubmit = onSubmit;
  $('#modal').classList.remove('hidden');
  $('#modalOk').style.display = onSubmit ? '' : 'none';
}
function closeModal() {
  $('#modal').classList.add('hidden');
  modalSubmit = null;
}
$('#modalOk').addEventListener('click', async () => {
  if (!modalSubmit) return;
  try {
    await modalSubmit();
    closeModal();
  } catch (e) {
    await handleEditError(e);
  }
});

function baseRevision() {
  return state.hypothesis.status === 'DRAFT' ? state.hypothesis.revision : null;
}

function reasonSelect(value) {
  const sel = document.createElement('select');
  for (const code of ['MARK_LOST', 'DUPLICATE_CODE', 'DATA_ENTRY_ERROR']) {
    const opt = el('option', null, ({
      MARK_LOST: '标记脱落', DUPLICATE_CODE: '重复编码', DATA_ENTRY_ERROR: '观察记录输入错误'
    })[code]);
    opt.value = code;
    if (code === value) opt.selected = true;
    sel.appendChild(opt);
  }
  return sel;
}

function openCandidates(obsId) {
  state.activeTab = 'candidates';
  document.querySelectorAll('.tabs button').forEach(x =>
    x.classList.toggle('active', x.dataset.tab === 'candidates'));
  document.querySelectorAll('.tab').forEach(t =>
    t.classList.toggle('active', t.id === 'tab-candidates'));
  renderActiveTab();
}

function openConfirm(o, c) {
  const node = document.createElement('div');
  const conflictInfo = c.conflict
    ? `<div class="field"><span class="badge ${c.conflict.level === 'HARD' ? 'hard' : 'soft'}">` +
      `${c.conflict.level} 冲突</span> <span class="reason">锚点 ${esc(c.conflict.anchorObservationId)}` +
      ` @${esc(c.conflict.anchorSiteCode)}，${c.conflict.distanceKm}km / ${c.conflict.gapHours}h` +
      `${c.conflict.speedKmh >= 0 ? ' ≈ ' + c.conflict.speedKmh + ' km/h' : '（同时不同地）'}</span></div>`
    : '';
  node.innerHTML = `<div class="field">确认 <span class="mono">${esc(o.id)}</span> 属于 ` +
    `<strong>${esc(indLabel(c.individualCode))}</strong>（调整后分数 ${c.adjustedScore}）。</div>` +
    conflictInfo +
    `<label class="field"><input type="checkbox" id="forceChk" ${c.status !== 'REJECTED' ? 'disabled' : ''}>` +
    ` 我已核对原始观测，强制保留该观测并覆盖冲突（OVERRIDE_CONFLICT）</label>` +
    `<div class="reason">原始观测与自动分不会被删除；覆盖原因将记入审计。</div>`;
  openModal('确认身份', node, async () => {
    const force = $('#forceChk', node).checked;
    const body = {
      observationId: o.id, individualCode: c.individualCode,
      baseRevision: baseRevision(), reasonCode: 'MANUAL_REVIEW', force,
    };
    const r = await api(`/hypotheses/${encodeURIComponent(state.hypId)}/confirm`,
      { method: 'POST', body });
    toast(`已确认；revision ${r.previousRevision} → ${r.revision}`);
    await reloadAfterEdit();
  });
}

function openNewIndividual(o) {
  const node = document.createElement('div');
  node.innerHTML =
    `<div class="field"><label>个体标签</label><input id="newLabel" value="新个体 ${esc(o.id)}"></div>` +
    `<div class="field"><label>标记编码（默认取观测片段）</label><input id="newMarking" value="${esc(o.markFragment || '')}"></div>` +
    `<div class="field"><label>花纹摘要（默认取观测摘要）</label><input id="newPattern" style="width:100%" value="${esc(o.patternSummary || '')}"></div>` +
    `<div class="field"><label>原因备注</label><input id="newNote" style="width:100%" placeholder="为何判定为新个体"></div>`;
  openModal('登记新个体', node, async () => {
    const r = await api(`/hypotheses/${encodeURIComponent(state.hypId)}/new-individual`, {
      method: 'POST',
      body: {
        observationId: o.id, label: $('#newLabel', node).value,
        marking: $('#newMarking', node).value || null,
        patternSummary: $('#newPattern', node).value || null,
        reasonNote: $('#newNote', node).value || null,
        baseRevision: baseRevision(),
      },
    });
    toast(`已登记新个体并确认；revision ${r.previousRevision} → ${r.revision}`);
    await reloadAfterEdit();
  });
}

function openDeny(o) {
  const node = document.createElement('div');
  const indSel = document.createElement('select');
  indSel.id = 'denyInd';
  const candidates = (state.candidatesCache[o.id]?.candidates) || [];
  for (const c of candidates) {
    if (c.individualCode === '#NEW#') continue;
    indSel.appendChild(el('option', null, indLabel(c.individualCode))).value = c.individualCode;
    indSel.lastChild.value = c.individualCode;
  }
  const field1 = document.createElement('div');
  field1.className = 'field';
  field1.append(Object.assign(document.createElement('label'), { textContent: '被否定的候选' }), indSel);
  const rs = reasonSelect('DUPLICATE_CODE');
  rs.id = 'denyReason';
  const field2 = document.createElement('div');
  field2.className = 'field';
  field2.append(Object.assign(document.createElement('label'), { textContent: '原因' }), rs);
  const note = document.createElement('textarea');
  note.id = 'denyNote';
  note.placeholder = '例如：标记脱落（MARK_LOST）/ 重复编码（DUPLICATE_CODE）/ 输入错误（DATA_ENTRY_ERROR）的依据';
  const field3 = document.createElement('div');
  field3.className = 'field';
  field3.append(Object.assign(document.createElement('label'), { textContent: '依据说明' }), note);
  node.append(field1, field2, field3);
  openModal('否定候选（分层保留人工决定）', node, async () => {
    const r = await api(`/hypotheses/${encodeURIComponent(state.hypId)}/deny`, {
      method: 'POST',
      body: {
        observationId: o.id, individualCode: indSel.value || null,
        reasonCode: rs.value, reasonNote: note.value || null,
        baseRevision: baseRevision(),
      },
    });
    toast(`已记录否定；revision ${r.previousRevision} → ${r.revision}`);
    await reloadAfterEdit();
  });
}

// -------------------------------------------------------------- conflicts

async function renderConflicts() {
  const root = $('#tab-conflicts');
  root.innerHTML = '';
  const conflicts = await api(`/hypotheses/${encodeURIComponent(state.hypId)}/conflicts`);
  const panel = el('div', 'panel');
  panel.appendChild(el('h3', null,
    `已确认归属中的 HARD 冲突（${conflicts.length}）— 冲突降低/拒绝候选，但观测始终保留`));

  const ops = el('div', 'row-actions');
  const bMerge = el('button', 'small ghost', '合并两个身份…');
  bMerge.addEventListener('click', openMerge);
  const bSplit = el('button', 'small ghost', '拆分身份…');
  bSplit.addEventListener('click', openSplit);
  ops.append(bMerge, bSplit);
  panel.appendChild(ops);
  if (state.hypothesis.status !== 'DRAFT') {
    panel.appendChild(el('p', 'reason', '已发布版本不可变；请从已发布版本新建草稿后修订。'));
  }

  if (conflicts.length === 0) {
    panel.appendChild(el('p', 'muted', '当前没有已确认的不可达冲突。'));
  }
  const table = el('table');
  table.innerHTML = '<tr><th>个体</th><th>观测 A</th><th>观测 B</th><th>距离</th><th>时间差</th>' +
    '<th>速度</th><th>级别</th></tr>';
  for (const c of conflicts) {
    const tr = el('tr');
    tr.innerHTML = `<td>${esc(indLabel(c.individualCode))}</td>` +
      `<td class="mono">${esc(c.observationA)}</td>` +
      `<td class="mono">${esc(c.observationB)}</td>` +
      `<td>${c.check.distanceKm} km</td><td>${c.check.gapHours} h</td>` +
      `<td>${c.check.speedKmh < 0 ? '同时不同地' : c.check.speedKmh + ' km/h'}</td>` +
      `<td><span class="badge hard">${c.check.level}</span></td>`;
    table.appendChild(tr);
  }
  panel.appendChild(table);
  root.appendChild(panel);

  const raw = el('div', 'panel');
  raw.appendChild(el('h3', null, '全部观测的候选级别（自动分与冲突调整分层展示）'));
  const obs = await api('/observations');
  const t2 = el('table');
  t2.innerHTML = '<tr><th>观测</th><th>候选</th><th>原始分</th><th>调整后</th><th>级别</th><th>锚点</th></tr>';
  for (const o of obs.filter(x => !x.zero)) {
    const data = await candidatesFor(o.id);
    for (const c of data.candidates) {
      if (c.status === 'ELIGIBLE' && c.individualCode !== '#NEW#') continue;
      const tr = el('tr');
      tr.innerHTML = `<td class="mono">${esc(o.id)}</td>` +
        `<td>${c.individualCode === '#NEW#' ? '<span class="badge new">新个体</span>'
          : esc(indLabel(c.individualCode))}</td>` +
        `<td>${c.rawScore}</td><td>${c.adjustedScore}</td>` +
        `<td><span class="badge ${c.status === 'REJECTED' ? 'hard' : 'soft'}">${c.status}</span></td>` +
        `<td class="reason">${c.conflict ? esc(c.conflict.anchorObservationId + ' @' +
          c.conflict.anchorSiteCode + ' ' + c.conflict.distanceKm + 'km/' + c.conflict.gapHours + 'h') : ''}</td>`;
      t2.appendChild(tr);
    }
  }
  raw.appendChild(t2);
  root.appendChild(raw);
}

function openMerge() {
  const node = document.createElement('div');
  node.innerHTML =
    `<div class="field"><label>源个体（其全部已确认观测并入目标）</label>${indSelectHtml('mergeFrom')}</div>` +
    `<div class="field"><label>目标个体</label>${indSelectHtml('mergeInto')}</div>` +
    `<div class="field"><label>原因（如重复编码）</label><input id="mergeNote" style="width:100%" ` +
    `placeholder="例如：两次野外编码实为同一个体（DUPLICATE_CODE）"></div>` +
    `<div class="reason">合并是假设层面的决定：源个体仍保留在注册表与审计中。</div>`;
  openModal('合并身份假设', node, async () => {
    const r = await api(`/hypotheses/${encodeURIComponent(state.hypId)}/merge`, {
      method: 'POST',
      body: {
        fromIndividual: $('#mergeFrom', node).value,
        intoIndividual: $('#mergeInto', node).value,
        reasonNote: $('#mergeNote', node).value || null,
        baseRevision: baseRevision(),
      },
    });
    toast(`已合并；revision ${r.previousRevision} → ${r.revision}`);
    await reloadAfterEdit();
  });
}

function indSelectHtml(id) {
  let html = `<select id="${id}">`;
  for (const i of state.overview.individuals) {
    html += `<option value="${esc(i.code)}">${esc(i.code)} ${esc(i.label)}</option>`;
  }
  return html + '</select>';
}

function openSplit() {
  const node = document.createElement('div');
  node.innerHTML =
    `<div class="field"><label>源个体</label>${indSelectHtml('splitFrom')}</div>` +
    `<div class="field"><label>目标个体（留空则创建新个体）</label><select id="splitTo">` +
    `<option value="">— 新建 —</option>` +
    state.overview.individuals.map(i =>
      `<option value="${esc(i.code)}">${esc(i.code)} ${esc(i.label)}</option>`).join('') +
    `</select></div>` +
    `<div class="field"><label>要拆出的观测 ID（逗号分隔）</label>` +
    `<input id="splitObs" style="width:100%" placeholder="o-1003,o-1016"></div>` +
    `<div class="field"><label>原因</label><input id="splitNote" style="width:100%"></div>`;
  openModal('拆分身份假设', node, async () => {
    const ids = $('#splitObs', node).value.split(',').map(s => s.trim()).filter(Boolean);
    const r = await api(`/hypotheses/${encodeURIComponent(state.hypId)}/split`, {
      method: 'POST',
      body: {
        fromIndividual: $('#splitFrom', node).value,
        toIndividual: $('#splitTo', node).value || null,
        observationIds: ids,
        reasonNote: $('#splitNote', node).value || null,
        baseRevision: baseRevision(),
      },
    });
    toast(`已拆分；revision ${r.previousRevision} → ${r.revision}`);
    await reloadAfterEdit();
  });
}

// --------------------------------------------------------------- capture

async function renderCapture() {
  const root = $('#tab-capture');
  root.innerHTML = '';
  const s = await api(`/hypotheses/${encodeURIComponent(state.hypId)}/capture-history`);
  const panel = el('div', 'panel');
  const title = s.status === 'DRAFT' ? `草稿捕获历史预览（revision ${state.hypothesis.revision}）`
    : `已发布版本 v${s.version} 的捕获历史`;
  panel.appendChild(el('h3', null, title));
  const legend = el('div', 'reason');
  legend.innerHTML = '<span class="badge ok">1</span> 捕获　' +
    '<span class="badge zero">0</span> 有调查但未捕获（观察可为零）　' +
    '<span class="badge unresolved">NA</span> 未调查＝缺失值，不计入概率分母';
  panel.appendChild(legend);

  const table = el('table');
  let head = '<tr><th>个体</th>';
  for (const p of s.periods) head += `<th>${p.code}<br><span class="reason">[${fmt(p.startAt)} , ${fmt(p.endAt)})</span></th>`;
  head += '<th>捕获次数</th></tr>';
  table.innerHTML = head;
  for (const row of s.rows) {
    const tr = el('tr');
    tr.innerHTML = `<td>${esc(row.individualCode)} ${esc(row.label)}</td>` +
      row.cells.map(c =>
        `<td class="${c === '1' ? 'c1' : c === '0' ? 'c0' : 'cna'}">${c}</td>`).join('') +
      `<td class="mono">${row.captured}</td>`;
    table.appendChild(tr);
  }
  const tr = el('tr');
  tr.innerHTML = '<td><strong>朴素捕获概率</strong></td>' +
    s.naiveCaptureProbability.map(p =>
      `<td class="c0"><strong>${p === null ? 'NA' : p.toFixed(2)}</strong></td>`).join('') + '<td></td>';
  table.appendChild(tr);
  panel.appendChild(table);

  const basis = el('div', 'reason');
  basis.innerHTML = `<br>统计口径：${esc(s.statisticalBasis)}<br><strong>${esc(s.disclaimer)}</strong>`;
  panel.appendChild(basis);
  root.appendChild(panel);
}

// ---------------------------------------------------------------- import

function renderImport() {
  const root = $('#tab-import');
  root.innerHTML = '';
  const panel = el('div', 'panel');
  panel.appendChild(el('h3', null, '导入观测批次（同批次同内容重复提交＝幂等空操作）'));
  const ta = document.createElement('textarea');
  ta.id = 'importJson';
  ta.style.minHeight = '220px';
  ta.value = JSON.stringify({
    batchRef: 'B-DEMO',
    note: '示例批次',
    observations: [{
      id: 'o-demo-1', siteCode: 'S-EAST', observedAt: '2026-04-22T10:00:00+09:00',
      markFragment: 'MK-A10', patternSummary: '左耳缺刻 尾斑四点',
      confidence: 0.8, observer: '演示'
    }, {
      id: 'o-demo-zero', siteCode: 'S-SOUTH', observedAt: '2026-04-23T09:00:00+09:00',
      zero: true, confidence: 1, observer: '演示', note: '有努力，零目击'
    }]
  }, null, 2);
  const f = document.createElement('div');
  f.className = 'field';
  f.append(Object.assign(document.createElement('label'), { textContent: 'JSON 请求体' }), ta);
  panel.appendChild(f);
  const btn = el('button', null, '提交 / 重放');
  const out = el('pre', 'diff-ctx');
  btn.addEventListener('click', async () => {
    try {
      const body = JSON.parse(ta.value);
      const r = await api('/imports', { method: 'POST', body });
      out.textContent = JSON.stringify(r, null, 2);
      toast(r.idempotentReplay ? '幂等重放：无重复写入' : `导入完成：新增 ${r.inserted} 条`);
      state.overview = await api('/overview');
    } catch (e) {
      out.textContent = JSON.stringify(e.body || e.message, null, 2);
      toast(e.message, true);
    }
  });
  panel.append(btn, out);
  root.appendChild(panel);
}

// ------------------------------------------------ publish / draft / errors

async function publish() {
  try {
    const r = await api(`/hypotheses/${encodeURIComponent(state.hypId)}/publish`, {
      method: 'POST',
      body: { baseRevision: baseRevision(), actor: 'researcher' },
    });
    toast(r.idempotentReplay
      ? `幂等：该 revision 已发布为 ${r.id} v${r.version}`
      : `已发布 ${r.id}（v${r.version}），草稿已重开`);
    state.overview = await api('/overview');
    renderHypSelect();
    $('#hypSelect').value = r.reopenedDraft || state.hypId;
    await loadHypothesis($('#hypSelect').value);
  } catch (e) {
    await handleEditError(e);
  }
}

async function newDraft() {
  const cur = state.hypothesis;
  const cloneFrom = cur.status === 'PUBLISHED' ? cur.id
    : (await api(`/hypotheses/${encodeURIComponent(cur.id)}`)).hypothesis.parentHypId;
  try {
    const d = await api('/hypotheses', {
      method: 'POST',
      body: {
        lineageId: 'L-' + Math.random().toString(36).slice(2, 7),
        title: '新审阅线草稿',
        cloneFromId: cloneFrom,
        note: '从 ' + cloneFrom + ' 克隆',
      },
    });
    toast('已创建草稿 ' + d.id);
    state.overview = await api('/overview');
    renderHypSelect();
    $('#hypSelect').value = d.id;
    await loadHypothesis(d.id);
  } catch (e) {
    toast(e.message, true);
  }
}

async function reloadAfterEdit() {
  state.overview = await api('/overview');
  renderHypSelect();
  $('#hypSelect').value = state.hypId;
  await loadHypothesis(state.hypId);
}

/**
 * On a 409 revision conflict, keep the reviewer's own change context visible:
 * show the other reviewer's changes since the base revision and offer a
 * one-click re-fetch; the user can then rebase and resubmit.
 */
async function handleEditError(e) {
  if (e.status === 409 && e.body) {
    const node = document.createElement('div');
    node.innerHTML =
      `<div class="reason">您基于 revision ${e.body.yourBaseRevision} 提交，服务器已到 revision ` +
      `${e.body.currentRevision}。您的修改内容未被覆盖，可在审阅对方改动后基于新版本重提。</div>` +
      `<div class="field"><label>对方在此期间的改动</label><div class="diff-ctx">${
        esc(JSON.stringify(e.body.concurrentChanges, null, 2))}</div></div>`;
    openModal('并发修订冲突（双方上下文均保留）', node, null);
    $('#modalOk').style.display = 'none';
    return e;
  }
  if (e.status === 422 && e.body) {
    toast('存在不可达冲突，已拒绝该候选（观测保留）。可在候选表中强制覆盖。', true);
    const node = document.createElement('div');
    node.innerHTML = `<div class="diff-ctx">${esc(JSON.stringify(e.body, null, 2))}</div>`;
    openModal('时空冲突阻止确认', node, null);
    $('#modalOk').style.display = 'none';
    return e;
  }
  toast(e.message, true);
  return e;
}

boot().catch(e => toast(e.message + '\n' + (e.stack || ''), true));
