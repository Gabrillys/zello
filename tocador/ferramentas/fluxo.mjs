import { chromium } from 'playwright-core';
const html = process.argv[2], out = process.argv[3];
const b = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium_headless_shell-1194/chrome-linux/headless_shell' });
const p = await b.newPage({ viewport: { width: 384, height: 823 }, deviceScaleFactor: 2 });
const erros = [], chamadas = [];
p.on('pageerror', (e) => erros.push(String(e)));
await p.addInitScript(() => {
  window.__modo = 'vlc';
  window.__chamadas = [];
  window.Tocador = new Proxy({}, { get: (_, k) => (...a) => {
    if (!/^(estado|radio|letraInfo|downloads|anuncios|dublagem|legendador|legendas|audios|listas|eqInfo|modoFilaAtual)$/.test(k)) window.__chamadas.push(k + '(' + a.join(',') + ')');
    if (k === 'estado') return JSON.stringify(window.__modo === 'vlc' ? { modo: 'vlc', situacao: 'tocando', tocando: true, titulo: 'Garota de Ipanema', video: false, busca: true, duracao: 318000, tempo: 12000, posicao: .04, volume: 100, url: 'file:///x.mp3' } : { modo: 'web', situacao: 'tocando', tocando: true, titulo: 'Vídeo do YouTube', busca: true, duracao: 600000, tempo: 1000, posicao: .01, podeCheio: true, expandido: true, qualidade: '1080p' });
    if (k === 'letraInfo') return JSON.stringify({ ligada: true, estado: 'pronta', titulo: 'Tom Jobim — Garota de Ipanema', ajuste: 300, ia: true, iaAuto: true });
    if (k === 'listas') return JSON.stringify({ listas: [{ id: 'a', nome: 'Bossa Nova Essencial', tipo: 'musica', n: 42 }, { id: 'b', nome: 'Rádios do Brasil', tipo: 'radio', n: 318 }, { id: 'c', nome: 'Músicas baixadas', tipo: 'musica', n: 17, baixada: true, doApp: 17 }] });
    if (k === 'dublagem') return JSON.stringify({ disponivel: true, yt: true, temLegenda: true, whisper: true, vozes: [{ chave: 'f', nome: 'Francisca (neural)' }], voz: 'f', modelos: [{ chave: 'base', nome: 'Guepardo: rápido, erra mais (Whisper base, cerca de 80 MB)', pronto: true, cabe: true }, { chave: 'small', nome: 'Golfinho: equilibrado e preciso (Whisper small, cerca de 250 MB)', pronto: false, cabe: true }, { chave: 'turbo', nome: 'Baleia: o melhor, bem mais lento (Whisper large-v3-turbo, cerca de 1 GB)', pronto: false, cabe: false }], modelo: 'small', volume: 20 });
    if (k === 'downloads') return JSON.stringify({ disponivel: true, versao: '2026.08.19' });
    if (k === 'anuncios') return JSON.stringify({ ligado: true, bloqueados: 132 });
    if (k === 'legendas') return JSON.stringify(window.__modo === 'vlc' ? { fonte: 'vlc', itens: [{ chave: 'vlc:1', nome: 'Português' }], podeBaixar: false, arquivos: [] } : { fonte: 'youtube', itens: [{ chave: 'yt:en', nome: 'Inglês' }, { chave: 'yt:pt', nome: 'Português' }], podeBaixar: true, podeTraduzir: true, arquivos: [] });
    if (k === 'legendador') return JSON.stringify({ disponivel: window.__modo === 'vlc', vlc: window.__modo === 'vlc', whisper: true, dublagem: window.__modo === 'web' });
    if (k === 'versao') return '4.4';
    if (k === 'eqInfo') return JSON.stringify({ motor: 'vlc', ligado: true, bandas: [60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000], niveis: [3, 2, 0, -1, 0, 1, 2, 3, 3, 2], min: -20, max: 20, presets: ['Flat', 'Rock'], preset: -1, temPreamp: true, preamp: 0 });
    return '{}';
  } });
});
await p.goto('file://' + html);
await p.waitForTimeout(700);
await p.evaluate(() => letra({ titulo: 'x', adiant: 500, ajuste: 0, linhas: [{ t: 1000, x: 'a' }, { t: 2000, x: 'b' }] }));
const foto = async (n) => { await p.waitForTimeout(450); await p.screenshot({ path: `${out}/f-${n}.png` }); };
const passo = async (n, f) => { try { await f(); await foto(n); console.log('ok   ', n); } catch (e) { console.log('FALHA', n, String(e).split('\n')[0]); process.exitCode = 1; } };
await passo('1-player', async () => {});
await passo('2-letra', async () => { await p.click('#acoes button:has-text("Letra")'); });
// Gaveta Legendas (arquivo no VLC): a lista de legendas e o legendador precisam montar sem erro de script.
await passo('2b-legendas', async () => {
  await p.click('#gFechar'); await p.waitForTimeout(300); await p.click('#acoes button:has-text("Legendas")'); await p.waitForTimeout(700);
  if ((await p.locator('#legLista .opcao').count()) < 1) throw new Error('a lista de legendas não foi montada');
  if (await p.locator('#lgIr').isHidden()) throw new Error('o botão Gerar legenda não apareceu');
});
await passo('3-mais', async () => { await p.click('#gFechar'); await p.waitForTimeout(300); await p.click('#acoes button:has-text("Mais")'); });
await passo('4-eq', async () => { await p.click('.menu-op:has-text("Equalizador")'); });
await passo('5-minimizado', async () => { await p.click('#gFechar'); await p.waitForTimeout(300); await p.click('#minimizar'); });
await passo('6-biblioteca', async () => { await p.click('#abas button[data-aba=pBiblioteca]'); });
await passo('7-lista', async () => { await p.click('#biblioteca .lst >> nth=0'); });
await passo('8-baixar', async () => { await p.click('#gFechar'); await p.waitForTimeout(300); await p.click('#abas button[data-aba=pBaixar]'); });
await passo('9-ajustes', async () => { await p.click('#abas button[data-aba=pAjustes]'); });
await passo('10-volta-player', async () => { await p.click('#mini'); });
await passo('11-youtube', async () => { await p.evaluate(() => { window.__modo = 'web'; }); await p.waitForTimeout(900); });
await passo('12-dublar', async () => { await p.click('#dubCartao'); });
// Gaveta Legendas no YouTube: lista de idiomas e botão de baixar o texto (era o que o 4.4 deixava sem ligar).
await passo('13-legendas-youtube', async () => {
  await p.click('#gFechar'); await p.waitForTimeout(300); await p.evaluate(() => abrirLegendas()); await p.waitForTimeout(900);
  if ((await p.locator('#legLista .opcao').count()) < 3) throw new Error('a lista de legendas do YouTube não foi montada');
  await p.click('#legLista .opcao >> nth=1'); await p.waitForTimeout(200);
  await p.click('#legBaixar');
  const feitas = await p.evaluate(() => window.__chamadas.filter((c) => c.startsWith('baixarLegenda(')));
  if (!feitas.length) throw new Error('"Baixar legenda como texto" não chamou o app');
});
// Ajustes do Whisper: os três níveis (Guepardo, Golfinho, Baleia), a Baleia desligada se faltar memória e a explicação do nível escolhido.
await passo('14-whisper-niveis', async () => {
  await p.click('#gFechar'); await p.waitForTimeout(300); await p.evaluate(() => abrirDublar()); await p.waitForTimeout(900);
  const ops = await p.locator('#dubModelo option').allTextContents();
  if (ops.length !== 3 || !/^Guepardo/.test(ops[0]) || !/^Golfinho/.test(ops[1]) || !/^Baleia/.test(ops[2])) throw new Error('níveis errados: ' + JSON.stringify(ops));
  if (!(await p.locator('#dubModelo option').nth(2).isDisabled())) throw new Error('a Baleia deveria estar desligada sem memória');
  const dica = await p.textContent('#dubModeloDica');
  if (!/Golfinho/.test(dica)) throw new Error('falta a explicação do nível: ' + dica);
});
console.log('chamadas:', JSON.stringify(await p.evaluate(() => window.__chamadas)));
console.log('erros:', JSON.stringify(erros));
await b.close();
if (erros.length) process.exitCode = 1;
