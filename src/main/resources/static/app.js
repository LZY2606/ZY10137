const state = { timeline: null, revision: null };
const reasonCodes = [
  'MARKER_SHED', 'DUPLICATE_CODE', 'DATA_ENTRY_ERROR',
  'PATTERN_CONFIRMATION', 'OBSERVER_REVIEW',
  'SPLIT_DISTINCT_INDIVIDUAL', 'MERGE_SAME_INDIVIDUAL'
];

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
    body: options.body ? JSON.stringify(options.body) : undefined
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(payload.error || `HTTP ${response.status}`);
    error.payload = payload;
    throw error;
  }
  return payload;
}

async function load() {
  state.timeline = await api('/api/timeline');
  state.revision = state.timeline.active_revision;
  render();
}

function render() {
  renderSummary();
  renderRevision();
  renderSurveys();
  renderObservations();
}

function renderSummary() {
  const s = state.timeline.summary;
  document.getElementById('summary').innerHTML = `
    <div>显式采样场合：<strong>${s ? '' : ''}</strong></div>`;
  api('/api/captures/summary').then(summary => {
    document.getElementById('summary').innerHTML = `
      <div>采样场合：<strong>${summary.survey_sites}</strong></div>
      <div>有观测场合：<strong>${summary.observed_occasions}</strong>，记录条数：<strong>${summary.captures}</strong></div>
      <div>基础捕获概率：<strong>${summary.raw_capture_probability.toFixed(3)}</strong></div>
      <small>${summary.interpretation}</small>`;
  });
}

function renderRevision() {
  const revision = state.revision;
  const publish = document.getElementById('publishRevision');
  publish.disabled = !revision || revision.status !== 'DRAFT';
  document.getElementById('newRevision').disabled = !state.timeline.revisions.some(r => r.status === 'PUBLISHED');
  if (!revision) {
    document.getElementById('revisionInfo').textContent = '尚无版本';
    return;
  }
  document.getElementById('revisionInfo').innerHTML = `
    <div>#${revision.revision_no} ${revision.status} · version ${revision.version}</div>
    <small>${revision.author} · ${revision.note || ''} · ${revision.created_at}</small>
    <div>分配 ${revision.items.length} 条观测</div>`;
}

function renderSurveys() {
  document.getElementById('surveys').innerHTML = `<div class="survey-grid">${
    state.timeline.surveys.map(survey => `
      <div class="survey-card">
        <strong>${survey.code}</strong> ${survey.name}
        <div><small>${survey.zone_id}</small></div>
        <div>[${survey.start_at}, ${survey.end_at})</div>
        <div><small>采样地点：${survey.site_codes.join(', ') || '观测时自动纳入'}</small></div>
      </div>`).join('')
  }</div>`;
}

function renderObservations() {
  const search = document.getElementById('search').value.toLowerCase();
  const onlyOpen = document.getElementById('onlyOpen').checked;
  const container = document.getElementById('observations');
  container.innerHTML = '';
  const observations = state.timeline.observations.filter(observation => {
    const haystack = [observation.code, observation.site_code, observation.marker_fragment,
      observation.pattern_summary, observation.survey_code].join(' ').toLowerCase();
    const open = observation.draft_individual_id == null || observation.candidates.some(c => c.auto_state === 'REJECTED');
    return haystack.includes(search) && (!onlyOpen || open);
  });
  if (!observations.length) {
    container.innerHTML = '<p>没有匹配观测。</p>';
    return;
  }
  for (const observation of observations) {
    container.appendChild(renderObservation(observation));
  }
}

function renderObservation(observation) {
  const template = document.getElementById('observation-template');
  const node = template.content.cloneNode(true);
  node.querySelector('h3').textContent = `${observation.code} · ${observation.site_code}`;
  node.querySelector('.meta').textContent =
    `${observation.observed_at} UTC · ${observation.survey_code} · 观察者 ${observation.observer} · 置信度 ${observation.confidence}`;
  node.querySelector('.raw-features').innerHTML = `
    <div><strong>标记片段</strong><br>${observation.marker_fragment}</div>
    <div><strong>花纹摘要</strong><br>${observation.pattern_summary}</div>
    <div><strong>时间不确定度</strong><br>${observation.uncertainty_seconds} 秒</div>`;
  const assigned = state.timeline.individuals.find(i => i.id === observation.draft_individual_id);
  node.querySelector('.assignment').innerHTML = assigned
    ? `当前草稿：<strong>${assigned.code}</strong>`
    : '<strong>未分配（可代表新个体）</strong>';
  const candidates = node.querySelector('.candidates');
  for (const candidate of observation.candidates) {
    candidates.appendChild(renderCandidate(observation, candidate));
  }
  return node;
}

function renderCandidate(observation, candidate) {
  const card = document.createElement('div');
  card.className = `candidate ${candidate.effective_state.toLowerCase()}`;
  card.innerHTML = `
    <div><span class="badge ${candidate.auto_state}">自动：${candidate.auto_state}</span>
      <span class="badge ${candidate.decision || ''}">${candidate.decision || '未人工决定'}</span>
    </div>
    <h4>${candidate.individual_code} <small>${candidate.display_name}</small></h4>
    <div class="score">自动分 ${candidate.score?.toFixed(3)}</div>
    <div class="feature-comparison">
      <small>已发布标记：${candidate.anchor_markers?.join(' / ') || '暂无'}</small><br>
      <small>已发布花纹：${candidate.anchor_patterns?.join(' / ') || '暂无'}</small>
    </div>
    ${candidate.reason_code ? `<div><small>原因：${candidate.reason_code} ${candidate.reason_detail || ''}</small></div>` : ''}
    ${candidate.conflict ? conflictHtml(candidate.conflict) : ''}
    <div class="actions"></div>`;
  const actions = card.querySelector('.actions');
  if (!state.revision || state.revision.status !== 'DRAFT') {
    actions.innerHTML = '<small>需要草稿版本才能操作</small>';
  } else {
    actions.appendChild(actionButton('确认', '', () => decide(observation, candidate, 'confirm')));
    actions.appendChild(actionButton('否定', 'secondary', () => decide(observation, candidate, 'deny')));
    if (candidate.auto_state === 'REJECTED') {
      actions.querySelector('button').disabled = true;
    }
    const split = actionButton('拆为新身份', 'secondary', () => split(observation));
    actions.appendChild(split);
    const merge = actionButton('移动到该身份', 'secondary', () => merge(observation, candidate));
    actions.appendChild(merge);
    const mergeIdentity = actionButton('合并整个身份', 'secondary', () => mergeIdentity(candidate));
    actions.appendChild(mergeIdentity);
  }
  return card;
}

function conflictHtml(conflict) {
  return `<div class="conflict">
    <strong>时空不可达</strong><br>
    距离 ${Math.round(conflict.distance_m)} m；
    最短间隔 ${Math.round(conflict.elapsed_seconds)} s；
    需要 ${conflict.required_speed_mps.toFixed(2)} m/s &gt;
    ${conflict.max_allowed_speed_mps} m/s<br>
    <small>${conflict.reason}</small>
  </div>`;
}

function actionButton(label, className, handler) {
  const button = document.createElement('button');
  button.className = className;
  button.textContent = label;
  button.addEventListener('click', handler);
  return button;
}

function reasonCode() {
  const selected = prompt('请输入原因代码：' + reasonCodes.join(', '), 'PATTERN_CONFIRMATION');
  if (!selected) throw new Error('cancelled');
  if (!reasonCodes.includes(selected)) {
    alert('原因代码无效');
    throw new Error('invalid reason');
  }
  return selected;
}

function basePayload(reasonCodeValue) {
  return {
    base_version: state.revision.version,
    author: 'browser-reviewer',
    reason_code: reasonCodeValue,
    reason_detail: 'Browser review',
    client_key: null
  };
}

async function decide(observation, candidate, kind) {
  try {
    const code = kind === 'deny' ? reasonCode() : (reasonCode() );
    await api(`/api/revisions/${state.revision.id}/observations/${observation.id}/candidates/${candidate.individual_id}/${kind}`, {
      method: 'POST',
      body: basePayload(code)
    });
    await load();
  } catch (error) { handleError(error); }
}

async function split(observation) {
  try {
    const name = prompt('新身份显示名', `Split from ${observation.code}`);
    if (!name) return;
    await api(`/api/revisions/${state.revision.id}/observations/${observation.id}/split`, {
      method: 'POST',
      body: { ...basePayload('SPLIT_DISTINCT_INDIVIDUAL'), display_name: name }
    });
    await load();
  } catch (error) { handleError(error); }
}

async function merge(observation, candidate) {
  try {
    const fromId = observation.draft_individual_id;
    if (!fromId) return alert('先确认一个来源身份后才能合并。');
    await api(`/api/revisions/${state.revision.id}/observations/${observation.id}/merge/${fromId}`, {
      method: 'POST',
      body: { ...basePayload('MERGE_SAME_INDIVIDUAL'), to_individual_id: candidate.individual_id }
    });
    await load();
  } catch (error) { handleError(error); }
}

async function mergeIdentity(candidate) {
  try {
    const choices = state.timeline.individuals
      .filter(individual => individual.status !== 'MERGED' && individual.id !== candidate.individual_id)
      .map(individual => `${individual.id}: ${individual.code}`)
      .join('\n');
    const picked = prompt(`输入目标个体 ID：\n${choices}`);
    if (!picked) return;
    await api(`/api/revisions/${state.revision.id}/identities/${candidate.individual_id}/merge`, {
      method: 'POST',
      body: { ...basePayload('MERGE_SAME_INDIVIDUAL'), to_individual_id: Number(picked) }
    });
    await load();
  } catch (error) { handleError(error); }
}

function handleError(error) {
  if (error.message === 'cancelled') return;
  const context = error.payload?.conflict_context;
  if (context) {
    alert(`版本冲突：你的基础版本 ${error.payload.submitted_base_version}，当前版本 ${context.current_version}。页面将刷新并保留服务端上下文。`);
    load();
  } else {
    alert(error.message);
  }
}

document.getElementById('search').addEventListener('input', renderObservations);
document.getElementById('onlyOpen').addEventListener('change', renderObservations);
document.getElementById('newRevision').addEventListener('click', async () => {
  try {
    const revision = await api('/api/revisions', {
      method: 'POST',
      body: {
        based_on_revision_id: null,
        author: 'browser-reviewer',
        note: 'Browser review',
        client_key: `draft-${crypto.randomUUID()}`
      }
    });
    await load();
    state.revision = state.timeline.revisions.find(r => r.id === revision.id);
    render();
  } catch (error) { handleError(error); }
});
document.getElementById('publishRevision').addEventListener('click', async () => {
  try {
    await api(`/api/revisions/${state.revision.id}/publish`, {
      method: 'POST',
      body: {
        base_version: state.revision.version,
        author: 'browser-reviewer',
        client_key: `publish-${crypto.randomUUID()}`,
        note: 'Browser publication'
      }
    });
    await load();
  } catch (error) { handleError(error); }
});

load().catch(error => {
  document.getElementById('observations').textContent = error.message;
});
