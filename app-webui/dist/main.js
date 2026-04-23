const editorInput = document.getElementById('editorInput');
const editorHighlight = document.getElementById('editorHighlight');
const editorLayer = document.querySelector('.editor-layer');
const editorCaretOverlay = document.getElementById('editorCaretOverlay');
const canvas = document.getElementById('vizCanvas');
const stats = document.getElementById('stats');
const compactToggle = document.getElementById('compactToggle');
const definitionBar = document.getElementById('definitionBar');
const ctx = canvas.getContext('2d');

const view = {
  scale: 1,
  offsetX: 0,
  offsetY: 0,
  minScale: 0.35,
  maxScale: 2.8,
  isPanning: false,
  lastX: 0,
  lastY: 0,
};

const bridgeApi = window.DeltaBridge;
const definitionBarApi = window.DeltaDefinitionBar;
const graphApi = window.DeltaGraph;
const rendererApi = window.DeltaRenderer;

if (!bridgeApi || !definitionBarApi || !graphApi || !rendererApi) {
  stats.textContent = 'UI initialization failed: script module missing';
  throw new Error('Delta web UI bootstrap failed: missing global module');
}

const {
  notifyHostDefinitionSelected: notifyHostDefinitionSelectedFn,
  notifyHostTextChanged: notifyHostTextChangedFn,
  notifyHostEditorCaretMoved: notifyHostEditorCaretMovedFn,
} = bridgeApi;
const { renderDefinitionBar: renderDefinitionBarFn } = definitionBarApi;
const { compactify: compactifyGraph } = graphApi;
const { createRenderer: createRendererFn } = rendererApi;

const renderer = createRendererFn({
  canvas,
  ctx,
  statsElement: stats,
  view,
});

let suppressHostNotify = false;
let lastSentCaretOffset = -1;
let activeSymbolReplacements = {};
let overlayInputMode = {
  active: false,
  start: null,
  buffer: '',
};
let lastPayload = {
  sourceText: '',
  diagnostics: [],
  textHighlights: [],
  symbolReplacements: {},
  definitionNames: [],
  selectedDefinitionName: null,
  freeVariableNames: [],
  nodes: [],
  blueEdges: [],
  greenEdges: [],
};
let renderedPayload = lastPayload;
let lastProjection = {
  displayText: '',
  rawToDisplay: [0],
};

const BACKSLASH_TOKEN_CHAR = /[^\s]/;

function escapeHtml(text) {
  return text
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');
}

function resolveSymbolReplacements(payload) {
  const table = {};
  const candidates = [
    payload?.symbolReplacements,
    payload?.backslashReplacements,
    payload?.backslashSymbolMap,
    payload?.symbolMap,
  ];

  function putEntry(rawKey, rawValue) {
    if (typeof rawKey !== 'string' || typeof rawValue !== 'string' || rawValue.length === 0) {
      return;
    }
    const trimmed = rawKey.trim();
    if (!trimmed) {
      return;
    }
    const key = trimmed.startsWith('\\') ? trimmed.slice(1) : trimmed;
    if (!key) {
      return;
    }
    table[key] = rawValue;
  }

  candidates.forEach((candidate) => {
    if (!candidate) {
      return;
    }
    if (Array.isArray(candidate)) {
      candidate.forEach((entry) => {
        if (!entry || typeof entry !== 'object') {
          return;
        }
        putEntry(entry.token ?? entry.name ?? entry.key, entry.symbol ?? entry.value ?? entry.replacement);
      });
      return;
    }
    if (typeof candidate === 'object') {
      Object.entries(candidate).forEach(([key, value]) => {
        putEntry(key, value);
      });
    }
  });

  return table;
}

function isTokenChar(ch) {
  return Boolean(ch) && BACKSLASH_TOKEN_CHAR.test(ch);
}

function resolveSpecialSymbol(token) {
  if (token.length === 0) {
    return 'λ';
  }
  return activeSymbolReplacements[token] || null;
}

function isWhitespace(ch) {
  return ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r';
}

function resetSlashMode() {
  overlayInputMode.active = false;
  overlayInputMode.start = null;
  overlayInputMode.buffer = '';
}

function enterSlashMode(start) {
  overlayInputMode.active = true;
  overlayInputMode.start = start;
  overlayInputMode.buffer = '';
}

function refreshSlashModeForSelection() {
  if (!overlayInputMode.active) {
    return false;
  }
  const start = editorInput.selectionStart ?? 0;
  const end = editorInput.selectionEnd ?? start;
  if (start !== end || start !== overlayInputMode.start) {
    resetSlashMode();
    return true;
  }
  return false;
}

function setProjection(rawToDisplay, rawStart, rawLength, displayStart, displayLength, mode = 'scaled') {
  rawToDisplay[rawStart] = displayStart;
  if (rawLength <= 0) {
    return;
  }

  if (mode === 'identity') {
    for (let step = 1; step <= rawLength; step += 1) {
      rawToDisplay[rawStart + step] = displayStart + Math.min(step, displayLength);
    }
    return;
  }

  if (mode === 'compressed') {
    for (let step = 1; step <= rawLength; step += 1) {
      rawToDisplay[rawStart + step] = step < rawLength ? displayStart : displayStart + displayLength;
    }
    return;
  }

  for (let step = 1; step <= rawLength; step += 1) {
    rawToDisplay[rawStart + step] = displayStart + Math.round((step * displayLength) / rawLength);
  }
}

function buildOverlayProjection(sourceText) {
  const rawToDisplay = new Array(sourceText.length + 1);
  rawToDisplay[0] = 0;

  let rawIndex = 0;
  let displayIndex = 0;
  let displayText = '';

  function append(rawLength, displayChunk, mode = 'identity') {
    const displayStart = displayIndex;
    displayText += displayChunk;
    displayIndex += displayChunk.length;
    setProjection(rawToDisplay, rawIndex, rawLength, displayStart, displayChunk.length, mode);
    rawIndex += rawLength;
  }

  while (rawIndex < sourceText.length) {
    const ch = sourceText[rawIndex];
    append(1, ch);
  }

  if (overlayInputMode.active && Number.isInteger(overlayInputMode.start)) {
    const start = Math.max(0, Math.min(sourceText.length, overlayInputMode.start));
    const tokenText = `\\${overlayInputMode.buffer}`;
    const before = displayText.slice(0, start);
    const after = displayText.slice(start);
    displayText = `${before}${tokenText}${after}`;

    for (let i = start + 1; i < rawToDisplay.length; i += 1) {
      rawToDisplay[i] += tokenText.length;
    }

    return {
      displayText,
      rawToDisplay,
      activeDisplayRange: {
        start,
        end: start + tokenText.length,
      },
    };
  }

  return {
    displayText,
    rawToDisplay,
    activeDisplayRange: null,
  };
}

function highlightClassFor(kind) {
  if (kind === 'CONSTANT') {
    return 'hl-constant';
  }
  if (kind === 'FREE_VARIABLE') {
    return 'hl-free-var';
  }
  if (kind === 'BOUND_VARIABLE') {
    return 'hl-bound-var';
  }
  if (kind === 'ACTIVE_CONSTANT_USAGE') {
    return 'hl-active-const-usage';
  }
  if (kind === 'ACTIVE_CONSTANT_DEFINITION') {
    return 'hl-active-const-definition';
  }
  if (kind === 'ACTIVE_BOUND_USAGE') {
    return 'hl-active-bound-usage';
  }
  if (kind === 'ACTIVE_BOUND_DEFINITION') {
    return 'hl-active-bound-definition';
  }
  return '';
}

function buildEditorHighlightHtml(text, spans) {
  const sourceText = text || '';
  const projection = buildOverlayProjection(sourceText);
  lastProjection = projection;
  const overlayText = projection.displayText;
  const validSpans = (spans || [])
    .map((span) => {
      const start = Number.isFinite(span.startOffset) ? Math.max(0, Math.min(sourceText.length, span.startOffset)) : 0;
      const end = Number.isFinite(span.endOffset) ? Math.max(start, Math.min(sourceText.length, span.endOffset)) : start;
      return {
        start: projection.rawToDisplay[start],
        end: projection.rawToDisplay[end],
        kind: highlightClassFor(span.kind),
      };
    })
    .filter((span) => span.end > span.start && span.kind);

  if (projection.activeDisplayRange) {
    const activeStart = projection.activeDisplayRange.start;
    const activeEnd = projection.activeDisplayRange.end;
    if (activeEnd > activeStart) {
      validSpans.push({
        start: activeStart,
        end: activeEnd,
        kind: 'hl-slash-mode-active',
      });
    }
  }

  if (validSpans.length === 0) {
    return `${escapeHtml(overlayText)}\n`;
  }

  const starts = new Map();
  const ends = new Map();
  const points = new Set([0, overlayText.length]);

  validSpans.forEach((span) => {
    if (!starts.has(span.start)) {
      starts.set(span.start, []);
    }
    starts.get(span.start).push(span.kind);

    if (!ends.has(span.end)) {
      ends.set(span.end, []);
    }
    ends.get(span.end).push(span.kind);

    points.add(span.start);
    points.add(span.end);
  });

  const sortedPoints = [...points].sort((a, b) => a - b);
  const active = new Map();
  let result = '';

  for (let i = 0; i < sortedPoints.length - 1; i += 1) {
    const point = sortedPoints[i];
    const nextPoint = sortedPoints[i + 1];

    (ends.get(point) || []).forEach((kind) => {
      const count = active.get(kind) || 0;
      if (count <= 1) {
        active.delete(kind);
      } else {
        active.set(kind, count - 1);
      }
    });

    (starts.get(point) || []).forEach((kind) => {
      active.set(kind, (active.get(kind) || 0) + 1);
    });

    if (nextPoint <= point) {
      continue;
    }

    const segment = escapeHtml(overlayText.slice(point, nextPoint));
    if (segment.length === 0) {
      continue;
    }

    if (active.size === 0) {
      result += segment;
    } else {
      const className = [...active.keys()].sort().join(' ');
      result += `<span class="${className}">${segment}</span>`;
    }
  }

  return `${result}\n`;
}

function updateEditorCaretOverlay() {
  const isFocused = document.activeElement === editorInput;
  const start = editorInput.selectionStart ?? 0;
  const end = editorInput.selectionEnd ?? start;
  if (!isFocused || start !== end || !lastProjection || !Array.isArray(lastProjection.rawToDisplay)) {
    editorCaretOverlay.style.display = 'none';
    return;
  }

  const displayOffset = lastProjection.rawToDisplay[Math.max(0, Math.min(start, lastProjection.rawToDisplay.length - 1))] ?? 0;
  const before = escapeHtml((lastProjection.displayText || '').slice(0, displayOffset));

  const probe = document.createElement('div');
  probe.style.position = 'absolute';
  probe.style.visibility = 'hidden';
  probe.style.pointerEvents = 'none';
  probe.style.whiteSpace = 'pre-wrap';
  probe.style.overflowWrap = 'break-word';
  probe.style.font = window.getComputedStyle(editorInput).font;
  probe.style.lineHeight = window.getComputedStyle(editorInput).lineHeight;
  probe.style.padding = '12px';
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

function syncEditorOverlayScroll() {
  editorHighlight.style.transform = `translate(${-editorInput.scrollLeft}px, ${-editorInput.scrollTop}px)`;
  updateEditorCaretOverlay();
}

function renderEditorHighlight(payload) {
  const sourceText = typeof payload?.sourceText === 'string' ? payload.sourceText : editorInput.value;
  const highlights = payload?.textHighlights || [];
  editorHighlight.innerHTML = buildEditorHighlightHtml(sourceText, highlights);
  syncEditorOverlayScroll();
  updateEditorCaretOverlay();
}

function renderEditorWithCurrentHighlights() {
  renderEditorHighlight({
    sourceText: editorInput.value,
    textHighlights: lastPayload?.textHighlights || [],
  });
}

function notifyCaretMoved() {
  const caretOffset = editorInput.selectionStart || 0;
  if (caretOffset === lastSentCaretOffset) {
    return;
  }
  lastSentCaretOffset = caretOffset;
  notifyHostEditorCaretMovedFn(caretOffset);
}

function applyEditorTextChange(nextText, caretOffset) {
  editorInput.value = nextText;
  editorInput.setSelectionRange(caretOffset, caretOffset);
  renderEditorWithCurrentHighlights();
  if (!suppressHostNotify) {
    notifyHostTextChangedFn(editorInput.value);
  }
  notifyCaretMoved();
}

function findBackslashAtomAt(text, index) {
  if (index < 0 || index >= text.length) {
    return null;
  }

  let start = index;
  while (start > 0 && !isWhitespace(text[start - 1])) {
    start -= 1;
  }
  if (text[start] !== '\\') {
    return null;
  }

  let end = start + 1;
  while (end < text.length && !isWhitespace(text[end])) {
    end += 1;
  }

  if (end - start <= 1 || index < start || index >= end) {
    return null;
  }

  return { start, end };
}

function renderCurrent(resetView) {
  lastPayload.symbolReplacements = activeSymbolReplacements;
  renderedPayload = compactifyGraph(lastPayload, compactToggle.checked);
  renderDefinitionBarFn(definitionBar, renderedPayload, notifyHostDefinitionSelectedFn);

  if (resetView) {
    renderer.fitToContent(renderedPayload);
  }
  renderer.draw(renderedPayload);
}

window.renderFromHost = (payload) => {
  const previousNodeCount = (lastPayload.nodes || []).length;
  lastPayload = payload || lastPayload;
  activeSymbolReplacements = resolveSymbolReplacements(lastPayload);
  refreshSlashModeForSelection();
  renderEditorHighlight(lastPayload);
  const shouldResetView = previousNodeCount === 0 && (lastPayload.nodes || []).length > 0;
  renderCurrent(shouldResetView);
};

if (window.__deltaQueuedPayload !== null) {
  window.renderFromHost(window.__deltaQueuedPayload);
  window.__deltaQueuedPayload = null;
}

window.setEditorTextFromHost = (text) => {
  suppressHostNotify = true;
  editorInput.value = text || '';
  resetSlashMode();
  renderEditorWithCurrentHighlights();
  syncEditorOverlayScroll();
  lastSentCaretOffset = -1;
  suppressHostNotify = false;
  editorInput.focus();
  notifyCaretMoved();
};

if (window.__deltaQueuedEditorText !== null) {
  window.setEditorTextFromHost(window.__deltaQueuedEditorText);
  window.__deltaQueuedEditorText = null;
}

editorInput.addEventListener('input', () => {
  refreshSlashModeForSelection();
  renderEditorWithCurrentHighlights();
  if (!suppressHostNotify) {
    notifyHostTextChangedFn(editorInput.value);
  }
  notifyCaretMoved();
});

editorInput.addEventListener('keydown', (event) => {
  if (event.altKey || event.ctrlKey || event.metaKey) {
    return;
  }

  const selectionStart = editorInput.selectionStart ?? 0;
  const selectionEnd = editorInput.selectionEnd ?? selectionStart;

  const text = editorInput.value;

  if (overlayInputMode.active) {
    if (event.key === 'Escape') {
      event.preventDefault();
      resetSlashMode();
      renderEditorWithCurrentHighlights();
      return;
    }

    if (event.key === 'Backspace') {
      event.preventDefault();
      if (overlayInputMode.buffer.length > 0) {
        overlayInputMode.buffer = overlayInputMode.buffer.slice(0, -1);
      } else {
        resetSlashMode();
      }
      renderEditorWithCurrentHighlights();
      return;
    }

    if (event.key === 'Delete') {
      event.preventDefault();
      resetSlashMode();
      renderEditorWithCurrentHighlights();
      return;
    }

    if (event.key === ' ' || event.key === 'Tab') {
      event.preventDefault();
      const symbol = resolveSpecialSymbol(overlayInputMode.buffer);
      if (symbol) {
        const start = overlayInputMode.start;
        const nextText = text.slice(0, start) + symbol + text.slice(start);
        resetSlashMode();
        applyEditorTextChange(nextText, start + 1);
      } else {
        const rawToken = `\\${overlayInputMode.buffer}`;
        const start = overlayInputMode.start;
        const nextText = text.slice(0, start) + rawToken + text.slice(start);
        resetSlashMode();
        applyEditorTextChange(nextText, start + rawToken.length);
      }
      return;
    }

    if (event.key.length === 1 && !isWhitespace(event.key)) {
      event.preventDefault();
      overlayInputMode.buffer += event.key;
      renderEditorWithCurrentHighlights();
      return;
    }

    return;
  }

  if (event.key === '\\' && selectionStart === selectionEnd) {
    event.preventDefault();
    enterSlashMode(selectionStart);
    renderEditorWithCurrentHighlights();
    return;
  }

  if (selectionStart !== selectionEnd) {
    return;
  }

  if ((event.key !== 'Backspace' && event.key !== 'Delete')) {
    return;
  }

  if (event.key === 'Backspace') {
    const atom = findBackslashAtomAt(text, selectionStart - 1);
    if (!atom || selectionStart <= atom.start || selectionStart > atom.end) {
      return;
    }
    event.preventDefault();
    applyEditorTextChange(text.slice(0, atom.start) + text.slice(atom.end), atom.start);
    return;
  }

  const atom = findBackslashAtomAt(text, selectionStart);
  if (!atom || selectionStart < atom.start || selectionStart >= atom.end) {
    return;
  }
  event.preventDefault();
  applyEditorTextChange(text.slice(0, atom.start) + text.slice(atom.end), atom.start);
});

editorInput.addEventListener('scroll', syncEditorOverlayScroll);
editorInput.addEventListener('keyup', () => {
  if (refreshSlashModeForSelection()) {
    renderEditorWithCurrentHighlights();
  }
  notifyCaretMoved();
  updateEditorCaretOverlay();
});
editorInput.addEventListener('click', () => {
  if (refreshSlashModeForSelection()) {
    renderEditorWithCurrentHighlights();
  }
  notifyCaretMoved();
  updateEditorCaretOverlay();
});
editorInput.addEventListener('select', () => {
  if (refreshSlashModeForSelection()) {
    renderEditorWithCurrentHighlights();
  }
  notifyCaretMoved();
  updateEditorCaretOverlay();
});
editorInput.addEventListener('focus', () => {
  if (refreshSlashModeForSelection()) {
    renderEditorWithCurrentHighlights();
  }
  notifyCaretMoved();
  updateEditorCaretOverlay();
});
editorInput.addEventListener('blur', () => {
  editorCaretOverlay.style.display = 'none';
});
document.addEventListener('selectionchange', () => {
  if (document.activeElement === editorInput) {
    if (refreshSlashModeForSelection()) {
      renderEditorWithCurrentHighlights();
    }
    notifyCaretMoved();
    updateEditorCaretOverlay();
  }
});

compactToggle.addEventListener('change', () => {
  renderCurrent(true);
});

window.addEventListener('resize', () => {
  renderer.draw(renderedPayload);
});

canvas.addEventListener('wheel', (event) => {
  event.preventDefault();

  const rect = canvas.getBoundingClientRect();
  const mouseX = event.clientX - rect.left;
  const mouseY = event.clientY - rect.top;
  const worldBefore = renderer.screenToWorld(mouseX, mouseY);

  const zoomFactor = Math.exp(-event.deltaY * 0.0012);
  const newScale = renderer.clamp(view.scale * zoomFactor, view.minScale, view.maxScale);

  view.scale = newScale;
  view.offsetX = mouseX - worldBefore.x * view.scale;
  view.offsetY = mouseY - worldBefore.y * view.scale;

  renderer.draw(renderedPayload);
}, { passive: false });

canvas.addEventListener('mousedown', (event) => {
  if (event.button !== 0) {
    return;
  }
  view.isPanning = true;
  view.lastX = event.clientX;
  view.lastY = event.clientY;
  canvas.style.cursor = 'grabbing';
});

window.addEventListener('mousemove', (event) => {
  if (!view.isPanning) {
    return;
  }

  const dx = event.clientX - view.lastX;
  const dy = event.clientY - view.lastY;
  view.lastX = event.clientX;
  view.lastY = event.clientY;

  view.offsetX += dx;
  view.offsetY += dy;
  renderer.draw(renderedPayload);
});

window.addEventListener('mouseup', () => {
  if (!view.isPanning) {
    return;
  }
  view.isPanning = false;
  canvas.style.cursor = 'grab';
});

canvas.style.cursor = 'grab';
resetSlashMode();
renderEditorHighlight(lastPayload);
renderCurrent(true);
