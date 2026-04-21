(() => {
  const editorInput = document.getElementById('editorInput');
  const canvas = document.getElementById('vizCanvas');
  const ctx = canvas.getContext('2d');
  const stats = document.getElementById('stats');

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

  function blueOutPoint(node, port) {
    const count = Math.max(1, node.blueOutputCount || 1);
    const gap = node.width / (count + 1);
    return {
      x: node.x + gap * (port + 1),
      y: node.y + node.height
    };
  }

  function blueInPoint(node, port) {
    const count = Math.max(1, node.blueInputCount || 1);
    const gap = node.width / (count + 1);
    return {
      x: node.x + gap * (port + 1),
      y: node.y
    };
  }

  function greenOutPoint(node, port) {
    const count = Math.max(1, node.greenOutputCount || 1);
    const gap = node.width / (count + 1);
    return {
      x: node.x + gap * (port + 1),
      y: node.y + node.height
    };
  }

  function greenInPoint(node, port) {
    const count = Math.max(1, node.greenInputCount || 1);
    const gap = node.width / (count + 1);
    return {
      x: node.x + gap * (port + 1),
      y: node.y
    };
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

  function drawSquareNode(node, text) {
    ctx.fillStyle = '#323841';
    ctx.strokeStyle = '#ffb173';
    ctx.lineWidth = 1.2;
    ctx.beginPath();
    ctx.roundRect(node.x, node.y, node.width, node.height, 7);
    ctx.fill();
    ctx.stroke();

    if (text) {
      ctx.fillStyle = '#f3e8db';
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
    ctx.clearRect(0, 0, canvas.width, canvas.height);

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
      drawSpline(greenOutPoint(from, edge.fromPort), greenInPoint(to, edge.toPort), '#7acd8b', 26);
    });

    (payload.nodes || []).forEach((node) => {
      if (node.type === 'ROOT') {
        drawRootNode(node, payload.freeVariableNames || []);
      } else if (node.type === 'APP') {
        drawSquareNode(node, '•');
      } else if (node.type === 'LAMBDA') {
        drawSquareNode(node, 'λ');
      } else if (node.type === 'CONST') {
        drawSquareNode(node, node.label || 'c');
      } else if (node.type === 'VAR') {
        drawSquareNode(node, '');
      }
      drawPorts(node);
    });

    const diagnostics = payload.diagnostics || [];
    if (diagnostics.length > 0) {
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
    lastPayload = payload || lastPayload;
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

  draw(lastPayload);
})();
