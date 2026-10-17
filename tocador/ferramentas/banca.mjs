// Bateria de testes do painel: cabe na tela? vaza para os lados? alvos de toque pequenos? erros?
import { chromium } from 'playwright-core';
const html = process.argv[2], saida = process.argv[3];
const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium_headless_shell-1194/chrome-linux/headless_shell' });
const LET = { titulo: 'Tom Jobim — Garota de Ipanema', adiant: 500, ajuste: 0, linhas: [{ t: 8000, x: 'Olha que coisa mais linda' }, { t: 11300, x: 'Mais cheia de graça' }, { t: 14500, x: 'É ela, menina, que vem e que passa' }, { t: 19000, x: 'Num doce balanço a caminho do mar' }] };
const CEN = {
  inicio: { estado: { modo: 'nada' } },
  musica: { estado: { modo: 'vlc', situacao: 'tocando', tocando: true, titulo: 'Garota de Ipanema', video: false, busca: true, duracao: 318000, tempo: 12000, posicao: 0.04, volume: 100, fila: { nome: 'Bossa Nova Essencial', indice: 6, ordem: 6, total: 42, aleatorio: false, repetir: 'nao' } }, letra: true },
  radio: { estado: { modo: 'vlc', situacao: 'tocando', tocando: true, titulo: 'Rádio Eldorado', video: false, busca: false, duracao: 0, volume: 100 }, radio: { nome: 'Rádio Eldorado 107,3', agora: 'Elis Regina — Águas de Março', estilos: ['MPB', 'Jazz'], pais: 'BR', estado: 'São Paulo', idioma: 'portuguese', qualidade: 'MP3 · 128 kbps · 44,1 kHz', site: 'eldorado.com.br' } },
  youtube: { estado: { modo: 'web', situacao: 'tocando', tocando: true, titulo: 'Como funciona um motor a jato — explicação completa com animações', qualidade: '1080p', busca: true, duracao: 845000, tempo: 61000, posicao: 0.07, podeCheio: true, expandido: true }, alturaFrac: 0.62 },
  youtubeBarra: { estado: { modo: 'web', situacao: 'tocando', tocando: true, titulo: 'Como funciona um motor a jato', qualidade: '1080p', podeCheio: true }, alturaFixa: 88 },
  videoVlc: { estado: { modo: 'vlc', situacao: 'tocando', tocando: true, titulo: 'Reels de @fulano (Instagram)', video: true, busca: true, duracao: 45000, tempo: 4000, posicao: 0.1, volume: 100, podeCheio: true, temporario: true }, alturaFrac: 0.45 },
};
const TELAS = [[360, 740, 'S'], [384, 823, 'S22U'], [412, 915, 'G'], [823, 384, 'S22U-deitado']];
const res = [];
for (const [W, H, nomeTela] of TELAS) for (const [nome, c] of Object.entries(CEN)) {
  const h = c.alturaFixa || Math.round(H * (c.alturaFrac || 1));
  const p = await b.newPage({ viewport: { width: W, height: h }, deviceScaleFactor: 2 });
  const erros = [];
  p.on('pageerror', (e) => erros.push(String(e)));
  await p.addInitScript((c) => {
    const t0 = performance.now();
    window.Tocador = new Proxy({}, { get: (_, k) => (...a) => {
      if (k === 'estado') { const e = Object.assign({}, c.estado); if (e.duracao) e.tempo = (e.tempo || 0) + (performance.now() - t0); return JSON.stringify(e); }
      if (k === 'radio') return JSON.stringify(c.radio || { musica: true, nome: 'Garota de Ipanema', agora: 'Tom Jobim — Getz/Gilberto', estilos: ['Bossa Nova Essencial'], qualidade: 'Faixa 7 de 42 · AAC · 256 kbps' });
      if (k === 'letraInfo') return JSON.stringify({ ligada: true, estado: c.letra ? 'pronta' : '', titulo: 'Tom Jobim — Garota de Ipanema', ajuste: 0, ia: true, iaAuto: true });
      if (k === 'listas') return JSON.stringify({ listas: [{ id: 'a', nome: 'Bossa Nova Essencial', tipo: 'musica', n: 42 }, { id: 'b', nome: 'Rádios do Brasil', tipo: 'radio', n: 318 }, { id: 'c', nome: 'Canais de TV abertos', tipo: 'tv', n: 120 }] });
      if (k === 'anuncios') return JSON.stringify({ ligado: true, bloqueados: 132 });
      if (k === 'downloads') return JSON.stringify({ disponivel: true, versao: '2026.08.19' });
      if (k === 'colar') return '';
      if (/^(legendas|audios|qualidades|dublagem|legendador|eqInfo|modoFilaAtual)$/.test(k)) return '{}';
      return '{}';
    } });
    try { localStorage.setItem('recentes', JSON.stringify(['https://www.youtube.com/watch?v=dQw4w9WgXcQ', 'https://www.instagram.com/reel/C8xYz', 'http://stream.eldorado.com.br:8000/live', 'https://music.youtube.com/playlist?list=PL123'])); } catch (e) {}
  }, c);
  await p.goto('file://' + html);
  await p.waitForTimeout(700);
  if (c.letra) await p.evaluate((d) => window.letra && letra(d), LET);
  await p.waitForTimeout(900);
  const m = await p.evaluate(() => {
    const vis = (el) => { const r = el.getBoundingClientRect(), s = getComputedStyle(el); return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none' && r.bottom > 0 && r.right > 0; };
    const alvos = [...document.querySelectorAll('button,input,select,label.chave,textarea')].filter(vis);
    const pequenos = alvos.filter((el) => { const r = el.getBoundingClientRect(); return (r.width < 40 || r.height < 40) && !(el.tagName === 'INPUT' && el.type === 'range'); })
      .map((el) => (el.id || el.getAttribute('aria-label') || el.textContent.trim().slice(0, 18) || el.tagName) + ' ' + Math.round(el.getBoundingClientRect().width) + 'x' + Math.round(el.getBoundingClientRect().height));
    const doc = document.scrollingElement;
    // texto cortado sem reticências
    const cortados = [...document.querySelectorAll('b,span,div,small,button')].filter(vis).filter((el) => el.children.length === 0 && el.scrollWidth > el.clientWidth + 2 && getComputedStyle(el).textOverflow !== 'ellipsis' && getComputedStyle(el).overflow === 'hidden').map((el) => el.id || el.textContent.trim().slice(0, 24));
    const vaza = [...document.querySelectorAll('body *')].filter(vis).filter((el) => el.getBoundingClientRect().right > innerWidth + 1 && !el.closest('#gaveta') && !el.closest('.grupos') && !el.closest('.agora')).map((el) => el.id || el.className || el.tagName).slice(0, 6);
    const pg = document.querySelector('.pagina.on'); const interna = pg && getComputedStyle(document.body).display !== 'none' && !document.body.classList.contains('compacto') ? Math.max(0, pg.scrollHeight - pg.clientHeight) : 0;
    return { altura: doc.scrollHeight, vh: innerHeight, rolagem: Math.max(0, doc.scrollHeight - innerHeight) + interna, largura: doc.scrollWidth > innerWidth, alvos: alvos.length, pequenos, cortados: cortados.slice(0, 8), vaza };
  });
  await p.screenshot({ path: `${saida}/${nomeTela}-${nome}.png`, fullPage: true });
  res.push({ tela: `${nomeTela} ${W}x${h}`, cena: nome, ...m, erros });
  await p.close();
}
await b.close();
for (const r of res) console.log(`${r.tela.padEnd(22)} ${r.cena.padEnd(13)} rolagem=${String(r.rolagem).padStart(4)}px  vazaLado=${r.largura ? 'SIM' : 'não'}  alvos=${r.alvos} pequenos=${r.pequenos.length}${r.pequenos.length ? ' [' + r.pequenos.slice(0, 4).join(', ') + ']' : ''}${r.cortados.length ? ' cortados=' + JSON.stringify(r.cortados) : ''}${r.vaza.length ? ' vaza=' + JSON.stringify(r.vaza) : ''}${r.erros.length ? ' ERROS=' + JSON.stringify(r.erros) : ''}`);
