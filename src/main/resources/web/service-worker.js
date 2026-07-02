const CACHE = 'llama-hub-v1';
const CHAT_BG_CACHE = 'llama-hub-chat-bg';

self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.map(k => k !== CACHE && k !== CHAT_BG_CACHE && caches.delete(k)))
    )
  );
});

self.addEventListener('fetch', e => {
  const { request } = e;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return;
  // 聊天背景图走 Service Worker 缓存优先
  if (url.pathname.startsWith('/api/chat/background/image/') || url.pathname.startsWith('/api/chat/background/thumb/')) {
    e.respondWith(
      caches.open(CHAT_BG_CACHE).then(cache =>
        cache.match(request).then(res => {
          if (res) return res;
          return fetch(request).catch(() => new Response(null, { status: 404 }));
        })
      )
    );
    return;
  }

  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/v1/')) return;

  e.respondWith(
    fetch(request)
      .then(res => {
        const copy = res.clone();
        caches.open(CACHE).then(c => c.put(request, copy));
        return res;
      })
      .catch(() => caches.match(request))
  );
});
