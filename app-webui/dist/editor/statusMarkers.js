(function initDeltaEditorStatusMarkers(global) {
  function renderDefinitionStatusMarkers(state, elements, payload, showTooltip, hideTooltip) {
    const container = elements.editorStatusMarkers;
    const input = elements.editorInput;
    if (!container || !input) {
      return;
    }

    const statuses = Array.isArray(payload?.definitionStatuses) ? payload.definitionStatuses : [];
    container.innerHTML = '';
    if (statuses.length === 0) {
      return;
    }

    const style = window.getComputedStyle(input);
    const lineHeight = Number.parseFloat(style.lineHeight) || 20;
    const paddingTop = Number.parseFloat(style.paddingTop) || 12;

    statuses.forEach((status) => {
      const line = Number(status?.line) || 0;
      if (line <= 0) {
        return;
      }

      const marker = document.createElement('span');
      marker.className = `status-marker ${status.isOk ? 'ok' : 'error'}`;
      marker.textContent = status.isOk ? '✓' : '✕';
      marker.style.top = `${paddingTop + (line - 1) * lineHeight - input.scrollTop + 2}px`;

      const messages = Array.isArray(status.messages) ? status.messages : [];
      const tooltipText = status.isOk ? `Line ${line}: definition typechecks successfully.` : messages.join('\n');

      marker.addEventListener('mouseenter', (event) => {
        showTooltip(tooltipText, event.clientX + 8, event.clientY + 12);
      });
      marker.addEventListener('mousemove', (event) => {
        showTooltip(tooltipText, event.clientX + 8, event.clientY + 12);
      });
      marker.addEventListener('mouseleave', () => {
        hideTooltip();
      });

      container.appendChild(marker);
    });
  }

  global.DeltaEditorStatusMarkers = {
    renderDefinitionStatusMarkers,
  };
}(window));
