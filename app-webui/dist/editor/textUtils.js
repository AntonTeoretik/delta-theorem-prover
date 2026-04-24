(function initDeltaEditorTextUtils(global) {
  const BACKSLASH_TOKEN_CHAR = /[^\s]/;

  function escapeHtml(text) {
    return text
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;');
  }

  function resolveSymbolReplacements(payload) {
    const table = {};
    const candidates = [
      payload?.symbolReplacements,
      payload?.backslashReplacements,
      payload?.backslashSymbolMap,
      payload?.symbolMap,
    ];

    function putEntry(rawKey, rawValue) {
      if (typeof rawKey !== 'string' || typeof rawValue !== 'string' || rawValue.length === 0) {
        return;
      }

      const trimmed = rawKey.trim();
      if (!trimmed) {
        return;
      }

      const key = trimmed.startsWith('\\') ? trimmed.slice(1) : trimmed;
      if (!key) {
        return;
      }

      table[key] = rawValue;
    }

    candidates.forEach((candidate) => {
      if (!candidate) {
        return;
      }

      if (Array.isArray(candidate)) {
        candidate.forEach((entry) => {
          if (!entry || typeof entry !== 'object') {
            return;
          }

          putEntry(
            entry.token ?? entry.name ?? entry.key,
            entry.symbol ?? entry.value ?? entry.replacement,
          );
        });
        return;
      }

      if (typeof candidate === 'object') {
        Object.entries(candidate).forEach(([key, value]) => {
          putEntry(key, value);
        });
      }
    });

    return table;
  }

  function isTokenChar(ch) {
    return Boolean(ch) && BACKSLASH_TOKEN_CHAR.test(ch);
  }

  function resolveSpecialSymbol(activeSymbolReplacements, token) {
    if (token.length === 0) {
      return 'λ';
    }

    return activeSymbolReplacements[token] || null;
  }

  function isWhitespace(ch) {
    return ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r';
  }

  function matchingBracket(ch) {
    if (ch === '(') return ')';
    if (ch === '[') return ']';
    if (ch === '{') return '}';
    if (ch === ')') return '(';
    if (ch === ']') return '[';
    if (ch === '}') return '{';
    return null;
  }

  function isOpeningBracket(ch) {
    return ch === '(' || ch === '[' || ch === '{';
  }

  function isClosingBracket(ch) {
    return ch === ')' || ch === ']' || ch === '}';
  }

  function findMatchingBracket(text, index) {
    if (index < 0 || index >= text.length) {
      return null;
    }

    const ch = text[index];
    const match = matchingBracket(ch);
    if (!match) {
      return null;
    }

    if (isOpeningBracket(ch)) {
      let depth = 0;
      for (let i = index + 1; i < text.length; i += 1) {
        if (text[i] === ch) {
          depth += 1;
        } else if (text[i] === match) {
          if (depth === 0) {
            return i;
          }
          depth -= 1;
        }
      }
      return null;
    }

    let depth = 0;
    for (let i = index - 1; i >= 0; i -= 1) {
      if (text[i] === ch) {
        depth += 1;
      } else if (text[i] === match) {
        if (depth === 0) {
          return i;
        }
        depth -= 1;
      }
    }

    return null;
  }

  function isBracketSequenceBalanced(text) {
    const stack = [];
    for (let i = 0; i < text.length; i += 1) {
      const ch = text[i];
      if (isOpeningBracket(ch)) {
        stack.push(ch);
        continue;
      }

      if (!isClosingBracket(ch)) {
        continue;
      }

      const expectedOpen = matchingBracket(ch);
      const top = stack[stack.length - 1];
      if (!top || top !== expectedOpen) {
        return false;
      }
      stack.pop();
    }

    return stack.length === 0;
  }

  function shouldSkipExistingClosing(text, caretOffset, closingBracket) {
    if (text[caretOffset] !== closingBracket) {
      return false;
    }
    if (!isBracketSequenceBalanced(text)) {
      return false;
    }

    const matchingIndex = findMatchingBracket(text, caretOffset);
    return Number.isInteger(matchingIndex) && matchingIndex < caretOffset;
  }

  function resolveBracketHighlight(text, caretOffset) {
    if (!text || text.length === 0) {
      return null;
    }

    const candidates = [];
    if (caretOffset >= 0 && caretOffset < text.length) {
      candidates.push(caretOffset);
    }
    if (caretOffset > 0 && caretOffset - 1 < text.length) {
      candidates.push(caretOffset - 1);
    }

    for (let i = 0; i < candidates.length; i += 1) {
      const index = candidates[i];
      const ch = text[index];
      if (!isOpeningBracket(ch) && !isClosingBracket(ch)) {
        continue;
      }

      const pair = findMatchingBracket(text, index);
      if (pair == null) {
        continue;
      }

      return {
        left: Math.min(index, pair),
        right: Math.max(index, pair),
      };
    }

    return null;
  }

  function findBackslashAtomAt(text, index) {
    if (index < 0 || index >= text.length) {
      return null;
    }

    let start = index;
    while (start > 0 && !isWhitespace(text[start - 1])) {
      start -= 1;
    }
    if (text[start] !== '\\') {
      return null;
    }

    let end = start + 1;
    while (end < text.length && !isWhitespace(text[end])) {
      end += 1;
    }

    if (end - start <= 1 || index < start || index >= end) {
      return null;
    }

    return { start, end };
  }

  global.DeltaEditorTextUtils = {
    escapeHtml,
    resolveSymbolReplacements,
    isTokenChar,
    resolveSpecialSymbol,
    isWhitespace,
    matchingBracket,
    isOpeningBracket,
    isClosingBracket,
    shouldSkipExistingClosing,
    resolveBracketHighlight,
    findBackslashAtomAt,
  };
}(window));
