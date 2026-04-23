function displayName(rawName, symbolReplacements) {
  if (typeof rawName !== 'string') {
    return '';
  }
  if (rawName.startsWith('$')) {
    return rawName.slice(1);
  }
  if (rawName.startsWith('\\')) {
    const withoutSlash = rawName.slice(1);
    const mapped = symbolReplacements?.[withoutSlash] || symbolReplacements?.[rawName];
    return typeof mapped === 'string' && mapped.length > 0 ? mapped : rawName;
  }
  return rawName;
}

function renderDefinitionBar(container, payload, onDefinitionSelected) {
  const names = payload.definitionNames || [];
  const selected = payload.selectedDefinitionName;
  const symbolReplacements = payload.symbolReplacements || {};
  container.innerHTML = '';

  names.forEach((name) => {
    const button = document.createElement('button');
    button.className = 'definition-pill';
    if (name === selected) {
      button.classList.add('active');
    }
    button.textContent = displayName(name, symbolReplacements);
    button.addEventListener('click', () => onDefinitionSelected(name));
    container.appendChild(button);
  });

  container.style.display = names.length > 0 ? 'flex' : 'none';
}

window.DeltaDefinitionBar = {
  renderDefinitionBar,
};
