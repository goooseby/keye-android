'use strict';

async function loadAnnouncements() {
  const list = document.getElementById('notice-list');
  try {
    const response = await fetch('/announcements.json', { cache: 'no-cache', signal: AbortSignal.timeout(10000) });
    if (!response.ok) throw new Error('Announcements unavailable');
    const feed = await response.json();
    if (feed.schemaVersion !== 1 || !Array.isArray(feed.announcements)) throw new Error('Invalid feed');
    const now = Date.now();
    const items = feed.announcements.filter(item =>
      typeof item.title === 'string' && typeof item.body === 'string' && Date.parse(item.publishedAt) <= now
    ).sort((a, b) => Date.parse(b.publishedAt) - Date.parse(a.publishedAt));
    const fragment = document.createDocumentFragment();
    for (const item of items) {
      const detail = document.createElement('details');
      detail.className = 'notice';
      const summary = document.createElement('summary');
      const heading = document.createElement('div');
      const title = document.createElement('span');
      title.className = 'notice-title';
      title.textContent = item.title;
      const date = document.createElement('time');
      date.dateTime = item.publishedAt;
      date.textContent = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(item.publishedAt));
      const toggle = document.createElement('span');
      toggle.className = 'notice-toggle';
      toggle.textContent = '+';
      toggle.setAttribute('aria-hidden', 'true');
      heading.append(title, date);
      summary.append(heading, toggle);
      const body = document.createElement('div');
      body.className = 'notice-body';
      body.textContent = item.body;
      detail.append(summary, body);
      fragment.append(detail);
    }
    if (items.length) list.replaceChildren(fragment);
    else list.textContent = '暂无公告。';
  } catch (_) {
    list.textContent = '公告暂时无法加载，请稍后刷新。';
  }
}

loadAnnouncements();
