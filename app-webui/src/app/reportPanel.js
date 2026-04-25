(function initDeltaReportPanel(global) {
  function renderDiagnosticsReport(reportOutput, payload) {
    const diagnostics = payload?.diagnostics || [];
    const trace = payload?.activeTypeCheckTrace;

    const diagnosticsBlock = diagnostics.length === 0
      ? 'No diagnostics.'
      : diagnostics
        .map((diag, index) => `${index + 1}. [${diag.line}:${diag.column}] ${diag.message}`)
        .join('\n');

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
