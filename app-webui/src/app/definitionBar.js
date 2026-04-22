function renderDefinitionBar(container, payload, onDefinitionSelected) {
  const names = payload.definitionNames || [];
  const selected = payload.selectedDefinitionName;
  container.innerHTML = '';

  names.forEach((name) => {
    const button = document.createElement('button');
    button.className = 'definition-pill';
    if (name === selected) {
      button.classList.add('active');
    }
    button.textContent = name;
    button.addEventListener('click', () => onDefinitionSelected(name));
    container.appendChild(button);
  });

  container.style.display = names.length > 0 ? 'flex' : 'none';
}

window.DeltaDefinitionBar = {
  renderDefinitionBar,
};
