(function initDeltaEditorOverlay(global) {
  const { escapeHtml, resolveBracketHighlight } = global.DeltaEditorTextUtils;

  function setProjection(rawToDisplay, rawStart, rawLength, displayStart, displayLength, mode) {
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

  function buildOverlayProjection(sourceText, overlayInputMode) {
    const rawToDisplay = new Array(sourceText.length + 1);
    rawToDisplay[0] = 0;

    let rawIndex = 0;
    let displayIndex = 0;
    let displayText = '';

    function append(rawLength, displayChunk, mode) {
      const displayStart = displayIndex;
      displayText += displayChunk;
      displayIndex += displayChunk.length;
      setProjection(rawToDisplay, rawIndex, rawLength, displayStart, displayChunk.length, mode || 'identity');
      rawIndex += rawLength;
    }

    while (rawIndex < sourceText.length) {
      const ch = sourceText[rawIndex];
      append(1, ch, 'identity');
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
    if (kind === 'DEFINITION_NAME') return 'hl-definition-name';
    if (kind === 'CONSTANT') return 'hl-constant';
    if (kind === 'TYPE_UNIVERSE') return 'hl-type-universe';
    if (kind === 'FREE_VARIABLE') return 'hl-free-var';
    if (kind === 'BOUND_VARIABLE') return 'hl-bound-var';
    if (kind === 'ACTIVE_CONSTANT_USAGE') return 'hl-active-const-usage';
    if (kind === 'ACTIVE_CONSTANT_DEFINITION') return 'hl-active-const-definition';
    if (kind === 'ACTIVE_BOUND_USAGE') return 'hl-active-bound-usage';
    if (kind === 'ACTIVE_BOUND_DEFINITION') return 'hl-active-bound-definition';
    if (kind === 'DIAGNOSTIC') return 'hl-diagnostic';
    return '';
  }

  function buildEditorHighlightHtml(state, sourceText, spans, typeHints, caretOffset) {
    const projection = buildOverlayProjection(sourceText, state.overlayInputMode);
    state.lastProjection = projection;
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

    const validTypeHints = (typeHints || [])
      .map((hint) => {
        const start = Number.isFinite(hint.startOffset) ? Math.max(0, Math.min(sourceText.length, hint.startOffset)) : 0;
        const end = Number.isFinite(hint.endOffset) ? Math.max(start, Math.min(sourceText.length, hint.endOffset)) : start;
        const id = typeof hint.id === 'string' ? hint.id : '';
        const type = typeof hint.type === 'string' ? hint.type : '';
        return {
          id,
          type,
          start: projection.rawToDisplay[start],
          end: projection.rawToDisplay[end],
        };
      })
      .filter((hint) => hint.id && hint.type && hint.end > hint.start);

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

    const bracketHighlight = resolveBracketHighlight(sourceText, caretOffset);
    if (bracketHighlight) {
      const leftStart = projection.rawToDisplay[bracketHighlight.left];
      const leftEnd = projection.rawToDisplay[bracketHighlight.left + 1];
      const rightStart = projection.rawToDisplay[bracketHighlight.right];
      const rightEnd = projection.rawToDisplay[bracketHighlight.right + 1];
      if (leftEnd > leftStart) {
        validSpans.push({ start: leftStart, end: leftEnd, kind: 'hl-bracket-match' });
      }
      if (rightEnd > rightStart) {
        validSpans.push({ start: rightStart, end: rightEnd, kind: 'hl-bracket-match' });
      }
    }

    if (validSpans.length === 0 && validTypeHints.length === 0) {
      return `${escapeHtml(overlayText)}\n`;
    }

    const starts = new Map();
    const ends = new Map();
    const hintStarts = new Map();
    const hintEnds = new Map();
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

    validTypeHints.forEach((hint) => {
      if (!hintStarts.has(hint.start)) {
        hintStarts.set(hint.start, []);
      }
      hintStarts.get(hint.start).push(hint.id);

      if (!hintEnds.has(hint.end)) {
        hintEnds.set(hint.end, []);
      }
      hintEnds.get(hint.end).push(hint.id);

      points.add(hint.start);
      points.add(hint.end);
    });

    const sortedPoints = [...points].sort((a, b) => a - b);
    const active = new Map();
    const activeHintIds = new Set();
    let result = '';
    let hintAnchorIndex = 0;

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

      (hintEnds.get(point) || []).forEach((hintId) => {
        activeHintIds.delete(hintId);
      });

      (hintStarts.get(point) || []).forEach((hintId) => {
        activeHintIds.add(hintId);
      });

      if (nextPoint <= point) {
        continue;
      }

      const segment = escapeHtml(overlayText.slice(point, nextPoint));
      if (segment.length === 0) {
        continue;
      }

      if (active.size === 0 && activeHintIds.size === 0) {
        result += segment;
      } else {
        const className = [...active.keys()].sort().join(' ');
        const hintIds = [...activeHintIds].sort();
        const hintAttr = hintIds.length > 0 ? ` data-type-hint-ids="${hintIds.join(',')}"` : '';
        const anchorAttr = hintIds.length > 0 ? ` id="type-hint-anchor-${hintAnchorIndex++}"` : '';
        if (className) {
          result += `<span class="${className}"${hintAttr}${anchorAttr}>${segment}</span>`;
        } else {
          result += `<span${hintAttr}${anchorAttr}>${segment}</span>`;
        }
      }
    }

    return `${result}\n`;
  }

  function renderLineNumbers(target, text) {
    const lines = Math.max(1, (text || '').split('\n').length);
    const rows = [];
    for (let i = 1; i <= lines; i += 1) {
      rows.push(String(i));
    }
    target.textContent = `${rows.join('\n')}\n`;
  }

  function renderEditorHighlight(state, elements, payload) {
    const sourceText = typeof payload?.sourceText === 'string' ? payload.sourceText : elements.editorInput.value;
    const highlights = payload?.textHighlights || [];
    const typeHints = payload?.typeHints || [];
    const caretOffset = elements.editorInput.selectionStart ?? 0;

    renderLineNumbers(elements.editorLineNumbers, sourceText);
    elements.editorHighlight.innerHTML = buildEditorHighlightHtml(
      state,
      sourceText,
      highlights,
      typeHints,
      caretOffset,
    );
  }

  global.DeltaEditorOverlay = {
    renderEditorHighlight,
  };
}(window));
