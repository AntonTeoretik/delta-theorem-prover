(function initDeltaEditorStatusMarkers(global) {
  function createMirror(textarea) {
    const mirror = document.createElement('div');
    const style = window.getComputedStyle(textarea);
    const props = [
      'boxSizing',
      'width',
      'height',
      'overflowX',
      'overflowY',
      'borderTopWidth',
      'borderRightWidth',
      'borderBottomWidth',
      'borderLeftWidth',
      'paddingTop',
      'paddingRight',
      'paddingBottom',
      'paddingLeft',
      'fontStyle',
      'fontVariant',
      'fontWeight',
      'fontStretch',
      'fontSize',
      'lineHeight',
      'fontFamily',
      'textAlign',
      'textTransform',
      'textIndent',
      'letterSpacing',
      'wordSpacing',
      'tabSize',
      'MozTabSize',
      'whiteSpace',
      'wordWrap',
      'overflowWrap',
    ];
    props.forEach((prop) => {
      mirror.style[prop] = style[prop];
    });

    mirror.style.position = 'absolute';
    mirror.style.visibility = 'hidden';
    mirror.style.pointerEvents = 'none';
    mirror.style.whiteSpace = 'pre-wrap';
    mirror.style.wordWrap = 'break-word';
    mirror.style.overflow = 'hidden';
    mirror.style.top = '-10000px';
    mirror.style.left = '-10000px';
    document.body.appendChild(mirror);
    return mirror;
  }

  function getOffsetCoordinates(textarea, text, offset) {
    if (!renderDefinitionStatusMarkers.__mirror) {
      renderDefinitionStatusMarkers.__mirror = createMirror(textarea);
    }
    const mirror = renderDefinitionStatusMarkers.__mirror;
    const style = window.getComputedStyle(textarea);
    mirror.style.width = style.width;
    mirror.style.height = style.height;
    mirror.style.font = style.font;
    mirror.style.lineHeight = style.lineHeight;
    mirror.style.padding = style.padding;
    mirror.style.borderWidth = style.borderWidth;
    const before = text.slice(0, Math.max(0, Math.min(text.length, offset)));
    const after = text.slice(Math.max(0, Math.min(text.length, offset)));

    mirror.textContent = '';
    const beforeNode = document.createTextNode(before);
    const markerNode = document.createElement('span');
    markerNode.textContent = after.length > 0 ? after[0] : ' ';
    markerNode.style.display = 'inline';
    markerNode.style.padding = '0';
    markerNode.style.margin = '0';
    mirror.appendChild(beforeNode);
    mirror.appendChild(markerNode);

    return {
      left: markerNode.offsetLeft,
      top: markerNode.offsetTop,
    };
  }

  function renderDefinitionStatusMarkers(state, elements, payload, showTooltip, hideTooltip) {
    const container = elements.editorStatusMarkers;
    const input = elements.editorInput;
    if (!container || !input) {
      return;
    }

    const statuses = Array.isArray(payload?.definitionStatuses) ? payload.definitionStatuses : [];
    container.innerHTML = '';
    if (statuses.length === 0) {
      return;
    }

    const sourceText = typeof payload?.sourceText === 'string' ? payload.sourceText : '';
    const style = window.getComputedStyle(input);
    const measureCanvas = renderDefinitionStatusMarkers.__measureCanvas
      || (renderDefinitionStatusMarkers.__measureCanvas = document.createElement('canvas'));
    const measureContext = measureCanvas.getContext('2d');
    measureContext.font = style.font;
    const virtualSpace = Math.max(4, measureContext.measureText(' ').width);

    statuses.forEach((status) => {
      const markerOffset = Number(status?.markerOffset);
      if (!Number.isFinite(markerOffset) || markerOffset < 0 || markerOffset > sourceText.length) {
        return;
      }

      const coords = getOffsetCoordinates(input, sourceText, markerOffset);

      const marker = document.createElement('span');
      marker.className = `status-marker ${status.isOk ? 'ok' : 'error'}`;
      marker.textContent = status.isOk ? '✓' : '✕';
      marker.style.top = `${coords.top - input.scrollTop}px`;
      marker.style.left = `${coords.left - input.scrollLeft + virtualSpace}px`;

      const messages = Array.isArray(status.messages) ? status.messages : [];
      const tooltipText = status.isOk
        ? 'Definition typechecks successfully.'
        : messages.join('\n');

      marker.addEventListener('mouseenter', (event) => {
        showTooltip(tooltipText, event.clientX + 8, event.clientY + 12);
      });
      marker.addEventListener('mousemove', (event) => {
        showTooltip(tooltipText, event.clientX + 8, event.clientY + 12);
      });
      marker.addEventListener('mouseleave', () => {
        hideTooltip();
      });

      container.appendChild(marker);
    });
  }

  global.DeltaEditorStatusMarkers = {
    renderDefinitionStatusMarkers,
  };
}(window));
