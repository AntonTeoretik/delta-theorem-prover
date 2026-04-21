(() => {
  const editorInput = document.getElementById('editorInput');
  const canvas = document.getElementById('vizCanvas');
  const ctx = canvas.getContext('2d');
  const stats = document.getElementById('stats');
  const view = {
    scale: 1,
    offsetX: 0,
    offsetY: 0,
    minScale: 0.35,
    maxScale: 2.8,
    isPanning: false,
    lastX: 0,
    lastY: 0
  };

  let suppressHostNotify = false;
  let lastPayload = {
    sourceText: '',
    diagnostics: [],
    freeVariableNames: [],
    nodes: [],
    blueEdges: [],
    greenEdges: []
  };

  function notifyHostTextChanged(text) {
    if (typeof window.cefQuery !== 'function') {
      return;
    }

    window.cefQuery({
      request: `editorTextChanged:${text}`,
      onSuccess: () => {},
      onFailure: () => {}
    });
  }

  function resizeCanvasToDisplaySize() {
    const rect = canvas.getBoundingClientRect();
    const width = Math.max(1, Math.floor(rect.width));
    const height = Math.max(1, Math.floor(rect.height));
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
    }
  }

  function nodeCenterX(node) {
    return node.x + node.width / 2;
  }

  function portPoint(node, side, color, index) {
    const blueCount = side === 'in' ? (node.blueInputCount || 0) : (node.blueOutputCount || 0);
    const greenCount = side === 'in' ? (node.greenInputCount || 0) : (node.greenOutputCount || 0);
    const total = Math.max(1, blueCount + greenCount);
    const slot = color === 'blue' ? index : blueCount + index;
    const gap = node.width / (total + 1);
    return {
      x: node.x + gap * (slot + 1),
      y: side === 'in' ? node.y : node.y + node.height
    };
  }

  function blueOutPoint(node, port) {
    return portPoint(node, 'out', 'blue', port);
  }

  function blueInPoint(node, port) {
    return portPoint(node, 'in', 'blue', port);
  }

  function greenOutPoint(node, port) {
    return portPoint(node, 'out', 'green', port);
  }

  function greenInPoint(node, port) {
    return portPoint(node, 'in', 'green', port);
  }

  function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
  }

  function screenToWorld(x, y) {
    return {
      x: (x - view.offsetX) / view.scale,
      y: (y - view.offsetY) / view.scale
    };
  }

  function fitToContent(payload) {
    const nodes = payload.nodes || [];
    if (nodes.length === 0) {
      view.scale = 1;
      view.offsetX = 0;
      view.offsetY = 0;
      return;
    }

    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;

    nodes.forEach((node) => {
      minX = Math.min(minX, node.x);
      minY = Math.min(minY, node.y);
      maxX = Math.max(maxX, node.x + node.width);
      maxY = Math.max(maxY, node.y + node.height);
    });

    const pad = 40;
    const contentWidth = Math.max(1, maxX - minX + pad * 2);
    const contentHeight = Math.max(1, maxY - minY + pad * 2);
    const scaleX = canvas.width / contentWidth;
    const scaleY = canvas.height / contentHeight;
    view.scale = clamp(Math.min(scaleX, scaleY), view.minScale, 1.2);
    view.offsetX = (canvas.width - contentWidth * view.scale) / 2 - (minX - pad) * view.scale;
    view.offsetY = (canvas.height - contentHeight * view.scale) / 2 - (minY - pad) * view.scale;
  }

  function drawSpline(from, to, color, lift) {

    ctx.strokeStyle = color;
    ctx.lineWidth = 2.2;
    ctx.beginPath();
    ctx.moveTo(from.x, from.y);
    ctx.bezierCurveTo(
      from.x,
      from.y + lift,
      to.x,
      to.y - lift,
      to.x,
      to.y
    );
    ctx.stroke();
  }

  function drawRootNode(node, freeNames) {
    ctx.fillStyle = '#30353d';
    ctx.strokeStyle = '#ffb173';
    ctx.lineWidth = 1.4;
    ctx.beginPath();
    ctx.roundRect(node.x, node.y, node.width, node.height, 10);
    ctx.fill();
    ctx.stroke();

    ctx.fillStyle = '#f3e8db';
    ctx.font = '13px "Segoe UI", sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText('ROOT', nodeCenterX(node), node.y + 17);

    ctx.fillStyle = '#b9b0a3';
    ctx.font = '11px "Segoe UI", sans-serif';
    const caption = freeNames.length > 0 ? freeNames.join(', ') : 'no free vars';
    ctx.fillText(caption, nodeCenterX(node), node.y + 32);
  }

  function drawSquareNode(node, text, textColor) {
    ctx.fillStyle = '#323841';
    ctx.strokeStyle = '#ffb173';
    ctx.lineWidth = 1.2;
    ctx.beginPath();
    ctx.roundRect(node.x, node.y, node.width, node.height, 7);
    ctx.fill();
    ctx.stroke();

    if (text) {
      ctx.fillStyle = textColor || '#f3e8db';
      ctx.font = '15px "Segoe UI", sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(text, nodeCenterX(node), node.y + node.height / 2);
      ctx.textBaseline = 'alphabetic';
    }
  }

  function drawPorts(node) {
    for (let i = 0; i < (node.blueInputCount || 0); i += 1) {
      const p = blueInPoint(node, i);
      ctx.fillStyle = '#68a7ff';
      ctx.beginPath();
      ctx.arc(p.x, p.y, 3.4, 0, Math.PI * 2);
      ctx.fill();
    }

    for (let i = 0; i < (node.blueOutputCount || 0); i += 1) {
      const p = blueOutPoint(node, i);
      ctx.fillStyle = '#68a7ff';
      ctx.beginPath();
      ctx.arc(p.x, p.y, 3.4, 0, Math.PI * 2);
      ctx.fill();
    }

    for (let i = 0; i < (node.greenInputCount || 0); i += 1) {
      const p = greenInPoint(node, i);
      ctx.fillStyle = '#72ca86';
      ctx.beginPath();
      ctx.arc(p.x, p.y, 3.2, 0, Math.PI * 2);
      ctx.fill();
    }

    for (let i = 0; i < (node.greenOutputCount || 0); i += 1) {
      const p = greenOutPoint(node, i);
      ctx.fillStyle = '#72ca86';
      ctx.beginPath();
      ctx.arc(p.x, p.y, 3.2, 0, Math.PI * 2);
      ctx.fill();
    }
  }

  function draw(payload) {
    resizeCanvasToDisplaySize();

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.setTransform(view.scale, 0, 0, view.scale, view.offsetX, view.offsetY);

    const nodeById = new Map((payload.nodes || []).map((node) => [node.id, node]));

    (payload.blueEdges || []).forEach((edge) => {
      const from = nodeById.get(edge.fromNodeId);
      const to = nodeById.get(edge.toNodeId);
      if (!from || !to) {
        return;
      }
      drawSpline(blueOutPoint(from, edge.fromPort), blueInPoint(to, edge.toPort), '#6ea8ff', 44);
    });

    (payload.greenEdges || []).forEach((edge) => {
      const from = nodeById.get(edge.fromNodeId);
      const to = nodeById.get(edge.toNodeId);
      if (!from || !to) {
        return;
      }
      drawSpline(greenOutPoint(from, edge.fromPort), greenInPoint(to, edge.toPort), 'rgba(122, 205, 139, 0.62)', 34);
    });

    (payload.nodes || []).forEach((node) => {
      if (node.type === 'ROOT') {
        drawRootNode(node, payload.freeVariableNames || []);
      } else if (node.type === 'APP') {
        drawSquareNode(node, '•', '#f3e8db');
      } else if (node.type === 'LAMBDA') {
        drawSquareNode(node, 'λ', '#f3e8db');
      } else if (node.type === 'CONST') {
        drawSquareNode(node, node.label || 'c', '#ffb173');
      } else if (node.type === 'VAR') {
        drawSquareNode(node, node.label || 'x', 'rgba(140, 220, 156, 0.74)');
      }
      drawPorts(node);
    });

    const diagnostics = payload.diagnostics || [];
    if (diagnostics.length > 0) {
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.fillStyle = '#f0b27a';
      ctx.font = '12px "Consolas", monospace';
      diagnostics.slice(0, 5).forEach((diag, idx) => {
        const text = `Error ${diag.line}:${diag.column} ${diag.message}`;
        ctx.fillText(text, 14, canvas.height - 16 - idx * 16);
      });
    }

    stats.textContent = `Nodes: ${(payload.nodes || []).length} | Blue edges: ${(payload.blueEdges || []).length} | Green edges: ${(payload.greenEdges || []).length} | Free vars: ${(payload.freeVariableNames || []).length}`;
  }

  window.renderFromHost = (payload) => {
    const previousNodeCount = (lastPayload.nodes || []).length;
    lastPayload = payload || lastPayload;
    if (previousNodeCount === 0 && (lastPayload.nodes || []).length > 0) {
      fitToContent(lastPayload);
    }
    draw(lastPayload);
  };

  window.setEditorTextFromHost = (text) => {
    suppressHostNotify = true;
    editorInput.value = text || '';
    suppressHostNotify = false;
    editorInput.focus();
  };

  editorInput.addEventListener('input', () => {
    if (!suppressHostNotify) {
      notifyHostTextChanged(editorInput.value);
    }
  });

  window.addEventListener('resize', () => {
    draw(lastPayload);
  });

  canvas.addEventListener('wheel', (event) => {
    event.preventDefault();

    const rect = canvas.getBoundingClientRect();
    const mouseX = event.clientX - rect.left;
    const mouseY = event.clientY - rect.top;
    const worldBefore = screenToWorld(mouseX, mouseY);

    const zoomFactor = Math.exp(-event.deltaY * 0.0012);
    const newScale = clamp(view.scale * zoomFactor, view.minScale, view.maxScale);

    view.scale = newScale;
    view.offsetX = mouseX - worldBefore.x * view.scale;
    view.offsetY = mouseY - worldBefore.y * view.scale;

    draw(lastPayload);
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
    draw(lastPayload);
  });

  window.addEventListener('mouseup', () => {
    if (!view.isPanning) {
      return;
    }
    view.isPanning = false;
    canvas.style.cursor = 'grab';
  });

  canvas.style.cursor = 'grab';

  draw(lastPayload);
})();
