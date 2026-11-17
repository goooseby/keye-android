(function () {
  'use strict';
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)');
  const chapters = Array.from(document.querySelectorAll('.story'));
  const navigation = document.querySelector('.chapter-nav');
  const links = Array.from(navigation.querySelectorAll('a'));
  const reveals = document.querySelectorAll('[data-reveal]');
  // Content is visible by default, including when JavaScript is unavailable.
  if ('IntersectionObserver' in window) {
    const observer = new IntersectionObserver(entries => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          entry.target.classList.add('in-view');
          observer.unobserve(entry.target);
        }
      });
    }, { threshold: 0.08 });
    reveals.forEach(element => {
      if (element.getBoundingClientRect().top > innerHeight) element.classList.add('will-reveal');
      observer.observe(element);
    });
  }
  let scheduled = false;
  function render() {
    scheduled = false;
    let active = -1;
    chapters.forEach((chapter, index) => {
      const rect = chapter.getBoundingClientRect();
      if (rect.top <= innerHeight * .48 && rect.bottom > innerHeight * .48) active = index;
      if (rect.bottom > 0 && rect.top < innerHeight) {
        const offset = reduce.matches ? 0 : Math.max(-20, Math.min(20, (innerHeight / 2 - rect.top - rect.height / 2) * .045));
        chapter.querySelector('.visual-inner').style.setProperty('--drift', offset + 'px');
      }
    });
    navigation.classList.toggle('is-reading', active >= 0);
    links.forEach((link, index) => {
      if (index === active) link.setAttribute('aria-current', 'step');
      else link.removeAttribute('aria-current');
    });
  }
  function schedule() {
    if (!scheduled) { scheduled = true; requestAnimationFrame(render); }
  }
  addEventListener('scroll', schedule, { passive: true });
  addEventListener('resize', schedule);
  reduce.addEventListener('change', () => {
    if (reduce.matches) chapters.forEach(chapter => chapter.querySelector('.visual-inner').style.setProperty('--drift', '0px'));
    schedule();
  });
  render();
}());
