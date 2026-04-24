if (!window.DeltaAppBootstrap || typeof window.DeltaAppBootstrap.bootstrap !== 'function') {
  throw new Error('Delta web UI bootstrap failed: DeltaAppBootstrap is missing');
}

window.DeltaAppBootstrap.bootstrap();
