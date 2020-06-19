import flashAtHash from './flash-at-hash';

export default (hash: string) => {
  if (!hash || !hash.startsWith('#')) {
    return;
  }

  const elementId = hash.substring(1);

  function tryScroll(attempt: number) {
    const targetElement = document.getElementById(elementId);

    // If element is not ready, keep trying up to 1 second.
    if (!targetElement) {
      if (attempt < 50) {
        setTimeout(() => tryScroll(attempt + 1), 20);
      }
      return;
    }

    // Update the history.
    if (window.location.hash !== hash) {
      window.history.pushState({}, '', hash);
    }

    // Scroll to the target element.
    window.scroll({ top: targetElement.offsetTop });

    // Highlight the target element briefly.
    flashAtHash(hash);
  }

  tryScroll(0);
};
