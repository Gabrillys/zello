/* Service worker do atalho: guarda só a casca (esta página e os ícones), para
   o app abrir na hora e mostrar um aviso claro quando estiver sem internet.
   O sistema em si continua vindo do Google a cada uso, sempre atualizado. */
const CACHE = "zello-casca-v2";
const CASCA = ["./", "./index.html", "./manifest.json", "./icone-192.png", "./icone-512.png", "./favicon-64.png"];

self.addEventListener("install", (ev) => {
  ev.waitUntil(caches.open(CACHE).then((c) => c.addAll(CASCA)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (ev) => {
  ev.waitUntil(
    caches.keys()
      .then((nomes) => Promise.all(nomes.filter((n) => n !== CACHE).map((n) => caches.delete(n))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (ev) => {
  const req = ev.request;
  if (req.method !== "GET" || new URL(req.url).origin !== self.location.origin) return;
  ev.respondWith(
    fetch(req)
      .then((resp) => {
        const copia = resp.clone();
        caches.open(CACHE).then((c) => c.put(req, copia)).catch(() => {});
        return resp;
      })
      .catch(() =>
        caches.match(req).then((achado) => achado || caches.match("./index.html").then((casca) => casca || semInternet()))
      )
  );
});

function semInternet() {
  return new Response(
    '<!DOCTYPE html><html lang="pt-BR"><meta charset="UTF-8">' +
    '<meta name="viewport" content="width=device-width, initial-scale=1">' +
    '<body style="margin:0;display:flex;align-items:center;justify-content:center;height:100vh;' +
    'background:#3d2718;color:#f2e9d8;font-family:system-ui,sans-serif;text-align:center;padding:24px;">' +
    '<div><h2 style="margin:0 0 8px;">Sem internet</h2>' +
    '<p style="color:#cbb98f;margin:0;">O sistema precisa de conexão. Verifique a rede e abra o app de novo.</p>' +
    '</div></body></html>',
    { headers: { "Content-Type": "text/html; charset=UTF-8" } }
  );
}
