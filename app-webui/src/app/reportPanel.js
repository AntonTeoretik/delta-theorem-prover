(function initDeltaReportPanel(global) {
  function renderDiagnosticsReport(reportOutput, statusBadge, payload, showTypecheckTrace) {
    const diagnostics = payload?.diagnostics || [];
    const trace = payload?.activeTypeCheckTrace;
    const deduplicatedDiagnostics = [];
    const seen = new Set();

    diagnostics.forEach((diag) => {
      const key = `${diag.line}:${diag.column}:${diag.message}`;
      if (seen.has(key)) {
        return;
      }
      seen.add(key);
      deduplicatedDiagnostics.push(diag);
    });

    const diagnosticsBlock = deduplicatedDiagnostics.length === 0
      ? 'All checks passed.'
      : deduplicatedDiagnostics
        .map((diag, index) => `${index + 1}. [${diag.line}:${diag.column}] ${diag.message}`)
        .join('\n');

    if (statusBadge) {
      if (deduplicatedDiagnostics.length === 0) {
        statusBadge.textContent = '✓';
        statusBadge.className = 'report-status-badge ok';
        statusBadge.title = 'No diagnostics';
      } else {
        statusBadge.textContent = '✕';
        statusBadge.className = 'report-status-badge error';
        statusBadge.title = `${deduplicatedDiagnostics.length} diagnostics`;
      }
    }

    if (!showTypecheckTrace) {
      reportOutput.textContent = diagnosticsBlock;
      return;
    }

    const traceBlock = !trace
      ? 'Typecheck trace for current line: unavailable.'
      : [
        `Typecheck trace for current line (${trace.title}, line ${trace.line}):`,
        ...(Array.isArray(trace.steps) && trace.steps.length > 0
          ? trace.steps.map((step, index) => `  ${index + 1}. ${step}`)
          : ['  (no recorded steps)']),
      ].join('\n');

    reportOutput.textContent = `${diagnosticsBlock}\n\n${traceBlock}`;
  }

  global.DeltaReportPanel = {
    renderDiagnosticsReport,
  };
}(window));
