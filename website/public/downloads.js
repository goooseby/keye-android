(function () {
  'use strict';
  const groups = document.querySelectorAll('.download-choice[data-product]');
  if (!groups.length) return;
  const repos = { windows: 'keye-desktop', android: 'keye-android' };
  fetch('/downloads/releases.json', { cache: 'no-cache', signal: AbortSignal.timeout(10000) })
    .then(response => { if (!response.ok) throw new Error('Download metadata unavailable'); return response.json(); })
    .then(manifest => {
      if (manifest.schemaVersion !== 1) return;
      groups.forEach(group => {
        const product = group.dataset.product;
        const entry = manifest.products?.[product];
        if (!entry || !repos[product] || !/^v\d+\.\d+\.\d+$/.test(entry.version) || !Number.isSafeInteger(entry.size) || entry.size <= 0) return;
        const mirror = new URL(entry.mirrorUrl, location.origin);
        const github = new URL(entry.githubUrl);
        if (mirror.origin !== location.origin || !mirror.pathname.startsWith('/downloads/' + product + '/' + entry.version + '/') ||
            github.origin !== 'https://github.com' || !github.pathname.startsWith('/goooseby/' + repos[product] + '/releases/download/' + entry.version + '/')) return;
        group.querySelector('[data-mirror]').href = mirror.href;
        group.querySelector('[data-github]').href = github.href;
        group.querySelector('[data-download-meta]').textContent = entry.version + ' · ' + (entry.size / 1048576).toFixed(1) + ' MB';
      });
    }).catch(() => { /* The verified version embedded in HTML remains downloadable. */ });
}());
