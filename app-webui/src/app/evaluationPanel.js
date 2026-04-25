(function initDeltaEvaluationPanel(global) {
  function escapeHtml(value) {
    return String(value ?? '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function renderEvaluationPanel(wrapElement, outputElement, payload, enabled) {
    if (!wrapElement || !outputElement) {
      return;
    }

    wrapElement.classList.toggle('hidden', !enabled);
    if (!enabled) {
      outputElement.innerHTML = '';
      return;
    }

    const trace = payload?.activeEvaluationTrace;
    if (!trace) {
      outputElement.innerHTML = '<p class="eval-empty">No evaluation trace for current line.</p>';
      return;
    }

    const steps = Array.isArray(trace.steps) ? trace.steps : [];
    if (steps.length === 0) {
      outputElement.innerHTML = `<p class="eval-empty">${escapeHtml(trace.title)} (line ${Number(trace.line) || '-'}) has no reduction steps.</p>`;
      return;
    }

    const header = `<p class="eval-empty">${escapeHtml(trace.title)} (line ${Number(trace.line) || '-'})</p>`;
    const body = steps.map((step, index) => {
      const reason = escapeHtml(step?.reason || 'step');
      const from = escapeHtml(step?.from || '');
      const to = escapeHtml(step?.to || '');
      return `<article class="eval-step"><div class="eval-step-head">${index + 1}. ${reason}</div><pre class="eval-term">${from}</pre><div class="eval-arrow">=> </div><pre class="eval-term">${to}</pre></article>`;
    }).join('');

    outputElement.innerHTML = `${header}${body}`;
  }

  global.DeltaEvaluationPanel = {
    renderEvaluationPanel,
  };
 }(window));
