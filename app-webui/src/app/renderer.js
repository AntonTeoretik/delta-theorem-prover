function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function displayName(rawName, symbolReplacements) {
  if (typeof rawName !== 'string') {
    return '-';
  }
  if (rawName.startsWith('$')) {
    return rawName.slice(1);
  }
  if (rawName.startsWith('\\')) {
    const withoutSlash = rawName.slice(1);
    const mapped = symbolReplacements?.[withoutSlash] || symbolReplacements?.[rawName];
    return typeof mapped === 'string' && mapped.length > 0 ? mapped : rawName;
  }
  return rawName;
}

function nodeCenterX(node) {
  return node.x + node.width / 2;
}

function portPoint(node, side, color, index) {
  const blueCount = side === 'in' ? (node.blueInputCount || 0) : (node.blueOutputCount || 0);
  const greenCount = side === 'in' ? (node.greenInputCount || 0) : (node.greenOutputCount || 0);
  const total = Math.max(1, blueCount + greenCount);
  let slot = color === 'blue' ? index : blueCount + index;

  if (side === 'out' && Array.isArray(node.portOrderOut) && node.portOrderOut.length > 0) {
    const mapped = node.portOrderOut.findIndex((entry) => entry.color === color && entry.index === index);
    if (mapped >= 0) {
      slot = mapped;
    }
  }

  const gap = node.width / (total + 1);
  return {
    x: node.x + gap * (slot + 1),
    y: side === 'in' ? node.y : node.y + node.height,
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

function drawSpline(ctx, from, to, color, lift) {
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
    to.y,
  );
  ctx.stroke();
}

function drawRootNode(ctx, node, freeNames) {
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

function drawLambdaNode(ctx, node) {
  ctx.fillStyle = '#323841';
  ctx.strokeStyle = '#ffb173';
  ctx.lineWidth = 1.2;
  ctx.beginPath();
  ctx.roundRect(node.x, node.y, node.width, node.height, 7);
  ctx.fill();
  ctx.stroke();

  ctx.fillStyle = '#f3e8db';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.font = '15px "Segoe UI", sans-serif';
  ctx.fillText('λ', nodeCenterX(node), node.y + node.height / 2);
  ctx.textBaseline = 'alphabetic';

  if (node.label && (node.greenOutputCount || 0) > 0) {
    ctx.fillStyle = 'rgba(140, 220, 156, 0.74)';
    ctx.font = '11px "Segoe UI", sans-serif';
    ctx.fillText(node.label, nodeCenterX(node), node.y + node.height - 6);
  }
}

function drawPiNode(ctx, node) {
  ctx.fillStyle = '#323841';
  ctx.strokeStyle = '#ffb173';
  ctx.lineWidth = 1.2;
  ctx.beginPath();
  ctx.roundRect(node.x, node.y, node.width, node.height, 7);
  ctx.fill();
  ctx.stroke();

  ctx.fillStyle = '#f3e8db';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.font = '12px "Segoe UI", sans-serif';
  ctx.fillText('PI', nodeCenterX(node), node.y + node.height / 2);
  ctx.textBaseline = 'alphabetic';
}

function drawSquareNode(ctx, node, text, textColor, fillColor, borderColor) {
  ctx.fillStyle = fillColor || '#323841';
  ctx.strokeStyle = borderColor || '#ffb173';
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

function drawAppNode(ctx, node) {
  drawSquareNode(ctx, node, '', '#f3e8db');
  const dotCount = Math.max(1, (node.blueOutputCount || 2) - 1);
  const centerX = nodeCenterX(node);
  const centerY = node.y + node.height / 2;
  const spacing = 11;
  const startX = centerX - ((dotCount - 1) * spacing) / 2;

  ctx.fillStyle = '#f3e8db';
  for (let i = 0; i < dotCount; i += 1) {
    ctx.beginPath();
    ctx.arc(startX + i * spacing, centerY, 2.7, 0, Math.PI * 2);
    ctx.fill();
  }
}

function drawPorts(ctx, node, isFreeVariableNode) {
  const greenPortColor = isFreeVariableNode ? 'rgba(157, 167, 255, 0.88)' : '#72ca86';
  const rootGreenPortColor = node.type === 'ROOT' ? 'rgba(157, 167, 255, 0.88)' : greenPortColor;

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
    ctx.fillStyle = greenPortColor;
    ctx.beginPath();
    ctx.arc(p.x, p.y, 3.2, 0, Math.PI * 2);
    ctx.fill();
  }

  for (let i = 0; i < (node.greenOutputCount || 0); i += 1) {
    const p = greenOutPoint(node, i);
    ctx.fillStyle = rootGreenPortColor;
    ctx.beginPath();
    ctx.arc(p.x, p.y, 3.2, 0, Math.PI * 2);
    ctx.fill();
  }
}

function resizeCanvasToDisplaySize(canvas) {
  const rect = canvas.getBoundingClientRect();
  const width = Math.max(1, Math.floor(rect.width));
  const height = Math.max(1, Math.floor(rect.height));
  if (canvas.width !== width || canvas.height !== height) {
    canvas.width = width;
    canvas.height = height;
  }
}

function createRenderer({ canvas, ctx, statsElement, view }) {
  function screenToWorld(x, y) {
    return {
      x: (x - view.offsetX) / view.scale,
      y: (y - view.offsetY) / view.scale,
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

  function draw(payload) {
    resizeCanvasToDisplaySize(canvas);

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.setTransform(view.scale, 0, 0, view.scale, view.offsetX, view.offsetY);

    const nodeById = new Map((payload.nodes || []).map((node) => [node.id, node]));
    const rootNodeIds = new Set((payload.nodes || []).filter((node) => node.type === 'ROOT').map((node) => node.id));
    const freeVarNodeIds = new Set();

    (payload.greenEdges || []).forEach((edge) => {
      if (rootNodeIds.has(edge.fromNodeId)) {
        freeVarNodeIds.add(edge.toNodeId);
      }
    });

    (payload.blueEdges || []).forEach((edge) => {
      const from = nodeById.get(edge.fromNodeId);
      const to = nodeById.get(edge.toNodeId);
      if (!from || !to) {
        return;
      }
      drawSpline(ctx, blueOutPoint(from, edge.fromPort), blueInPoint(to, edge.toPort), '#6ea8ff', 44);
    });

    (payload.nodes || []).forEach((node) => {
      if (node.type === 'ROOT') {
        drawRootNode(ctx, node, payload.freeVariableNames || []);
      } else if (node.type === 'APP') {
        drawAppNode(ctx, node);
      } else if (node.type === 'TYPE') {
        drawSquareNode(ctx, node, ':', '#f3e8db');
      } else if (node.type === 'LAMBDA') {
        drawLambdaNode(ctx, node);
      } else if (node.type === 'PI') {
        drawPiNode(ctx, node);
      } else if (node.type === 'CONST') {
        drawSquareNode(ctx, node, displayName(node.label || 'c', payload.symbolReplacements), '#ffd8b4', '#4d3528', '#ffb173');
      } else if (node.type === 'VAR') {
        if (freeVarNodeIds.has(node.id)) {
          drawSquareNode(ctx, node, node.label || 'x', 'rgba(178, 183, 255, 0.92)', '#34384d', '#9da7ff');
        } else {
          drawSquareNode(ctx, node, node.label || 'x', 'rgba(170, 226, 176, 0.84)', '#2f4437', '#89d49a');
        }
      } else if (node.type === 'META') {
        drawSquareNode(ctx, node, node.label || `?m${node.id}`, 'rgba(170, 225, 255, 0.94)', '#2d3c49', '#78bcd9');
      }
      drawPorts(ctx, node, freeVarNodeIds.has(node.id));
    });

    (payload.greenEdges || []).forEach((edge) => {
      const from = nodeById.get(edge.fromNodeId);
      const to = nodeById.get(edge.toNodeId);
      if (!from || !to) {
        return;
      }
      const edgeColor = rootNodeIds.has(edge.fromNodeId)
        ? 'rgba(157, 167, 255, 0.42)'
        : 'rgba(122, 205, 139, 0.38)';
      drawSpline(ctx, greenOutPoint(from, edge.fromPort), greenInPoint(to, edge.toPort), edgeColor, 34);
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

    const selected = payload.selectedDefinitionName
      ? displayName(payload.selectedDefinitionName, payload.symbolReplacements)
      : '-';
    statsElement.textContent =
      `Selected: ${selected} | Nodes: ${(payload.nodes || []).length} | Blue edges: ${(payload.blueEdges || []).length} | Green edges: ${(payload.greenEdges || []).length} | Free vars: ${(payload.freeVariableNames || []).length}`;
  }

  return {
    draw,
    fitToContent,
    screenToWorld,
    clamp,
  };
}

window.DeltaRenderer = {
  createRenderer,
};
