// TV browsers do not consistently provide spatial focus navigation.
// Add arrow-key navigation while keeping Tab, touch and normal scrolling intact.
const focusableSelector = 'a[href], button:not(:disabled), summary, [tabindex="0"]';
const directions = new Set(['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown']);

function revealLinkedLegalSection(): void {
  if (!['#privacy', '#terms', '#imprint'].includes(window.location.hash)) return;
  const section = document.getElementById(window.location.hash.slice(1));
  if (section instanceof HTMLDetailsElement) {
    section.open = true;
    section.querySelector('summary')?.focus({ preventScroll: true });
  }
}

window.addEventListener('hashchange', revealLinkedLegalSection);
revealLinkedLegalSection();

function visibleControls(): HTMLElement[] {
  return Array.from(document.querySelectorAll<HTMLElement>(focusableSelector)).filter((element) => {
    if (element.matches('.skip-link') || element.getAttribute('aria-disabled') === 'true') return false;
    const style = getComputedStyle(element);
    return element.getClientRects().length > 0 && style.visibility !== 'hidden' && style.display !== 'none';
  });
}

document.addEventListener('keydown', (event: KeyboardEvent) => {
  if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return;
  const current = document.activeElement;
  if (current instanceof HTMLElement && (current.isContentEditable || current.matches('input, textarea, select'))) return;

  // Some TV remotes send Select or an unhandled Enter for ordinary links.
  if (event.key === 'Select' && current instanceof HTMLElement && current.matches(focusableSelector)) {
    event.preventDefault();
    current.click();
    return;
  }
  if (!directions.has(event.key)) return;

  const controls = visibleControls();
  if (!(current instanceof HTMLElement) || !controls.includes(current)) {
    const initial = document.querySelector<HTMLElement>('[data-tv-default]');
    if (initial) {
      event.preventDefault();
      initial.focus({ preventScroll: true });
      initial.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'auto' });
    }
    return;
  }

  const origin = current.getBoundingClientRect();
  const originX = origin.x + origin.width / 2;
  const originY = origin.y + origin.height / 2;
  const horizontal = event.key === 'ArrowLeft' || event.key === 'ArrowRight';
  const positive = event.key === 'ArrowRight' || event.key === 'ArrowDown';
  let target: HTMLElement | undefined;
  let bestScore = Infinity;

  for (const candidate of controls) {
    if (candidate === current) continue;
    const rect = candidate.getBoundingClientRect();
    // Keep long legal text scrollable instead of jumping to a distant link.
    if (rect.bottom <= 0 || rect.top >= window.innerHeight || rect.right <= 0 || rect.left >= window.innerWidth) continue;
    const deltaX = rect.x + rect.width / 2 - originX;
    const deltaY = rect.y + rect.height / 2 - originY;
    const primary = horizontal ? deltaX : deltaY;
    const secondary = horizontal ? deltaY : deltaX;
    if ((positive ? primary : -primary) <= 2) continue;
    const perpendicularOverlap = horizontal
      ? rect.bottom > origin.top && rect.top < origin.bottom
      : rect.right > origin.left && rect.left < origin.right;
    const score = Math.abs(primary) + Math.abs(secondary) * (perpendicularOverlap ? 0.15 : 2.5);
    if (score < bestScore) {
      bestScore = score;
      target = candidate;
    }
  }

  if (target) {
    event.preventDefault();
    target.focus({ preventScroll: true });
    target.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'auto' });
  }
  // At an edge there is no navigation target: leave the key to page scrolling.
});
