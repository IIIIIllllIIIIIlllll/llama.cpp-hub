const CACHE = 'llama-hub-v1';
const CHAT_BG_CACHE = 'llama-hub-chat-bg';

// 合并同一 URL 的并发背景图请求，避免 CSS、JS 亮度计算、设置面板预览同时触发多次网络请求
const chatBgInFlight = new Map();

self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.map(k => k !== CACHE && k !== CHAT_BG_CACHE && caches.delete(k)))
    )
  );
});

/**
 * 背景图/缩略图统一入口：缓存优先，并把同一 URL 的并发请求合并为一次网络请求。
 * 关键点：在异步查缓存/发网络之前，先同步地把该 URL 标记为“正在处理”，
 * 这样后续并发请求不会重复进入 fetch。
 */
function fetchChatBackground(request) {
  const key = request.url;
  const existing = chatBgInFlight.get(key);
  if (existing) {
    return existing.then(r => r.clone());
  }

  let inFlightResolve;
  const inFlightPromise = new Promise(resolve => { inFlightResolve = resolve; });
  chatBgInFlight.set(key, inFlightPromise);

  const p = caches.open(CHAT_BG_CACHE).then(cache =>
    cache.match(request).then(res => {
      if (res) return res;
      return fetch(request).then(networkRes => {
        // 把成功响应写入 Cache API，才是真正“缓存”起来
        const cacheRes = networkRes.clone();
        cache.put(request, cacheRes);
        return networkRes;
      });
    })
  ).catch(() => new Response(null, { status: 404 }));

  // 结果出来后通知所有等待者，然后清理 in-flight 标记
  p.then(res => inFlightResolve(res.clone()));
  p.finally(() => chatBgInFlight.delete(key));

  return p.then(r => r.clone());
}

self.addEventListener('fetch', e => {
  const { request } = e;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return;

  // 聊天背景图/缩略图走 Service Worker 缓存优先 + 并发合并
  if (url.pathname.startsWith('/api/chat/background/image/') || url.pathname.startsWith('/api/chat/background/thumb/')) {
    e.respondWith(fetchChatBackground(request));
    return;
  }

  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/v1/')) return;

  // 只缓存静态资源
  if (!url.pathname.match(/\.(html|js|css|png|jpg|jpeg|gif|svg|ico|woff2?|ttf|eot|webp|manifest)$/i)) return;

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
