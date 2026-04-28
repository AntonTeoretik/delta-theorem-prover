import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

class FakeInput {
  constructor() {
    this.listeners = new Map();
    this.value = '';
    this.selectionStart = 0;
    this.selectionEnd = 0;
    this.style = {};
  }
  addEventListener(name, fn) { this.listeners.set(name, fn); }
  dispatch(name, event = {}) { const fn = this.listeners.get(name); if (fn) fn(event); }
  setSelectionRange(s, e) { this.selectionStart = s; this.selectionEnd = e; }
}

function loadHandlers(windowObj) {
  const code = fs.readFileSync(new URL('../src/editor/inputHandlers.js', import.meta.url), 'utf8');
  const context = vm.createContext({ window: windowObj, document: windowObj.document, console });
  vm.runInContext(code, context);
}

function baseConfig(editorInput, editorLayer, notifyHostTextChanged) {
  return {
    state: { suppressHostNotify: false, overlayInputMode: { active: false, buffer: '', start: null }, lastPayload: { definitionNames: [], freeVariableNames: [] } },
    elements: { editorInput, editorLayer, editorCaretOverlay: { style: {} }, editorAutocomplete: { style: {}, innerHTML: '' } },
    renderEditorWithCurrentHighlights: () => {},
    notifyHostTextChanged,
    notifyCaretMoved: () => {},
    applyEditorTextChange: (nextText, caret) => { editorInput.value = nextText; editorInput.setSelectionRange(caret, caret); },
    resetSlashMode: () => {},
    enterSlashMode: () => {},
    refreshSlashModeForSelection: () => false,
    resolveSpecialSymbol: () => null,
    isWhitespace: (s) => /^\s$/.test(s),
    matchingBracket: (ch) => ({ '(': ')', '[': ']', '{': '}' }[ch]),
    isOpeningBracket: (ch) => '([{'.includes(ch),
    isClosingBracket: (ch) => ')]}'.includes(ch),
    shouldSkipExistingClosing: () => false,
    findBackslashAtomAt: () => null,
    updateEditorCaretOverlay: () => {},
    syncEditorOverlayScroll: () => {},
    findEditorTypeHintAt: () => null,
    showTooltip: () => {},
    hideTooltip: () => {},
  };
}

test('input event notifies host callback', () => {
  const editorInput = new FakeInput();
  const editorLayer = new FakeInput();
  const windowObj = { document: { addEventListener: () => {} } };
  loadHandlers(windowObj);

  let calls = 0;
  windowObj.DeltaEditorInputHandlers.attachEditorInputHandlers(baseConfig(editorInput, editorLayer, () => { calls += 1; }));

  editorInput.value = 'abc';
  editorInput.dispatch('input');
  assert.equal(calls, 1);
});

test('ctrl slash toggles line comment', () => {
  const editorInput = new FakeInput();
  const editorLayer = new FakeInput();
  const windowObj = { document: { addEventListener: () => {} } };
  loadHandlers(windowObj);

  windowObj.DeltaEditorInputHandlers.attachEditorInputHandlers(baseConfig(editorInput, editorLayer, () => {}));

  editorInput.value = 'x';
  editorInput.selectionStart = 0;
  editorInput.selectionEnd = 1;
  editorInput.dispatch('keydown', { key: '/', ctrlKey: true, preventDefault: () => {} });
  assert.equal(editorInput.value, '-- x');
});
