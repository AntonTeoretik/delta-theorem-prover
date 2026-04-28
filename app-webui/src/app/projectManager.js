(function initDeltaProjectManager(global) {
  const STORAGE_KEY = 'delta.project.v1';
  const HEADER = '--!delta-project v1';
  const FILE_PREFIX = '--!file ';
  const DEFAULT_EXTENSION = 'dlt';
  const DEFAULT_FILE = `main.${DEFAULT_EXTENSION}`;

  function createDefaultProject() {
    return { activeFile: DEFAULT_FILE, files: [{ path: DEFAULT_FILE, content: '' }] };
  }

  function decodeProject(text) {
    const normalized = (text || '').replace(/\r\n/g, '\n');
    if (!normalized.startsWith(HEADER)) {
      return { activeFile: DEFAULT_FILE, files: [{ path: DEFAULT_FILE, content: normalized }] };
    }

    const lines = normalized.split('\n');
    const files = [];
    let current = null;
    let buffer = [];

    function flush() {
      if (!current) return;
      files.push({ path: current, content: buffer.join('\n').replace(/\n+$/, '') });
      buffer = [];
    }

    for (let i = 1; i < lines.length; i += 1) {
      const line = lines[i];
      if (line.startsWith(FILE_PREFIX)) {
        flush();
        current = line.slice(FILE_PREFIX.length).trim() || null;
        continue;
      }
      if (current) {
        buffer.push(line);
      }
    }
    flush();

    if (!files.length) return createDefaultProject();
    return { activeFile: files[0].path, files };
  }

  function encodeProject(project) {
    const ordered = [...project.files].sort((a, b) => {
      if (a.path === project.activeFile) return -1;
      if (b.path === project.activeFile) return 1;
      return a.path.localeCompare(b.path);
    });

    const body = ordered.map((file) => `${FILE_PREFIX}${file.path}\n${file.content.trimEnd()}\n`).join('\n');
    return `${HEADER}\n${body}`.trimEnd() + '\n';
  }

  function createProjectManager({ editorInput, fileSelect, addFileButton, uploadButton, uploadInput }) {
    let project = createDefaultProject();

    function renderFiles() {
      fileSelect.innerHTML = '';
      project.files.forEach((file) => {
        const option = document.createElement('option');
        option.value = file.path;
        option.textContent = file.path;
        fileSelect.appendChild(option);
      });
      fileSelect.value = project.activeFile;
    }

    function getActiveFile() {
      return project.files.find((f) => f.path === project.activeFile) || project.files[0];
    }

    function syncEditorFromProject() {
      const file = getActiveFile();
      editorInput.value = file?.content || '';
    }

    function updateActiveFileContent(text) {
      const file = getActiveFile();
      if (file) file.content = text;
    }

    function loadFromSerialized(serialized) {
      project = decodeProject(serialized || '');
      renderFiles();
      syncEditorFromProject();
    }

    function serialize() {
      return encodeProject(project);
    }

    function loadFromStorage() {
      const serialized = global.localStorage?.getItem(STORAGE_KEY);
      if (serialized) {
        loadFromSerialized(serialized);
      } else {
        renderFiles();
      }
    }

    function autosave() {
      global.localStorage?.setItem(STORAGE_KEY, serialize());
    }

    fileSelect.addEventListener('change', () => {
      updateActiveFileContent(editorInput.value);
      project.activeFile = fileSelect.value;
      syncEditorFromProject();
      autosave();
    });

    addFileButton.addEventListener('click', () => {
      const raw = global.prompt(`New file name (extension .${DEFAULT_EXTENSION})`, `module${project.files.length + 1}.${DEFAULT_EXTENSION}`);
      if (!raw) return;
      const path = raw.includes('.') ? raw : `${raw}.${DEFAULT_EXTENSION}`;
      if (project.files.some((f) => f.path === path)) return;
      updateActiveFileContent(editorInput.value);
      project.files.push({ path, content: '' });
      project.activeFile = path;
      renderFiles();
      syncEditorFromProject();
      autosave();
    });

    uploadButton.addEventListener('click', () => uploadInput.click());
    uploadInput.addEventListener('change', async () => {
      const [file] = uploadInput.files || [];
      if (!file) return;
      const text = await file.text();
      if (file.name.endsWith('.dlproj')) {
        loadFromSerialized(text);
      } else {
        updateActiveFileContent(editorInput.value);
        const path = file.name.includes('.') ? file.name : `${file.name}.${DEFAULT_EXTENSION}`;
        const existing = project.files.find((f) => f.path === path);
        if (existing) {
          existing.content = text;
        } else {
          project.files.push({ path, content: text });
        }
        project.activeFile = path;
        renderFiles();
        syncEditorFromProject();
      }
      uploadInput.value = '';
      autosave();
    });

    return {
      loadFromStorage,
      updateActiveFileContent,
      serialize,
      autosave,
      loadFromSerialized,
      getProject: () => project,
    };
  }

  global.DeltaProjectManager = {
    createProjectManager,
    decodeProject,
    encodeProject,
  };
}(window));
