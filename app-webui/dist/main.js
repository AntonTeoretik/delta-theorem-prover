(() => {
  const editorInput = document.getElementById('editorInput');
  const canvas = document.getElementById('vizCanvas');
  const ctx = canvas.getContext('2d');
  const stats = document.getElementById('stats');
  let suppressHostNotify = false;
  let lastPayload = { lines: [], lineCount: 0, nonEmptyLineCount: 0, totalCharacters: 0 };

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

  function draw(payload) {
    resizeCanvasToDisplaySize();
    const lines = payload.lines || [];
    const maxLength = Math.max(1, ...lines.map((line) => line.length));

    ctx.clearRect(0, 0, canvas.width, canvas.height);

    const pad = 24;
    const barHeight = 22;
    const gap = 10;
    const areaWidth = canvas.width - pad * 2;
    const maxBars = Math.max(1, Math.floor((canvas.height - pad * 2) / (barHeight + gap)));
    const visible = lines.slice(0, maxBars);

    visible.forEach((line, index) => {
      const y = pad + index * (barHeight + gap);
      const width = Math.max(2, Math.floor((line.length / maxLength) * areaWidth));

      const hue = (line.length * 17 + index * 13) % 360;
      ctx.fillStyle = `hsl(${hue}, 68%, 55%)`;
      ctx.fillRect(pad, y, width, barHeight);

      ctx.fillStyle = '#12263a';
      ctx.font = '13px "Segoe UI", sans-serif';
      const label = `${line.lineNumber}: ${line.preview} (${line.length})`;
      ctx.fillText(label, pad + 8, y + 15);
    });

    if (lines.length === 0) {
      ctx.fillStyle = '#12263a';
      ctx.font = '16px "Segoe UI", sans-serif';
      ctx.fillText('Type text in the editor to see visualization.', 24, 40);
    }

    stats.textContent = `Lines: ${payload.lineCount} | Non-empty: ${payload.nonEmptyLineCount} | Characters: ${payload.totalCharacters}`;
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
    if (suppressHostNotify) {
      return;
    }
    notifyHostTextChanged(editorInput.value);
  });

  window.addEventListener('resize', () => {
    draw(lastPayload);
  });

  window.renderFromHost(lastPayload);
})();
