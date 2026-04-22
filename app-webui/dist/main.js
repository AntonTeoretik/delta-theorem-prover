(() => {
  const editorInput = document.getElementById('editorInput');
  const canvas = document.getElementById('vizCanvas');
  const ctx = canvas.getContext('2d');
  const stats = document.getElementById('stats');
  const compactToggle = document.getElementById('compactToggle');
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
  let renderedPayload = lastPayload;

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

  function layoutGraph(payload) {
    const nodes = payload.nodes || [];
    const edges = payload.blueEdges || [];
    const nodeById = new Map(nodes.map((node) => [node.id, node]));
    const root = nodes.find((node) => node.type === 'ROOT');
    if (!root) {
      return;
    }

    const children = new Map();
    edges.forEach((edge) => {
      if (!children.has(edge.fromNodeId)) {
        children.set(edge.fromNodeId, []);
      }
      children.get(edge.fromNodeId).push(edge);
    });
    children.forEach((value) => value.sort((a, b) => a.fromPort - b.fromPort));

    const subtreeWidth = new Map();
    const siblingGap = 36;
    const depthGap = 92;
    const leftMargin = 40;
    const topMargin = 36;

    function measure(nodeId) {
      if (subtreeWidth.has(nodeId)) {
        return subtreeWidth.get(nodeId);
      }
      const node = nodeById.get(nodeId);
      if (!node) {
        return 0;
      }
      const childEdges = children.get(nodeId) || [];
      let width = node.width;
      if (childEdges.length > 0) {
        const totalChildrenWidth = childEdges
          .map((edge) => measure(edge.toNodeId))
          .reduce((sum, value) => sum + value, 0) + siblingGap * (childEdges.length - 1);
        width = Math.max(width, totalChildrenWidth);
      }
      subtreeWidth.set(nodeId, width);
      return width;
    }

    function place(nodeId, left, depth) {
      const node = nodeById.get(nodeId);
      if (!node) {
        return;
      }
      const width = subtreeWidth.get(nodeId) || node.width;
      node.x = left + (width - node.width) / 2;
      node.y = topMargin + depth * depthGap;

      const childEdges = children.get(nodeId) || [];
      let childLeft = left;
      childEdges.forEach((edge) => {
        const childWidth = subtreeWidth.get(edge.toNodeId) || 0;
        place(edge.toNodeId, childLeft, depth + 1);
        childLeft += childWidth + siblingGap;
      });
    }

    measure(root.id);
    place(root.id, leftMargin, 0);
  }

  function compactify(payload) {
    const model = {
      ...payload,
      nodes: (payload.nodes || []).map((node) => ({ ...node })),
      blueEdges: (payload.blueEdges || []).map((edge) => ({ ...edge })),
      greenEdges: (payload.greenEdges || []).map((edge) => ({ ...edge }))
    };

    if (!compactToggle.checked) {
      return model;
    }

    const nodeById = new Map(model.nodes.map((node) => [node.id, node]));
    const outgoing = new Map();
    const incoming = new Map();

    model.blueEdges.forEach((edge) => {
      if (!outgoing.has(edge.fromNodeId)) {
        outgoing.set(edge.fromNodeId, []);
      }
      outgoing.get(edge.fromNodeId).push(edge);

      if (!incoming.has(edge.toNodeId)) {
        incoming.set(edge.toNodeId, []);
      }
      incoming.get(edge.toNodeId).push(edge);
    });

    outgoing.forEach((list) => list.sort((a, b) => a.fromPort - b.fromPort));

    const processed = new Set();
    const removeNodeIds = new Set();
    const removeEdgeIds = new Set();
    const newEdges = [];
    let compactEdgeIndex = 0;

    function firstEdgeByPort(nodeId, port) {
      const edges = outgoing.get(nodeId) || [];
      return edges.find((edge) => edge.fromPort === port);
    }

    model.nodes.forEach((node) => {
      if (node.type !== 'APP' || processed.has(node.id)) {
        return;
      }

      const incomingEdges = incoming.get(node.id) || [];
      const hasParentFnSpine = incomingEdges.some((edge) => {
        const parent = nodeById.get(edge.fromNodeId);
        return parent && parent.type === 'APP' && edge.fromPort === 0;
      });

      if (hasParentFnSpine) {
        return;
      }

      const chain = [];
      let currentId = node.id;
      while (true) {
        const current = nodeById.get(currentId);
        if (!current || current.type !== 'APP' || processed.has(currentId)) {
          break;
        }
        chain.push(currentId);
        processed.add(currentId);

        const fnEdge = firstEdgeByPort(currentId, 0);
        if (!fnEdge) {
          break;
        }
        const fnTarget = nodeById.get(fnEdge.toNodeId);
        if (!fnTarget || fnTarget.type !== 'APP') {
          break;
        }
        currentId = fnTarget.id;
      }

      if (chain.length <= 1) {
        return;
      }

      const topId = chain[0];
      const deepestId = chain[chain.length - 1];
      const topNode = nodeById.get(topId);
      const deepestFnEdge = firstEdgeByPort(deepestId, 0);
      if (!topNode || !deepestFnEdge) {
        return;
      }

      const argTargets = [];
      [...chain].reverse().forEach((appId) => {
        const argEdge = firstEdgeByPort(appId, 1);
        if (argEdge) {
          argTargets.push(argEdge.toNodeId);
        }
      });

      const outputTargets = [deepestFnEdge.toNodeId, ...argTargets];
      topNode.blueOutputCount = outputTargets.length;
      topNode.width = Math.max(48, 22 + outputTargets.length * 20);

      chain.forEach((appId, idx) => {
        const outEdges = outgoing.get(appId) || [];
        outEdges.forEach((edge) => removeEdgeIds.add(edge.id));
        if (idx > 0) {
          removeNodeIds.add(appId);
        }
      });

      outputTargets.forEach((targetId, port) => {
        newEdges.push({
          id: `cb${compactEdgeIndex++}`,
          fromNodeId: topId,
          toNodeId: targetId,
          fromPort: port,
          toPort: 0
        });
      });
    });

    model.nodes = model.nodes.filter((node) => !removeNodeIds.has(node.id));
    model.blueEdges = model.blueEdges
      .filter((edge) => !removeEdgeIds.has(edge.id))
      .filter((edge) => !removeNodeIds.has(edge.fromNodeId) && !removeNodeIds.has(edge.toNodeId));
    model.blueEdges.push(...newEdges);
    model.greenEdges = model.greenEdges
      .filter((edge) => !removeNodeIds.has(edge.fromNodeId) && !removeNodeIds.has(edge.toNodeId));

    compactifyLambdaChains(model);

    layoutGraph(model);
    return model;
  }

  function compactifyLambdaChains(model) {
    const nodeById = new Map(model.nodes.map((node) => [node.id, node]));
    const outgoingBlue = new Map();
    const incomingBlue = new Map();

    model.blueEdges.forEach((edge) => {
      if (!outgoingBlue.has(edge.fromNodeId)) {
        outgoingBlue.set(edge.fromNodeId, []);
      }
      outgoingBlue.get(edge.fromNodeId).push(edge);

      if (!incomingBlue.has(edge.toNodeId)) {
        incomingBlue.set(edge.toNodeId, []);
      }
      incomingBlue.get(edge.toNodeId).push(edge);
    });
    outgoingBlue.forEach((list) => list.sort((a, b) => a.fromPort - b.fromPort));

    function firstBlueEdge(nodeId, port) {
      const edges = outgoingBlue.get(nodeId) || [];
      return edges.find((edge) => edge.fromPort === port);
    }

    const processed = new Set();
    const removeNodeIds = new Set();
    const removeBlueEdgeIds = new Set();
    const edgeUpdates = [];
    let lambdaCompactEdgeIndex = 0;

    model.nodes.forEach((node) => {
      if (node.type !== 'LAMBDA' || processed.has(node.id)) {
        return;
      }

      const incoming = incomingBlue.get(node.id) || [];
      const hasLambdaParent = incoming.some((edge) => {
        const parent = nodeById.get(edge.fromNodeId);
        return parent && parent.type === 'LAMBDA' && edge.fromPort === 0;
      });
      if (hasLambdaParent) {
        return;
      }

      const chain = [];
      let currentId = node.id;
      while (true) {
        const current = nodeById.get(currentId);
        if (!current || current.type !== 'LAMBDA' || processed.has(currentId)) {
          break;
        }
        chain.push(currentId);
        processed.add(currentId);

        const nextEdge = firstBlueEdge(currentId, 0);
        if (!nextEdge) {
          break;
        }
        const nextNode = nodeById.get(nextEdge.toNodeId);
        if (!nextNode || nextNode.type !== 'LAMBDA') {
          break;
        }
        currentId = nextNode.id;
      }

      if (chain.length <= 1) {
        return;
      }

      const topId = chain[0];
      const deepestId = chain[chain.length - 1];
      const topNode = nodeById.get(topId);
      const deepestBodyEdge = firstBlueEdge(deepestId, 0);
      if (!topNode || !deepestBodyEdge) {
        return;
      }

      const usedBinders = [];
      const binderPortByNodeId = new Map();
      chain.forEach((lambdaId) => {
        const lambdaNode = nodeById.get(lambdaId);
        if (!lambdaNode) {
          return;
        }
        if ((lambdaNode.greenOutputCount || 0) > 0) {
          binderPortByNodeId.set(lambdaId, usedBinders.length);
          if (lambdaNode.label) {
            usedBinders.push(lambdaNode.label);
          }
        }
      });

      topNode.greenOutputCount = usedBinders.length;
      topNode.label = usedBinders.join(', ');
      if (topNode.label) {
        topNode.width = Math.max(topNode.width, 24 + topNode.label.length * 7.2);
      }

      chain.forEach((lambdaId, index) => {
        const outEdges = outgoingBlue.get(lambdaId) || [];
        outEdges.forEach((edge) => removeBlueEdgeIds.add(edge.id));
        if (index > 0) {
          removeNodeIds.add(lambdaId);
        }
      });

      edgeUpdates.push({
        id: `lb${lambdaCompactEdgeIndex++}`,
        fromNodeId: topId,
        toNodeId: deepestBodyEdge.toNodeId,
        fromPort: 0,
        toPort: deepestBodyEdge.toPort
      });

      model.greenEdges = model.greenEdges.flatMap((edge) => {
        if (!chain.includes(edge.fromNodeId)) {
          return [edge];
        }

        const mappedPort = binderPortByNodeId.get(edge.fromNodeId);
        if (mappedPort === undefined) {
          return [];
        }

        return [{
          ...edge,
          fromNodeId: topId,
          fromPort: mappedPort
        }];
      });
    });

    model.nodes = model.nodes.filter((node) => !removeNodeIds.has(node.id));
    model.blueEdges = model.blueEdges
      .filter((edge) => !removeBlueEdgeIds.has(edge.id))
      .filter((edge) => !removeNodeIds.has(edge.fromNodeId) && !removeNodeIds.has(edge.toNodeId));
    model.blueEdges.push(...edgeUpdates);
    model.greenEdges = model.greenEdges
      .filter((edge) => !removeNodeIds.has(edge.fromNodeId) && !removeNodeIds.has(edge.toNodeId));
  }

  function renderCurrent(resetView) {
    renderedPayload = compactify(lastPayload);
    if (resetView) {
      fitToContent(renderedPayload);
    }
    draw(renderedPayload);
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

  function drawLambdaNode(node) {
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

    if (node.label) {
      ctx.fillStyle = 'rgba(140, 220, 156, 0.74)';
      ctx.font = '11px "Segoe UI", sans-serif';
      ctx.fillText(node.label, nodeCenterX(node), node.y + node.height - 6);
    }
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

  function drawAppNode(node) {
    drawSquareNode(node, '', '#f3e8db');
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
        drawAppNode(node);
      } else if (node.type === 'LAMBDA') {
        drawLambdaNode(node);
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
    const shouldResetView = previousNodeCount === 0 && (lastPayload.nodes || []).length > 0;
    renderCurrent(shouldResetView);
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
    draw(renderedPayload);
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

    draw(renderedPayload);
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
    draw(renderedPayload);
  });

  window.addEventListener('mouseup', () => {
    if (!view.isPanning) {
      return;
    }
    view.isPanning = false;
    canvas.style.cursor = 'grab';
  });

  canvas.style.cursor = 'grab';

  compactToggle.addEventListener('change', () => {
    renderCurrent(true);
  });

  renderCurrent(true);
})();
