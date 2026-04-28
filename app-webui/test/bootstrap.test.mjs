import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

class FakeElement {
  constructor(id = '') {
    this.id = id;
    this.value = '';
    this.innerHTML = '';
    this.textContent = '';
    this.checked = false;
    this.style = {};
    this.files = [];
    this.listeners = new Map();
  }
  addEventListener(name, fn) { this.listeners.set(name, fn); }
  dispatch(name, event = {}) {
    const fn = this.listeners.get(name);
    if (fn) fn({ preventDefault: () => {}, ...event });
  }
  appendChild() {}
  setSelectionRange(s, e) { this.selectionStart = s; this.selectionEnd = e; }
  focus() {}
  getContext() { return {}; }
  click() { this.dispatch('click'); }
}

function load(file, context) {
  const code = fs.readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8');
  vm.runInContext(code, context);
}

test('bootstrap typing sends serialized project text (not undefined)', () => {
  const elements = new Map();
  const get = (id) => {
    if (!elements.has(id)) elements.set(id, new FakeElement(id));
    return elements.get(id);
  };
  const editorLayer = new FakeElement('editor-layer');

  const sent = [];
  const windowObj = {
    __deltaQueuedPayload: null,
    __deltaQueuedEditorText: null,
    addEventListener: () => {},
    prompt: () => null,
    localStorage: { getItem: () => null, setItem: () => {} },
    DeltaBridge: {
      notifyHostDefinitionSelected: () => {},
      notifyHostTextChanged: (text) => { sent.push(text); },
      notifyHostEditorCaretMoved: () => {},
    },
    DeltaDefinitionBar: { renderDefinitionBar: () => {} },
    DeltaGraph: { compactify: (x) => x },
    DeltaRenderer: { createRenderer: () => ({ fitToContent: () => {}, draw: () => {} }) },
    DeltaEditorState: {
      createAppState: () => ({ suppressHostNotify: false, lastSentCaretOffset: -1, activeSymbolReplacements: {}, overlayInputMode: { active: false, start: null, buffer: '' }, lastPayload: { sourceText: '', textHighlights: [], typeHints: [], nodes: [] }, renderedPayload: { nodes: [] } }),
      createViewState: () => ({}),
      resetSlashMode: (s) => { s.overlayInputMode = { active: false, start: null, buffer: '' }; },
      enterSlashMode: () => {},
      refreshSlashModeForSelection: () => false,
    },
    DeltaEditorTextUtils: {
      resolveSymbolReplacements: () => ({}),
      resolveSpecialSymbol: () => null,
      isWhitespace: (s) => /^\s$/.test(s),
      matchingBracket: (ch) => ({ '(': ')', '[': ']', '{': '}' }[ch]),
      isOpeningBracket: (ch) => '([{'.includes(ch),
      isClosingBracket: (ch) => ')]}'.includes(ch),
      shouldSkipExistingClosing: () => false,
      findBackslashAtomAt: () => null,
      escapeHtml: (s) => s,
      resolveBracketHighlight: () => null,
    },
    DeltaEditorOverlay: { renderEditorHighlight: () => {} },
    DeltaEditorTooltips: {
      showTooltip: () => {},
      hideTooltip: () => {},
      collectEditorTypeHintRects: () => {},
      findEditorTypeHintAt: () => null,
    },
    DeltaEditorCaretOverlay: { updateEditorCaretOverlay: () => {} },
    DeltaEditorStatusMarkers: { renderDefinitionStatusMarkers: () => {} },
    DeltaGraphInteractions: { attachGraphInteractions: () => {} },
    DeltaReportPanel: { renderDiagnosticsReport: () => {} },
    DeltaEvaluationPanel: { renderEvaluationPanel: () => {} },
  };

  const documentObj = {
    getElementById: (id) => get(id),
    querySelector: () => editorLayer,
    addEventListener: () => {},
    activeElement: null,
    createElement: () => new FakeElement(),
  };

  const context = vm.createContext({ window: windowObj, document: documentObj, console });
  load('app/projectManager.js', context);
  load('editor/inputHandlers.js', context);
  load('app/bootstrap.js', context);

  windowObj.DeltaAppBootstrap.bootstrap();

  const editorInput = get('editorInput');
  editorInput.value = 'def x : Type;';
  editorInput.selectionStart = editorInput.value.length;
  editorInput.selectionEnd = editorInput.value.length;
  editorInput.dispatch('input');

  assert.ok(sent.length > 0);
  assert.match(sent.at(-1), /--!delta-project v1/);
  assert.match(sent.at(-1), /def x : Type;/);
  assert.equal(sent.at(-1).includes('undefined'), false);
});
