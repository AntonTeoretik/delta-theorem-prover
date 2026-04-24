(function initDeltaGraphInteractions(global) {
  function findNodeTypeHintAtCanvasPoint(canvas, renderer, renderedPayload, clientX, clientY) {
    const rect = canvas.getBoundingClientRect();
    const x = clientX - rect.left;
    const y = clientY - rect.top;
    const world = renderer.screenToWorld(x, y);
    const nodes = renderedPayload?.nodes || [];

    for (let i = nodes.length - 1; i >= 0; i -= 1) {
      const node = nodes[i];
      if (world.x >= node.x && world.x <= node.x + node.width && world.y >= node.y && world.y <= node.y + node.height) {
        return renderedPayload?.nodeTypeHints?.[node.id] || null;
      }
    }

    return null;
  }

  function attachGraphInteractions(config) {
    const {
      canvas,
      view,
      renderer,
      getRenderedPayload,
      showTooltip,
      hideTooltip,
    } = config;

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

      renderer.draw(getRenderedPayload());
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

    canvas.addEventListener('mousemove', (event) => {
      const hint = findNodeTypeHintAtCanvasPoint(
        canvas,
        renderer,
        getRenderedPayload(),
        event.clientX,
        event.clientY,
      );
      if (!hint) {
        hideTooltip();
        return;
      }

      showTooltip(hint, event.clientX, event.clientY);
    });

    canvas.addEventListener('mouseleave', () => {
      hideTooltip();
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
      renderer.draw(getRenderedPayload());
    });

    window.addEventListener('mouseup', () => {
      if (!view.isPanning) {
        return;
      }
      view.isPanning = false;
      canvas.style.cursor = 'grab';
    });

    canvas.style.cursor = 'grab';
  }

  global.DeltaGraphInteractions = {
    attachGraphInteractions,
  };
}(window));
