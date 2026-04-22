const editorInput = document.getElementById('editorInput');
const editorHighlight = document.getElementById('editorHighlight');
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
let lastPayload = {
  sourceText: '',
  diagnostics: [],
  textHighlights: [],
  definitionNames: [],
  selectedDefinitionName: null,
  freeVariableNames: [],
  nodes: [],
  blueEdges: [],
  greenEdges: [],
};
let renderedPayload = lastPayload;

function escapeHtml(text) {
  return text
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');
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
  if (sourceText.length === 0) {
    return '\n';
  }

  const validSpans = (spans || [])
    .map((span) => {
      const start = Number.isFinite(span.startOffset) ? Math.max(0, Math.min(sourceText.length, span.startOffset)) : 0;
      const end = Number.isFinite(span.endOffset) ? Math.max(start, Math.min(sourceText.length, span.endOffset)) : start;
      return {
        start,
        end,
        kind: highlightClassFor(span.kind),
      };
    })
    .filter((span) => span.end > span.start && span.kind);

  if (validSpans.length === 0) {
    return `${escapeHtml(sourceText)}\n`;
  }

  const starts = new Map();
  const ends = new Map();
  const points = new Set([0, sourceText.length]);

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

    const segment = escapeHtml(sourceText.slice(point, nextPoint));
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

function syncEditorOverlayScroll() {
  editorHighlight.style.transform = `translate(${-editorInput.scrollLeft}px, ${-editorInput.scrollTop}px)`;
}

function renderEditorHighlight(payload) {
  const sourceText = typeof payload?.sourceText === 'string' ? payload.sourceText : editorInput.value;
  const highlights = payload?.textHighlights || [];
  editorHighlight.innerHTML = buildEditorHighlightHtml(sourceText, highlights);
  syncEditorOverlayScroll();
}

function notifyCaretMoved() {
  const caretOffset = editorInput.selectionStart || 0;
  if (caretOffset === lastSentCaretOffset) {
    return;
  }
  lastSentCaretOffset = caretOffset;
  notifyHostEditorCaretMovedFn(caretOffset);
}

function renderCurrent(resetView) {
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
  renderEditorHighlight({ sourceText: editorInput.value, textHighlights: [] });
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
  renderEditorHighlight({ sourceText: editorInput.value, textHighlights: [] });
  if (!suppressHostNotify) {
    notifyHostTextChangedFn(editorInput.value);
  }
  notifyCaretMoved();
});

editorInput.addEventListener('scroll', syncEditorOverlayScroll);
editorInput.addEventListener('keyup', notifyCaretMoved);
editorInput.addEventListener('click', notifyCaretMoved);
editorInput.addEventListener('select', notifyCaretMoved);
editorInput.addEventListener('focus', notifyCaretMoved);
document.addEventListener('selectionchange', () => {
  if (document.activeElement === editorInput) {
    notifyCaretMoved();
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
renderEditorHighlight(lastPayload);
renderCurrent(true);
