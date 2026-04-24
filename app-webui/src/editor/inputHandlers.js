(function initDeltaEditorInputHandlers(global) {
  function attachEditorInputHandlers(config) {
    const {
      state,
      elements,
      renderEditorWithCurrentHighlights,
      notifyHostTextChanged,
      notifyCaretMoved,
      applyEditorTextChange,
      resetSlashMode,
      enterSlashMode,
      refreshSlashModeForSelection,
      resolveSpecialSymbol,
      isWhitespace,
      matchingBracket,
      isOpeningBracket,
      isClosingBracket,
      shouldSkipExistingClosing,
      findBackslashAtomAt,
      updateEditorCaretOverlay,
      syncEditorOverlayScroll,
      findEditorTypeHintAt,
      showTooltip,
      hideTooltip,
    } = config;

    const { editorInput, editorLayer } = elements;

    editorInput.addEventListener('input', () => {
      refreshSlashModeForSelection();
      renderEditorWithCurrentHighlights();
      if (!state.suppressHostNotify) {
        notifyHostTextChanged(editorInput.value);
      }
      notifyCaretMoved();
    });

    editorInput.addEventListener('keydown', (event) => {
      if (event.altKey || event.ctrlKey || event.metaKey) {
        return;
      }

      const selectionStart = editorInput.selectionStart ?? 0;
      const selectionEnd = editorInput.selectionEnd ?? selectionStart;
      const text = editorInput.value;

      if (state.overlayInputMode.active) {
        if (event.key === 'Escape') {
          event.preventDefault();
          resetSlashMode();
          renderEditorWithCurrentHighlights();
          return;
        }

        if (event.key === 'Backspace') {
          event.preventDefault();
          if (state.overlayInputMode.buffer.length > 0) {
            state.overlayInputMode.buffer = state.overlayInputMode.buffer.slice(0, -1);
          } else {
            resetSlashMode();
          }
          renderEditorWithCurrentHighlights();
          return;
        }

        if (event.key === 'Delete') {
          event.preventDefault();
          resetSlashMode();
          renderEditorWithCurrentHighlights();
          return;
        }

        if (event.key === ' ' || event.key === 'Tab') {
          event.preventDefault();
          const symbol = resolveSpecialSymbol(state.overlayInputMode.buffer);
          const start = state.overlayInputMode.start;
          if (symbol) {
            const nextText = text.slice(0, start) + symbol + text.slice(start);
            resetSlashMode();
            applyEditorTextChange(nextText, start + 1);
          } else {
            const rawToken = `\\${state.overlayInputMode.buffer}`;
            const nextText = text.slice(0, start) + rawToken + text.slice(start);
            resetSlashMode();
            applyEditorTextChange(nextText, start + rawToken.length);
          }
          return;
        }

        if (event.key.length === 1 && !isWhitespace(event.key)) {
          event.preventDefault();
          state.overlayInputMode.buffer += event.key;
          renderEditorWithCurrentHighlights();
          return;
        }

        return;
      }

      if (event.key === 'Tab') {
        event.preventDefault();
        const start = selectionStart;
        const end = selectionEnd;
        const nextText = text.slice(0, start) + '  ' + text.slice(end);
        applyEditorTextChange(nextText, start + 2);
        return;
      }

      if (isOpeningBracket(event.key)) {
        event.preventDefault();
        const open = event.key;
        const close = matchingBracket(open);
        const selected = text.slice(selectionStart, selectionEnd);
        const nextText = text.slice(0, selectionStart) + open + selected + close + text.slice(selectionEnd);
        const nextCaret = selected.length > 0 ? selectionStart + selected.length + 1 : selectionStart + 1;
        applyEditorTextChange(nextText, nextCaret);
        return;
      }

      if (isClosingBracket(event.key)
        && selectionStart === selectionEnd
        && shouldSkipExistingClosing(text, selectionStart, event.key)) {
        event.preventDefault();
        editorInput.setSelectionRange(selectionStart + 1, selectionStart + 1);
        renderEditorWithCurrentHighlights();
        notifyCaretMoved();
        return;
      }

      if (event.key === '\\' && selectionStart === selectionEnd) {
        event.preventDefault();
        enterSlashMode(selectionStart);
        renderEditorWithCurrentHighlights();
        return;
      }

      if (selectionStart !== selectionEnd) {
        return;
      }

      if (event.key !== 'Backspace' && event.key !== 'Delete') {
        return;
      }

      if (event.key === 'Backspace' && selectionStart > 0) {
        const left = text[selectionStart - 1];
        if (isOpeningBracket(left) && text[selectionStart] === matchingBracket(left)) {
          event.preventDefault();
          applyEditorTextChange(
            text.slice(0, selectionStart - 1) + text.slice(selectionStart + 1),
            selectionStart - 1,
          );
          return;
        }
      }

      if (event.key === 'Delete' && selectionStart < text.length) {
        const current = text[selectionStart];
        if (isOpeningBracket(current) && text[selectionStart + 1] === matchingBracket(current)) {
          event.preventDefault();
          applyEditorTextChange(
            text.slice(0, selectionStart) + text.slice(selectionStart + 2),
            selectionStart,
          );
          return;
        }
      }

      if (event.key === 'Backspace') {
        const atom = findBackslashAtomAt(text, selectionStart - 1);
        if (!atom || selectionStart <= atom.start || selectionStart > atom.end) {
          return;
        }
        event.preventDefault();
        applyEditorTextChange(text.slice(0, atom.start) + text.slice(atom.end), atom.start);
        return;
      }

      const atom = findBackslashAtomAt(text, selectionStart);
      if (!atom || selectionStart < atom.start || selectionStart >= atom.end) {
        return;
      }
      event.preventDefault();
      applyEditorTextChange(text.slice(0, atom.start) + text.slice(atom.end), atom.start);
    });

    editorInput.addEventListener('scroll', syncEditorOverlayScroll);
    editorInput.addEventListener('keyup', () => {
      if (refreshSlashModeForSelection()) {
        renderEditorWithCurrentHighlights();
      }
      notifyCaretMoved();
      updateEditorCaretOverlay();
    });
    editorInput.addEventListener('click', () => {
      if (refreshSlashModeForSelection()) {
        renderEditorWithCurrentHighlights();
      }
      notifyCaretMoved();
      updateEditorCaretOverlay();
    });
    editorInput.addEventListener('select', () => {
      if (refreshSlashModeForSelection()) {
        renderEditorWithCurrentHighlights();
      }
      notifyCaretMoved();
      updateEditorCaretOverlay();
    });
    editorInput.addEventListener('focus', () => {
      if (refreshSlashModeForSelection()) {
        renderEditorWithCurrentHighlights();
      }
      notifyCaretMoved();
      updateEditorCaretOverlay();
    });
    editorInput.addEventListener('blur', () => {
      elements.editorCaretOverlay.style.display = 'none';
    });

    editorLayer.addEventListener('mousemove', (event) => {
      const hint = findEditorTypeHintAt(event.clientX, event.clientY);
      if (!hint) {
        hideTooltip();
        return;
      }

      showTooltip(hint, event.clientX, event.clientY);
    });

    editorLayer.addEventListener('mouseleave', () => {
      hideTooltip();
    });

    document.addEventListener('selectionchange', () => {
      if (document.activeElement === editorInput) {
        if (refreshSlashModeForSelection()) {
          renderEditorWithCurrentHighlights();
        }
        notifyCaretMoved();
        updateEditorCaretOverlay();
      }
    });
  }

  global.DeltaEditorInputHandlers = {
    attachEditorInputHandlers,
  };
}(window));
