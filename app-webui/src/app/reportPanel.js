(function initDeltaReportPanel(global) {
  function renderDiagnosticsReport(reportOutput, payload) {
    const diagnostics = payload?.diagnostics || [];
    if (diagnostics.length === 0) {
      reportOutput.textContent = 'No diagnostics.';
      return;
    }

    reportOutput.textContent = diagnostics
      .map((diag, index) => `${index + 1}. [${diag.line}:${diag.column}] ${diag.message}`)
      .join('\n');
  }

  global.DeltaReportPanel = {
    renderDiagnosticsReport,
  };
}(window));
