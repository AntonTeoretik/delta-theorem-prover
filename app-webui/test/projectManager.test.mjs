import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

class FakeElement {
  constructor() {
    this.listeners = new Map();
    this.value = '';
    this.innerHTML = '';
    this.files = [];
  }

  addEventListener(name, fn) {
    this.listeners.set(name, fn);
  }

  dispatch(name, event = {}) {
    const fn = this.listeners.get(name);
    if (fn) fn(event);
  }

  appendChild() {}
  click() {}
}

function loadProjectManager(windowObj) {
  const code = fs.readFileSync(new URL('../src/app/projectManager.js', import.meta.url), 'utf8');
  const context = vm.createContext({ window: windowObj, document: windowObj.document, console });
  vm.runInContext(code, context);
}

test('decodeProject normalizes undefined payload', () => {
  const windowObj = { document: { createElement: () => new FakeElement() } };
  loadProjectManager(windowObj);
  const decoded = windowObj.DeltaProjectManager.decodeProject('undefined');
  assert.equal(decoded.files[0].content, '');
});

test('manager serializes active file changes', () => {
  const localStorage = new Map();
  const windowObj = {
    prompt: () => null,
    localStorage: {
      getItem: (k) => localStorage.get(k) ?? null,
      setItem: (k, v) => localStorage.set(k, v),
    },
    document: { createElement: () => new FakeElement() },
  };
  loadProjectManager(windowObj);

  const editorInput = new FakeElement();
  const fileSelect = new FakeElement();
  const addFileButton = new FakeElement();
  const uploadButton = new FakeElement();
  const uploadInput = new FakeElement();

  const manager = windowObj.DeltaProjectManager.createProjectManager({
    editorInput,
    fileSelect,
    addFileButton,
    uploadButton,
    uploadInput,
  });

  manager.loadFromSerialized('--!delta-project v1\n--!file main.dlt\n');
  editorInput.value = 'def x : Type;';
  manager.updateActiveFileContent(editorInput.value);
  const serialized = manager.serialize();
  assert.match(serialized, /def x : Type;/);
});


test('file switch preserves per-file text', () => {
  class SelectElement extends FakeElement {
    constructor() { super(); this.options = []; }
    appendChild(opt) { this.options.push(opt); }
  }

  const windowObj = {
    prompt: () => 'util.dlt',
    localStorage: { getItem: () => null, setItem: () => {} },
    document: { createElement: () => new FakeElement() },
  };
  loadProjectManager(windowObj);

  const editorInput = new FakeElement();
  const fileSelect = new SelectElement();
  const addFileButton = new FakeElement();
  const uploadButton = new FakeElement();
  const uploadInput = new FakeElement();

  const manager = windowObj.DeltaProjectManager.createProjectManager({
    editorInput,
    fileSelect,
    addFileButton,
    uploadButton,
    uploadInput,
  });

  manager.loadFromSerialized(`--!delta-project v1\n--!file main.dlt\nmain text\n`);
  addFileButton.dispatch('click');
  editorInput.value = 'util text';
  manager.updateActiveFileContent(editorInput.value);

  fileSelect.value = 'main.dlt';
  fileSelect.dispatch('change');
  assert.equal(editorInput.value, 'main text');
});
