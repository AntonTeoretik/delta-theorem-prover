(function initDeltaEditorInputHandlers(global) {
  function createAutocompleteState() {
    return {
      open: false,
      anchor: 0,
      options: [],
      selectedIndex: 0,
    };
  }

  function collectSuggestionCandidates(state, text, prefix) {
    const known = new Set([
      ...(state.lastPayload?.definitionNames || []),
      ...(state.lastPayload?.freeVariableNames || []),
    ]);

    const regex = /[A-Za-z_][A-Za-z0-9_.]*/g;
    let match = regex.exec(text);
    while (match) {
      known.add(match[0]);
      match = regex.exec(text);
    }

    return [...known]
      .filter((name) => name && name !== prefix && name.startsWith(prefix))
      .sort((a, b) => a.localeCompare(b))
      .slice(0, 12);
  }

  function findIdentifierPrefix(text, caret) {
    const left = text.slice(0, caret);
    const match = left.match(/[A-Za-z_][A-Za-z0-9_.]*$/);
    return match ? match[0] : '';
  }

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
    const autocomplete = createAutocompleteState();

    function closeAutocomplete() {
      autocomplete.open = false;
      autocomplete.options = [];
      if (elements.editorAutocomplete) {
        elements.editorAutocomplete.style.display = 'none';
        elements.editorAutocomplete.innerHTML = '';
      }
    }

    function openAutocomplete(anchor, options) {
      if (!elements.editorAutocomplete || !options.length) {
        closeAutocomplete();
        return;
      }
      autocomplete.open = true;
      autocomplete.anchor = anchor;
      autocomplete.options = options;
      autocomplete.selectedIndex = 0;
      elements.editorAutocomplete.innerHTML = options
        .map((item, index) => `<div class="autocomplete-item${index === 0 ? ' selected' : ''}">${item}</div>`)
        .join('');
      elements.editorAutocomplete.style.display = 'block';
    }

    function applyAutocompleteSelection() {
      if (!autocomplete.open || !autocomplete.options.length) {
        return false;
      }
      const selectionStart = editorInput.selectionStart ?? 0;
      const text = editorInput.value;
      const chosen = autocomplete.options[autocomplete.selectedIndex];
      const nextText = text.slice(0, autocomplete.anchor) + chosen + text.slice(selectionStart);
      applyEditorTextChange(nextText, autocomplete.anchor + chosen.length);
      closeAutocomplete();
      return true;
    }

    function refreshAutocomplete() {
      const selectionStart = editorInput.selectionStart ?? 0;
      const selectionEnd = editorInput.selectionEnd ?? selectionStart;
      if (selectionStart !== selectionEnd) {
        closeAutocomplete();
        return;
      }
      const prefix = findIdentifierPrefix(editorInput.value, selectionStart);
      if (!prefix || prefix.length < 1) {
        closeAutocomplete();
        return;
      }
      const anchor = selectionStart - prefix.length;
      const options = collectSuggestionCandidates(state, editorInput.value, prefix);
      if (!options.length) {
        closeAutocomplete();
        return;
      }
      openAutocomplete(anchor, options);
    }

    function toggleLineComments() {
      const text = editorInput.value;
      const start = editorInput.selectionStart ?? 0;
      const end = editorInput.selectionEnd ?? start;
      const lineStart = text.lastIndexOf('\n', start - 1) + 1;
      const lineEndIndex = text.indexOf('\n', end);
      const lineEnd = lineEndIndex === -1 ? text.length : lineEndIndex;
      const block = text.slice(lineStart, lineEnd);
      const lines = block.split('\n');
      const uncomment = lines.every((line) => /^\s*(--|\/\/)/.test(line) || line.trim() === '');
      const transformed = lines.map((line) => {
        if (line.trim() === '') return line;
        if (uncomment) return line.replace(/^(\s*)(--|\/\/ )?/, '$1');
        return line.replace(/^(\s*)/, '$1-- ');
      }).join('\n');
      const nextText = text.slice(0, lineStart) + transformed + text.slice(lineEnd);
      applyEditorTextChange(nextText, end + (transformed.length - block.length));
    }

    function indentSelection(outdent) {
      const text = editorInput.value;
      const start = editorInput.selectionStart ?? 0;
      const end = editorInput.selectionEnd ?? start;
      const lineStart = text.lastIndexOf('\n', start - 1) + 1;
      const lineEndIndex = text.indexOf('\n', end);
      const lineEnd = lineEndIndex === -1 ? text.length : lineEndIndex;
      const block = text.slice(lineStart, lineEnd);
      const lines = block.split('\n');
      const transformed = lines.map((line) => {
        if (outdent) {
          if (line.startsWith('  ')) return line.slice(2);
          if (line.startsWith('\t')) return line.slice(1);
          return line;
        }
        return `  ${line}`;
      }).join('\n');
      const nextText = text.slice(0, lineStart) + transformed + text.slice(lineEnd);
      applyEditorTextChange(nextText, start + (outdent ? -2 : 2));
    }

    editorInput.addEventListener('input', () => {
      refreshSlashModeForSelection();
      renderEditorWithCurrentHighlights();
      if (!state.suppressHostNotify) {
        notifyHostTextChanged();
      }
      refreshAutocomplete();
      notifyCaretMoved();
    });

    editorInput.addEventListener('keydown', (event) => {
      if (event.ctrlKey && event.key === '/') {
        event.preventDefault();
        toggleLineComments();
        return;
      }

      if ((event.ctrlKey || event.metaKey) && event.key === ' ') {
        event.preventDefault();
        refreshAutocomplete();
        return;
      }

      if (autocomplete.open) {
        if (event.key === 'ArrowDown') {
          event.preventDefault();
          autocomplete.selectedIndex = Math.min(autocomplete.selectedIndex + 1, autocomplete.options.length - 1);
          openAutocomplete(autocomplete.anchor, autocomplete.options);
          return;
        }
        if (event.key === 'ArrowUp') {
          event.preventDefault();
          autocomplete.selectedIndex = Math.max(autocomplete.selectedIndex - 1, 0);
          openAutocomplete(autocomplete.anchor, autocomplete.options);
          return;
        }
        if (event.key === 'Enter' || event.key === 'Tab') {
          event.preventDefault();
          applyAutocompleteSelection();
          return;
        }
        if (event.key === 'Escape') {
          event.preventDefault();
          closeAutocomplete();
          return;
        }
      }

      if (event.altKey || event.metaKey || (event.ctrlKey && event.key !== '/')) {
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
        indentSelection(event.shiftKey);
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
      refreshAutocomplete();
      notifyCaretMoved();
      updateEditorCaretOverlay();
    });
    editorInput.addEventListener('click', () => {
      if (refreshSlashModeForSelection()) {
        renderEditorWithCurrentHighlights();
      }
      refreshAutocomplete();
      notifyCaretMoved();
      updateEditorCaretOverlay();
    });
    editorInput.addEventListener('select', () => {
      if (refreshSlashModeForSelection()) {
        renderEditorWithCurrentHighlights();
      }
      refreshAutocomplete();
      notifyCaretMoved();
      updateEditorCaretOverlay();
    });
    editorInput.addEventListener('blur', () => {
      elements.editorCaretOverlay.style.display = 'none';
      closeAutocomplete();
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
