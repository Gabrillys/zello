// Monta e assina o APK do Tocador sem Android SDK.
// Uso: node ferramentas/empacotar.mjs <classes.dex> <pasta-aar-libvlc> <saida.apk>
//
// Reaproveita as peças do antigo "Gerador de APK" (manifesto binário, resources.arsc e
// assinatura v2), agora com compressão (deflate) e as bibliotecas nativas do libVLC.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { webcrypto } from 'node:crypto';

const subtle = webcrypto.subtle;
const enc = new TextEncoder();
const AQUI = path.dirname(new URL(import.meta.url).pathname);
const RAIZ = path.resolve(AQUI, '..');

const CONFIG = {
  nome: 'Tocador',
  pacote: 'app.tocador',
  versionCode: 24,
  versionName: '4.0',
  minSdk: 24,
  targetSdk: 34,
  abis: ['arm64-v8a', 'armeabi-v7a'],
};

// ---------------------------------------------------------------- utilitários
class W {
  constructor() { this.a = []; }
  u8(v) { this.a.push(v & 255); return this; }
  u16(v) { this.a.push(v & 255, (v >> 8) & 255); return this; }
  u32(v) { v = v >>> 0; this.a.push(v & 255, (v >>> 8) & 255, (v >>> 16) & 255, (v >>> 24) & 255); return this; }
  u64(v) { this.u32(v % 4294967296); this.u32(Math.floor(v / 4294967296)); return this; }
  bytes(b) { for (let i = 0; i < b.length; i++) this.a.push(b[i]); return this; }
  get length() { return this.a.length; }
  pad(n) { while (this.a.length % n) this.a.push(0); return this; }
  out() { return Uint8Array.from(this.a); }
}
const cat = (...parts) => Buffer.concat(parts.map((p) => Buffer.from(p.buffer, p.byteOffset, p.byteLength)));
const sha = async (alg, data) => new Uint8Array(await subtle.digest(alg, data));
const b64 = (u8) => Buffer.from(u8).toString('base64');
const unb64 = (s) => new Uint8Array(Buffer.from(s, 'base64'));

// ---------------------------------------------------------------- AndroidManifest.xml binário
const RID = {
  theme: 0x01010000, label: 0x01010001, icon: 0x01010002, name: 0x01010003, exported: 0x01010010,
  launchMode: 0x0101001d, configChanges: 0x0101001f, mimeType: 0x01010026, minSdkVersion: 0x0101020c,
  versionCode: 0x0101021b, versionName: 0x0101021c, windowSoftInputMode: 0x0101022b, targetSdkVersion: 0x01010270,
  hardwareAccelerated: 0x010102d3, extractNativeLibs: 0x010104ea, usesCleartextTraffic: 0x010104ec,
  foregroundServiceType: 0x01010599, maxSdkVersion: 0x01010271,
};
const NS_URI = 'http://schemas.android.com/apk/res/android';

function manifesto(o) {
  const A = (name, type, value) => ({ ns: name in RID, name, type, value });
  const el = (tag, attrs, children) => ({ tag, attrs, children: children || [] });
  const perm = (p) => el('uses-permission', [A('name', 'str', p)]);
  const acao = (a) => el('action', [A('name', 'str', a)]);
  const cat_ = (c) => el('category', [A('name', 'str', c)]);
  const dado = (m) => el('data', [A('mimeType', 'str', m)]);
  const tree = el('manifest', [A('versionCode', 'int', o.versionCode), A('versionName', 'str', o.versionName), A('package', 'str', o.pacote)], [
    el('uses-sdk', [A('minSdkVersion', 'int', o.minSdk), A('targetSdkVersion', 'int', o.targetSdk)]),
    perm('android.permission.INTERNET'),
    perm('android.permission.ACCESS_NETWORK_STATE'),
    perm('android.permission.ACCESS_WIFI_STATE'),
    perm('android.permission.WAKE_LOCK'),
    perm('android.permission.FOREGROUND_SERVICE'),
    perm('android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK'),
    perm('android.permission.POST_NOTIFICATIONS'),
    perm('android.permission.MODIFY_AUDIO_SETTINGS'),
    // Animação da rádio: o Android só entrega o espectro do som (Visualizer) com esta permissão.
    perm('android.permission.RECORD_AUDIO'),
    // Só Android 9 ou anterior precisa disto para salvar legendas na pasta Download.
    el('uses-permission', [A('name', 'str', 'android.permission.WRITE_EXTERNAL_STORAGE'), A('maxSdkVersion', 'int', 28)]),
    // Android 11+: sem isto o app não enxerga os motores de voz instalados (usados na dublagem).
    el('queries', [], [el('intent', [], [acao('android.intent.action.TTS_SERVICE')])]),
    el('application', [A('label', 'str', o.nome), A('icon', 'ref', 0x7f010000), A('hardwareAccelerated', 'bool', true),
      A('extractNativeLibs', 'bool', true), A('usesCleartextTraffic', 'bool', true)], [
      el('activity', [A('label', 'str', o.nome), A('name', 'str', 'app.tocador.Principal'), A('exported', 'bool', true),
        A('launchMode', 'int', 2), A('configChanges', 'hex', 0xfb0), A('windowSoftInputMode', 'hex', 0x10)], [
        el('intent-filter', [], [acao('android.intent.action.MAIN'), cat_('android.intent.category.LAUNCHER')]),
        // "Compartilhar" de qualquer app (YouTube, Instagram, TikTok…) manda o link para cá.
        el('intent-filter', [], [acao('android.intent.action.SEND'), cat_('android.intent.category.DEFAULT'), dado('text/plain')]),
        // Abrir arquivos e links de áudio/vídeo com o Tocador.
        el('intent-filter', [], [acao('android.intent.action.VIEW'), cat_('android.intent.category.DEFAULT'), cat_('android.intent.category.BROWSABLE'), dado('audio/*')]),
        el('intent-filter', [], [acao('android.intent.action.VIEW'), cat_('android.intent.category.DEFAULT'), cat_('android.intent.category.BROWSABLE'), dado('video/*')]),
      ]),
      // foregroundServiceType 0x2 = mediaPlayback
      el('service', [A('name', 'str', 'app.tocador.Servico'), A('exported', 'bool', false), A('foregroundServiceType', 'hex', 0x2)]),
    ]),
  ]);

  const resNames = [];
  const visit = (n) => { n.attrs.forEach((a) => { if (a.ns && !resNames.includes(a.name)) resNames.push(a.name); }); n.children.forEach(visit); };
  visit(tree);
  resNames.sort((a, b) => RID[a] - RID[b]);
  const pool = resNames.slice();
  const add = (s) => { if (!pool.includes(s)) pool.push(s); return pool.indexOf(s); };
  add('android'); add(NS_URI);
  const walk = (n) => { add(n.tag); n.attrs.forEach((a) => { add(a.name); if (a.type === 'str') add(a.value); }); n.children.forEach(walk); };
  walk(tree);
  const idx = (s) => pool.indexOf(s);

  const strData = new W(); const offs = [];
  pool.forEach((s) => { offs.push(strData.length); strData.u16(s.length); for (let i = 0; i < s.length; i++) strData.u16(s.charCodeAt(i)); strData.u16(0); });
  strData.pad(4);
  const sp = new W();
  sp.u16(0x0001).u16(28).u32(28 + 4 * pool.length + strData.length).u32(pool.length).u32(0).u32(0).u32(28 + 4 * pool.length).u32(0);
  offs.forEach((x) => sp.u32(x)); sp.bytes(strData.out());

  const rm = new W(); rm.u16(0x0180).u16(8).u32(8 + 4 * resNames.length); resNames.forEach((n) => rm.u32(RID[n]));

  const body = new W();
  const nsChunk = (type) => { body.u16(type).u16(16).u32(24).u32(1).u32(0xFFFFFFFF).u32(idx('android')).u32(idx(NS_URI)); };
  nsChunk(0x0100);
  const emit = (n) => {
    const attrs = n.attrs.slice().sort((a, b) => (a.ns && b.ns) ? RID[a.name] - RID[b.name] : (a.ns ? -1 : b.ns ? 1 : 0));
    body.u16(0x0102).u16(16).u32(36 + 20 * attrs.length).u32(1).u32(0xFFFFFFFF).u32(0xFFFFFFFF).u32(idx(n.tag));
    body.u16(0x14).u16(0x14).u16(attrs.length).u16(0).u16(0).u16(0);
    attrs.forEach((a) => {
      body.u32(a.ns ? idx(NS_URI) : 0xFFFFFFFF).u32(idx(a.name));
      if (a.type === 'str') body.u32(idx(a.value)).u16(8).u8(0).u8(0x03).u32(idx(a.value));
      else if (a.type === 'int') body.u32(0xFFFFFFFF).u16(8).u8(0).u8(0x10).u32(a.value);
      else if (a.type === 'hex') body.u32(0xFFFFFFFF).u16(8).u8(0).u8(0x11).u32(a.value);
      else if (a.type === 'ref') body.u32(0xFFFFFFFF).u16(8).u8(0).u8(0x01).u32(a.value);
      else body.u32(0xFFFFFFFF).u16(8).u8(0).u8(0x12).u32(a.value ? 0xFFFFFFFF : 0);
    });
    n.children.forEach(emit);
    body.u16(0x0103).u16(16).u32(24).u32(1).u32(0xFFFFFFFF).u32(0xFFFFFFFF).u32(idx(n.tag));
  };
  emit(tree);
  nsChunk(0x0101);
  const parts = [sp.out(), rm.out(), body.out()];
  const total = 8 + parts.reduce((s, p) => s + p.length, 0);
  return cat(new W().u16(3).u16(8).u32(total).out(), ...parts);
}

// ---------------------------------------------------------------- resources.arsc (só o ícone)
function poolChunk(list) {
  const data = new W(); const offs = [];
  list.forEach((s) => { offs.push(data.length); data.u16(s.length); for (let i = 0; i < s.length; i++) data.u16(s.charCodeAt(i)); data.u16(0); });
  data.pad(4);
  const w = new W(); w.u16(0x0001).u16(28).u32(28 + 4 * list.length + data.length).u32(list.length).u32(0).u32(0).u32(28 + 4 * list.length).u32(0);
  offs.forEach((x) => w.u32(x)); w.bytes(data.out());
  return w.out();
}
function arsc(pacote, caminho) {
  const globalPool = poolChunk([caminho]);
  const typePool = poolChunk(['mipmap']);
  const keyPool = poolChunk(['ic_launcher']);
  const spec = new W().u16(0x0202).u16(16).u32(20).u8(1).u8(0).u16(0).u32(1).u32(0).out();
  const cfg = new W().u32(28); for (let i = 0; i < 24; i++) cfg.u8(0);
  const entry = new W().u16(8).u16(0).u32(0).u16(8).u8(0).u8(3).u32(0).out();
  const type = new W().u16(0x0201).u16(48).u32(48 + 4 + entry.length).u8(1).u8(0).u16(0).u32(1).u32(52).bytes(cfg.out()).u32(0).bytes(entry).out();
  const nome = new W(); for (let i = 0; i < 128; i++) nome.u16(i < pacote.length ? pacote.charCodeAt(i) : 0);
  const pkgBody = cat(typePool, keyPool, spec, type);
  const pkgHead = new W().u16(0x0200).u16(288).u32(288 + pkgBody.length).u32(0x7f).bytes(nome.out()).u32(288).u32(1).u32(288 + typePool.length).u32(1).u32(0).out();
  const total = 12 + globalPool.length + pkgHead.length + pkgBody.length;
  return cat(new W().u16(2).u16(12).u32(total).u32(1).out(), globalPool, pkgHead, pkgBody);
}

// ---------------------------------------------------------------- ZIP (deflate + alinhamento)
function zip(entradas) {
  const partes = []; const cd = []; let off = 0; let n = 0;
  for (const e of entradas) {
    const nome = enc.encode(e.name);
    const dados = Buffer.from(e.data);
    const crc = zlib.crc32(dados) >>> 0;
    const comprimir = !e.store;
    const corpo = comprimir ? zlib.deflateRawSync(dados, { level: 9 }) : dados;
    const metodo = comprimir ? 8 : 0;
    // Entradas sem compressão ficam alinhadas (4 bytes; .so em 4096) como o zipalign faz.
    let extra = 0;
    if (!comprimir) {
      const al = e.align || 4;
      extra = (al - ((off + 30 + nome.length) % al)) % al;
      if (extra > 0 && extra < 4) extra += al;
    }
    const h = new W().u32(0x04034b50).u16(20).u16(0x0800).u16(metodo).u16(0).u16(0x21).u32(crc).u32(corpo.length).u32(dados.length).u16(nome.length).u16(extra);
    h.bytes(nome);
    if (extra) { h.u16(0xd935).u16(extra - 4); for (let i = 0; i < extra - 4; i++) h.u8(0); }
    const cab = h.out();
    partes.push(cab, corpo);
    cd.push(new W().u32(0x02014b50).u16(20).u16(20).u16(0x0800).u16(metodo).u16(0).u16(0x21).u32(crc).u32(corpo.length).u32(dados.length)
      .u16(nome.length).u16(0).u16(0).u16(0).u16(0).u32(0).u32(off).bytes(nome).out());
    off += cab.length + corpo.length; n++;
  }
  const entries = Buffer.concat(partes.map((p) => Buffer.from(p.buffer ? p : p)));
  const cdBytes = Buffer.concat(cd.map((p) => Buffer.from(p)));
  const eocd = new W().u32(0x06054b50).u16(0).u16(0).u16(n).u16(n).u32(cdBytes.length).u32(entries.length).u16(0).out();
  return { entries, cd: cdBytes, eocd };
}

// ---------------------------------------------------------------- certificado e assinatura v2
const der = (tag, ...c) => { const body = cat(...c); const l = body.length; const len = l < 128 ? [l] : l < 256 ? [0x81, l] : [0x82, l >> 8, l & 255]; return cat(Uint8Array.of(tag, ...len), body); };
const oid = (arr) => der(0x06, Uint8Array.from(arr));
const nameDer = (cn) => der(0x30, der(0x31, der(0x30, oid([0x55, 0x04, 0x03]), der(0x0c, enc.encode(cn)))));
const SHA256_RSA = der(0x30, oid([0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x01, 0x0b]), Uint8Array.of(5, 0));

async function criarAssinatura() {
  const kp = await subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: Uint8Array.of(1, 0, 1), hash: 'SHA-256' }, true, ['sign', 'verify']);
  const pkcs8 = new Uint8Array(await subtle.exportKey('pkcs8', kp.privateKey));
  const spki = new Uint8Array(await subtle.exportKey('spki', kp.publicKey));
  const serial = webcrypto.getRandomValues(new Uint8Array(8)); serial[0] = (serial[0] & 0x7f) | 0x01;
  const tbs = der(0x30,
    Uint8Array.of(0xa0, 3, 2, 1, 2), der(0x02, serial), SHA256_RSA, nameDer('Tocador'),
    der(0x30, der(0x17, enc.encode('260101000000Z')), der(0x18, enc.encode('20760101000000Z'))),
    nameDer('Tocador'), spki);
  const sig = new Uint8Array(await subtle.sign({ name: 'RSASSA-PKCS1-v1_5' }, kp.privateKey, tbs));
  const cert = der(0x30, tbs, SHA256_RSA, der(0x03, Uint8Array.of(0), sig));
  return { pkcs8: b64(pkcs8), spki: b64(spki), cert: b64(cert) };
}

async function assinarV2(z, assin) {
  const key = await subtle.importKey('pkcs8', unb64(assin.pkcs8), { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const chunkDigests = []; const CH = 1048576;
  for (const sec of [z.entries, z.cd, z.eocd]) {
    for (let o = 0; o < sec.length; o += CH) {
      const part = sec.subarray(o, Math.min(o + CH, sec.length));
      chunkDigests.push(await sha('SHA-256', cat(Uint8Array.of(0xa5), new W().u32(part.length).out(), part)));
    }
  }
  const top = await sha('SHA-256', cat(Uint8Array.of(0x5a), new W().u32(chunkDigests.length).out(), ...chunkDigests));
  const lp = (b) => cat(new W().u32(b.length).out(), b);
  const ALGO = new W().u32(0x0103).out();
  const digests = lp(lp(cat(ALGO, lp(top))));
  const certs = lp(lp(unb64(assin.cert)));
  const signedData = cat(digests, certs, lp(new Uint8Array(0)));
  const sig = new Uint8Array(await subtle.sign({ name: 'RSASSA-PKCS1-v1_5' }, key, signedData));
  const signer = cat(lp(signedData), lp(lp(cat(ALGO, lp(sig)))), lp(unb64(assin.spki)));
  const value = lp(lp(signer));
  const pair = cat(new W().u64(4 + value.length).out(), new W().u32(0x7109871a).out(), value);
  const size = pair.length + 8 + 16;
  return cat(new W().u64(size).out(), pair, new W().u64(size).out(), enc.encode('APK Sig Block 42'));
}

// ---------------------------------------------------------------- montar
async function main() {
  const [dexPath, aarDir, saida, ...extras] = process.argv.slice(2);
  if (!dexPath || !aarDir || !saida) { console.error('uso: node empacotar.mjs <classes.dex> <pasta-aar> <saida.apk> [pastas com jni/<abi>/*.so…]'); process.exit(1); }

  const chavePath = path.join(RAIZ, 'chave-assinatura.json');
  let assin;
  if (fs.existsSync(chavePath)) assin = JSON.parse(fs.readFileSync(chavePath, 'utf8'));
  else { assin = await criarAssinatura(); fs.writeFileSync(chavePath, JSON.stringify(assin, null, 2)); console.log('nova chave de assinatura criada:', chavePath); }

  const arquivos = [];
  arquivos.push({ name: 'AndroidManifest.xml', data: manifesto(CONFIG) });
  arquivos.push({ name: 'resources.arsc', data: arsc(CONFIG.pacote, 'res/mipmap/ic_launcher.png'), store: true });
  arquivos.push({ name: 'classes.dex', data: fs.readFileSync(dexPath) });
  const andar = (d, rel) => { for (const f of fs.readdirSync(d).sort()) { const p = path.join(d, f); const r = rel + '/' + f; if (fs.statSync(p).isDirectory()) andar(p, r); else arquivos.push({ name: 'assets' + r, data: fs.readFileSync(p) }); } };
  andar(path.join(RAIZ, 'assets'), '');
  const aarAssets = path.join(aarDir, 'assets');
  if (fs.existsSync(aarAssets)) andar(aarAssets, '');
  for (const e of extras) if (fs.existsSync(path.join(e, 'assets'))) andar(path.join(e, 'assets'), '');
  arquivos.push({ name: 'res/mipmap/ic_launcher.png', data: fs.readFileSync(path.join(RAIZ, 'res', 'icone.png')), store: true });
  for (const abi of CONFIG.abis) {
    for (const base of [aarDir, ...extras]) {
      const d = path.join(base, 'jni', abi);
      if (!fs.existsSync(d)) continue;
      for (const f of fs.readdirSync(d).filter((x) => x.endsWith('.so')).sort()) arquivos.push({ name: `lib/${abi}/${f}`, data: fs.readFileSync(path.join(d, f)) });
    }
  }

  const z = zip(arquivos);
  const bloco = await assinarV2(z, assin);
  const eocd = Buffer.from(z.eocd);
  eocd.writeUInt32LE(z.entries.length + bloco.length, 16);
  const apk = Buffer.concat([z.entries, bloco, z.cd, eocd]);
  fs.writeFileSync(saida, apk);
  console.log(`APK: ${saida} (${(apk.length / 1048576).toFixed(1)} MB, ${arquivos.length} arquivos)`);
}

main().catch((e) => { console.error(e); process.exit(1); });
