(function () {
  'use strict';
  const key = 'keye-theme';
  function apply(theme) {
    const light = theme === 'light';
    document.documentElement.dataset.theme = light ? 'light' : 'dark';
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.content = light ? '#f7f8fa' : '#0b0d11';
    const button = document.getElementById('theme-toggle');
    if (button) {
      button.setAttribute('aria-pressed', String(light));
      button.setAttribute('aria-label', light ? '切换到深色模式' : '切换到浅色模式');
      button.title = button.getAttribute('aria-label');
    }
  }
  let saved;
  try { saved = localStorage.getItem(key); } catch (_) { /* Storage can be disabled. */ }
  apply(saved);
  document.addEventListener('DOMContentLoaded', function () {
    apply(document.documentElement.dataset.theme);
    document.getElementById('theme-toggle').addEventListener('click', function () {
      const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
      apply(next);
      try { localStorage.setItem(key, next); } catch (_) { /* Switching still works without persistence. */ }
    });
  });
  window.addEventListener('storage', function (event) { if (event.key === key) apply(event.newValue); });
}());
