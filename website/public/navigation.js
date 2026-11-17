(function () {
  'use strict';
  const header = document.querySelector('.header');
  if (!header) return;
  const reduce = matchMedia('(prefers-reduced-motion: reduce)');
  let pending = false;
  let previous = -1;
  function paint() {
    pending = false;
    const distance = Math.max(0, Math.min(1, scrollY / 150));
    const progress = reduce.matches ? (distance > 0.15 ? 1 : 0) : distance * distance * (3 - 2 * distance);
    if (progress !== previous) {
      header.style.setProperty('--nav-progress', progress.toFixed(4));
      previous = progress;
    }
  }
  function schedule() {
    if (!pending) { pending = true; requestAnimationFrame(paint); }
  }
  addEventListener('scroll', schedule, { passive: true });
  addEventListener('pageshow', schedule);
  reduce.addEventListener('change', schedule);
  paint();
}());
