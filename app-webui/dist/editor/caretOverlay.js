(function initDeltaEditorCaretOverlay(global) {
  const { escapeHtml } = global.DeltaEditorTextUtils;

  function updateEditorCaretOverlay(state, elements) {
    const { editorInput, editorCaretOverlay, editorLayer } = elements;
    const isFocused = document.activeElement === editorInput;
    const start = editorInput.selectionStart ?? 0;
    const end = editorInput.selectionEnd ?? start;
    if (!isFocused || start !== end || !state.lastProjection || !Array.isArray(state.lastProjection.rawToDisplay)) {
      editorCaretOverlay.style.display = 'none';
      return;
    }

    const clampedIndex = Math.max(0, Math.min(start, state.lastProjection.rawToDisplay.length - 1));
    const displayOffset = state.lastProjection.rawToDisplay[clampedIndex] ?? 0;
    const before = escapeHtml((state.lastProjection.displayText || '').slice(0, displayOffset));
    const inputStyle = window.getComputedStyle(editorInput);

    const probe = document.createElement('div');
    probe.style.position = 'absolute';
    probe.style.visibility = 'hidden';
    probe.style.pointerEvents = 'none';
    probe.style.whiteSpace = 'pre-wrap';
    probe.style.overflowWrap = 'break-word';
    probe.style.font = inputStyle.font;
    probe.style.lineHeight = inputStyle.lineHeight;
    probe.style.letterSpacing = inputStyle.letterSpacing;
    probe.style.paddingTop = inputStyle.paddingTop;
    probe.style.paddingRight = inputStyle.paddingRight;
    probe.style.paddingBottom = inputStyle.paddingBottom;
    probe.style.paddingLeft = inputStyle.paddingLeft;
    probe.style.width = `${editorInput.clientWidth}px`;
    probe.style.height = `${editorInput.clientHeight}px`;
    probe.style.left = '0';
    probe.style.top = '0';
    probe.innerHTML = `${before}<span id="caretProbe">|</span>`;
    editorLayer.appendChild(probe);

    const marker = probe.querySelector('#caretProbe');
    const markerRect = marker.getBoundingClientRect();
    const layerRect = editorLayer.getBoundingClientRect();
    const lineHeight = Number.parseFloat(window.getComputedStyle(editorInput).lineHeight) || 20;

    const left = markerRect.left - layerRect.left - editorInput.scrollLeft;
    const top = markerRect.top - layerRect.top - editorInput.scrollTop;

    editorCaretOverlay.style.display = 'block';
    editorCaretOverlay.style.left = `${Math.max(0, left)}px`;
    editorCaretOverlay.style.top = `${Math.max(0, top)}px`;
    editorCaretOverlay.style.height = `${lineHeight}px`;

    probe.remove();
  }

  function hideEditorCaretOverlay(editorCaretOverlay) {
    editorCaretOverlay.style.display = 'none';
  }

  global.DeltaEditorCaretOverlay = {
    updateEditorCaretOverlay,
    hideEditorCaretOverlay,
  };
}(window));
