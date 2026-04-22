const editorInput = document.getElementById('editorInput');
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

const { notifyHostDefinitionSelected: notifyHostDefinitionSelectedFn, notifyHostTextChanged: notifyHostTextChangedFn } = bridgeApi;
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
let lastPayload = {
  sourceText: '',
  diagnostics: [],
  definitionNames: [],
  selectedDefinitionName: null,
  freeVariableNames: [],
  nodes: [],
  blueEdges: [],
  greenEdges: [],
};
let renderedPayload = lastPayload;

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
  suppressHostNotify = false;
  editorInput.focus();
};

if (window.__deltaQueuedEditorText !== null) {
  window.setEditorTextFromHost(window.__deltaQueuedEditorText);
  window.__deltaQueuedEditorText = null;
}

editorInput.addEventListener('input', () => {
  if (!suppressHostNotify) {
    notifyHostTextChangedFn(editorInput.value);
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
renderCurrent(true);
