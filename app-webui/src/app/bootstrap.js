(function initDeltaAppBootstrap(global) {
  function bootstrap() {
    const editorInput = document.getElementById('editorInput');
    const editorHighlight = document.getElementById('editorHighlight');
    const editorLineNumbers = document.getElementById('editorLineNumbers');
    const editorLayer = document.querySelector('.editor-layer');
    const editorCaretOverlay = document.getElementById('editorCaretOverlay');
    const hoverTooltip = document.getElementById('hoverTooltip');
    const canvas = document.getElementById('vizCanvas');
    const stats = document.getElementById('stats');
    const reportOutput = document.getElementById('reportOutput');
    const compactToggle = document.getElementById('compactToggle');
    const definitionBar = document.getElementById('definitionBar');
    const ctx = canvas.getContext('2d');

    const bridgeApi = global.DeltaBridge;
    const definitionBarApi = global.DeltaDefinitionBar;
    const graphApi = global.DeltaGraph;
    const rendererApi = global.DeltaRenderer;
    const stateApi = global.DeltaEditorState;
    const textUtilsApi = global.DeltaEditorTextUtils;
    const overlayApi = global.DeltaEditorOverlay;
    const tooltipApi = global.DeltaEditorTooltips;
    const caretOverlayApi = global.DeltaEditorCaretOverlay;
    const inputHandlersApi = global.DeltaEditorInputHandlers;
    const graphInteractionsApi = global.DeltaGraphInteractions;
    const reportPanelApi = global.DeltaReportPanel;

    if (!bridgeApi
      || !definitionBarApi
      || !graphApi
      || !rendererApi
      || !stateApi
      || !textUtilsApi
      || !overlayApi
      || !tooltipApi
      || !caretOverlayApi
      || !inputHandlersApi
      || !graphInteractionsApi
      || !reportPanelApi) {
      stats.textContent = 'UI initialization failed: script module missing';
      throw new Error('Delta web UI bootstrap failed: missing global module');
    }

    const {
      notifyHostDefinitionSelected,
      notifyHostTextChanged,
      notifyHostEditorCaretMoved,
    } = bridgeApi;
    const { renderDefinitionBar } = definitionBarApi;
    const { compactify } = graphApi;
    const { createRenderer } = rendererApi;
    const {
      createAppState,
      createViewState,
      resetSlashMode,
      enterSlashMode,
      refreshSlashModeForSelection,
    } = stateApi;
    const {
      resolveSymbolReplacements,
      resolveSpecialSymbol,
      isWhitespace,
      matchingBracket,
      isOpeningBracket,
      isClosingBracket,
      shouldSkipExistingClosing,
      findBackslashAtomAt,
    } = textUtilsApi;
    const { renderEditorHighlight } = overlayApi;
    const {
      showTooltip,
      hideTooltip,
      collectEditorTypeHintRects,
      findEditorTypeHintAt,
    } = tooltipApi;
    const { updateEditorCaretOverlay } = caretOverlayApi;
    const { attachEditorInputHandlers } = inputHandlersApi;
    const { attachGraphInteractions } = graphInteractionsApi;
    const { renderDiagnosticsReport } = reportPanelApi;

    const elements = {
      editorInput,
      editorHighlight,
      editorLineNumbers,
      editorLayer,
      editorCaretOverlay,
      hoverTooltip,
      reportOutput,
    };
    const view = createViewState();
    const state = createAppState();
    const renderer = createRenderer({
      canvas,
      ctx,
      statsElement: stats,
      view,
    });

    function showGlobalTooltip(typeText, clientX, clientY) {
      showTooltip(hoverTooltip, typeText, clientX, clientY);
    }

    function hideGlobalTooltip() {
      hideTooltip(hoverTooltip);
    }

    function refreshEditorTypeHintRects(typeHints) {
      collectEditorTypeHintRects(state, editorHighlight, typeHints);
    }

    function refreshCaretOverlay() {
      updateEditorCaretOverlay(state, elements);
    }

    function syncEditorOverlayScroll() {
      editorHighlight.style.transform = `translate(${-editorInput.scrollLeft}px, ${-editorInput.scrollTop}px)`;
      editorLineNumbers.style.transform = `translateY(${-editorInput.scrollTop}px)`;
      refreshCaretOverlay();
      refreshEditorTypeHintRects(state.lastPayload?.typeHints || []);
    }

    function renderEditor(payload) {
      renderEditorHighlight(state, elements, payload);
      syncEditorOverlayScroll();
      refreshEditorTypeHintRects(payload?.typeHints || []);
      refreshCaretOverlay();
    }

    function renderEditorWithCurrentHighlights() {
      renderEditor({
        sourceText: editorInput.value,
        textHighlights: state.lastPayload?.textHighlights || [],
        typeHints: state.lastPayload?.typeHints || [],
      });
    }

    function notifyCaretMoved() {
      const caretOffset = editorInput.selectionStart || 0;
      if (caretOffset === state.lastSentCaretOffset) {
        return;
      }
      state.lastSentCaretOffset = caretOffset;
      notifyHostEditorCaretMoved(caretOffset);
    }

    function applyEditorTextChange(nextText, caretOffset) {
      editorInput.value = nextText;
      editorInput.setSelectionRange(caretOffset, caretOffset);
      renderEditorWithCurrentHighlights();
      if (!state.suppressHostNotify) {
        notifyHostTextChanged(editorInput.value);
      }
      notifyCaretMoved();
    }

    function refreshSlashModeForEditorSelection() {
      return refreshSlashModeForSelection(state, editorInput);
    }

    function resolveSlashSymbol(token) {
      return resolveSpecialSymbol(state.activeSymbolReplacements, token);
    }

    function renderCurrent(resetView) {
      state.lastPayload.symbolReplacements = state.activeSymbolReplacements;
      state.renderedPayload = compactify(state.lastPayload, compactToggle.checked);
      renderDefinitionBar(definitionBar, state.renderedPayload, notifyHostDefinitionSelected);
      renderDiagnosticsReport(reportOutput, state.renderedPayload);

      if (resetView) {
        renderer.fitToContent(state.renderedPayload);
      }
      renderer.draw(state.renderedPayload);
    }

    global.renderFromHost = (payload) => {
      const previousNodeCount = (state.lastPayload.nodes || []).length;
      state.lastPayload = payload || state.lastPayload;
      state.activeSymbolReplacements = resolveSymbolReplacements(state.lastPayload);
      refreshSlashModeForEditorSelection();
      renderEditor(state.lastPayload);
      const shouldResetView = previousNodeCount === 0 && (state.lastPayload.nodes || []).length > 0;
      renderCurrent(shouldResetView);
    };

    if (global.__deltaQueuedPayload !== null) {
      global.renderFromHost(global.__deltaQueuedPayload);
      global.__deltaQueuedPayload = null;
    }

    global.setEditorTextFromHost = (text) => {
      state.suppressHostNotify = true;
      editorInput.value = text || '';
      resetSlashMode(state);
      renderEditorWithCurrentHighlights();
      syncEditorOverlayScroll();
      state.lastSentCaretOffset = -1;
      state.suppressHostNotify = false;
      editorInput.focus();
      notifyCaretMoved();
    };

    if (global.__deltaQueuedEditorText !== null) {
      global.setEditorTextFromHost(global.__deltaQueuedEditorText);
      global.__deltaQueuedEditorText = null;
    }

    attachEditorInputHandlers({
      state,
      elements,
      renderEditorWithCurrentHighlights,
      notifyHostTextChanged,
      notifyCaretMoved,
      applyEditorTextChange,
      resetSlashMode: () => resetSlashMode(state),
      enterSlashMode: (start) => enterSlashMode(state, start),
      refreshSlashModeForSelection: refreshSlashModeForEditorSelection,
      resolveSpecialSymbol: resolveSlashSymbol,
      isWhitespace,
      matchingBracket,
      isOpeningBracket,
      isClosingBracket,
      shouldSkipExistingClosing,
      findBackslashAtomAt,
      updateEditorCaretOverlay: refreshCaretOverlay,
      syncEditorOverlayScroll,
      findEditorTypeHintAt: (x, y) => findEditorTypeHintAt(state, x, y),
      showTooltip: showGlobalTooltip,
      hideTooltip: hideGlobalTooltip,
    });

    compactToggle.addEventListener('change', () => {
      renderCurrent(true);
    });

    window.addEventListener('resize', () => {
      renderer.draw(state.renderedPayload);
      refreshEditorTypeHintRects(state.lastPayload?.typeHints || []);
      hideGlobalTooltip();
    });

    attachGraphInteractions({
      canvas,
      view,
      renderer,
      getRenderedPayload: () => state.renderedPayload,
      showTooltip: showGlobalTooltip,
      hideTooltip: hideGlobalTooltip,
    });

    resetSlashMode(state);
    renderEditor(state.lastPayload);
    renderCurrent(true);
  }

  global.DeltaAppBootstrap = {
    bootstrap,
  };
}(window));
