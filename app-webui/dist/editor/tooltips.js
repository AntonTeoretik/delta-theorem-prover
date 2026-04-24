(function initDeltaEditorTooltips(global) {
  function positionTooltip(hoverTooltip, clientX, clientY) {
    const margin = 10;
    const offset = 14;
    let left = clientX + offset;
    let top = clientY + offset;

    const rect = hoverTooltip.getBoundingClientRect();
    if (left + rect.width > window.innerWidth - margin) {
      left = clientX - rect.width - offset;
    }
    if (top + rect.height > window.innerHeight - margin) {
      top = clientY - rect.height - offset;
    }

    left = Math.max(margin, left);
    top = Math.max(margin, top);
    hoverTooltip.style.left = `${left}px`;
    hoverTooltip.style.top = `${top}px`;
  }

  function showTooltip(hoverTooltip, typeText, clientX, clientY) {
    if (!typeText) {
      hoverTooltip.style.display = 'none';
      return;
    }

    hoverTooltip.textContent = typeText;
    hoverTooltip.style.display = 'block';
    positionTooltip(hoverTooltip, clientX, clientY);
  }

  function hideTooltip(hoverTooltip) {
    hoverTooltip.style.display = 'none';
  }

  function collectEditorTypeHintRects(state, editorHighlight, typeHints) {
    const hintsById = new Map((typeHints || [])
      .filter((hint) => hint && typeof hint.id === 'string' && typeof hint.type === 'string')
      .map((hint) => [hint.id, hint.type]));
    const spans = editorHighlight.querySelectorAll('[data-type-hint-ids]');
    const nextRects = [];

    spans.forEach((span) => {
      const idsRaw = span.getAttribute('data-type-hint-ids') || '';
      const ids = idsRaw.split(',').map((value) => value.trim()).filter(Boolean);
      if (ids.length === 0) {
        return;
      }

      const hintId = ids[0];
      const hintType = hintsById.get(hintId);
      if (!hintType) {
        return;
      }

      const rects = span.getClientRects();
      for (let i = 0; i < rects.length; i += 1) {
        const rect = rects[i];
        if (rect.width <= 0 || rect.height <= 0) {
          continue;
        }

        nextRects.push({
          left: rect.left,
          right: rect.right,
          top: rect.top,
          bottom: rect.bottom,
          type: hintType,
        });
      }
    });

    state.editorTypeHintRects = nextRects;
  }

  function findEditorTypeHintAt(state, clientX, clientY) {
    for (let i = state.editorTypeHintRects.length - 1; i >= 0; i -= 1) {
      const rect = state.editorTypeHintRects[i];
      if (clientX >= rect.left && clientX <= rect.right && clientY >= rect.top && clientY <= rect.bottom) {
        return rect.type;
      }
    }

    return null;
  }

  global.DeltaEditorTooltips = {
    showTooltip,
    hideTooltip,
    collectEditorTypeHintRects,
    findEditorTypeHintAt,
  };
}(window));
