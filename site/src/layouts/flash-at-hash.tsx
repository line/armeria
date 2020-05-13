export default (hash: string) => {
  if (!hash || !hash.startsWith('#')) {
    return;
  }

  const elementId = hash.substring(1);

  function tryFlash(attempt: number) {
    const targetElement = document.getElementById(elementId);

    // If element is not ready, keep trying up to 1 second.
    if (!targetElement) {
      if (attempt < 50) {
        setTimeout(() => tryFlash(attempt + 1), 20);
      }
      return;
    }

    // Highlight the target element briefly.
    const oldTransitionProperty = targetElement.style.transitionProperty;
    const oldTransitionDuration = targetElement.style.transitionDuration;
    const oldBgColor = targetElement.style.backgroundColor;
    targetElement.style.transitionProperty = 'background-color';
    targetElement.style.transitionDuration = '0.5s';
    targetElement.style.backgroundColor = 'rgb(186, 231, 255)';
    setTimeout(() => {
      targetElement.style.backgroundColor = oldBgColor;
      setTimeout(() => {
        targetElement.style.transitionProperty = oldTransitionProperty;
        targetElement.style.transitionDuration = oldTransitionDuration;
      }, 500);
    }, 500);
  }

  tryFlash(0);
};
