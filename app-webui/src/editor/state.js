(function initDeltaEditorState(global) {
  function createInitialPayload() {
    return {
      sourceText: '',
      diagnostics: [],
      textHighlights: [],
      typeHints: [],
      activeTypeCheckTrace: null,
      activeEvaluationTrace: null,
      definitionStatuses: [],
      symbolReplacements: {},
      definitionNames: [],
      selectedDefinitionName: null,
      freeVariableNames: [],
      nodes: [],
      nodeTypeHints: {},
      blueEdges: [],
      greenEdges: [],
    };
  }

  function createOverlayInputMode() {
    return {
      active: false,
      start: null,
      buffer: '',
    };
  }

  function createAppState() {
    const lastPayload = createInitialPayload();
    return {
      suppressHostNotify: false,
      lastSentCaretOffset: -1,
      activeSymbolReplacements: {},
      overlayInputMode: createOverlayInputMode(),
      lastPayload,
      renderedPayload: lastPayload,
      lastProjection: {
        displayText: '',
        rawToDisplay: [0],
      },
      editorTypeHintRects: [],
    };
  }

  function createViewState() {
    return {
      scale: 1,
      offsetX: 0,
      offsetY: 0,
      minScale: 0.35,
      maxScale: 2.8,
      isPanning: false,
      lastX: 0,
      lastY: 0,
    };
  }

  function resetSlashMode(state) {
    state.overlayInputMode.active = false;
    state.overlayInputMode.start = null;
    state.overlayInputMode.buffer = '';
  }

  function enterSlashMode(state, start) {
    state.overlayInputMode.active = true;
    state.overlayInputMode.start = start;
    state.overlayInputMode.buffer = '';
  }

  function refreshSlashModeForSelection(state, editorInput) {
    if (!state.overlayInputMode.active) {
      return false;
    }

    const start = editorInput.selectionStart ?? 0;
    const end = editorInput.selectionEnd ?? start;
    if (start !== end || start !== state.overlayInputMode.start) {
      resetSlashMode(state);
      return true;
    }

    return false;
  }

  global.DeltaEditorState = {
    createAppState,
    createViewState,
    resetSlashMode,
    enterSlashMode,
    refreshSlashModeForSelection,
  };
}(window));
