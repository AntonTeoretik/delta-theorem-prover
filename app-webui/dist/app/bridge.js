function notifyHostTextChanged(text) {
  if (typeof window.cefQuery !== 'function') {
    return;
  }

  window.cefQuery({
    request: `editorTextChanged:${text}`,
    onSuccess: () => {},
    onFailure: () => {},
  });
}

function notifyHostDefinitionSelected(name) {
  if (typeof window.cefQuery !== 'function') {
    return;
  }

  window.cefQuery({
    request: `selectDefinition:${name}`,
    onSuccess: () => {},
    onFailure: () => {},
  });
}

window.DeltaBridge = {
  notifyHostTextChanged,
  notifyHostDefinitionSelected,
};
