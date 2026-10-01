#!/usr/bin/env python3
"""Separa para revisão manual o que o takeout_to_photos.py enviou ao Google Fotos.

A Library API não apaga fotos; este script só cria álbuns "REVISAR - ..." (com
itens criados pelo próprio app) para a pessoa conferir e apagar no app.

Etapas (cada uma retomável; o progresso fica em --work-dir):
  collect   lista os itens do app, baixa miniaturas, calcula hash perceptual
            e lê o texto (OCR) de imagens do WhatsApp/prints/figurinhas
  classify  agrupa imagens visualmente iguais e classifica cada item
  albums    cria os álbuns de revisão e adiciona os itens (só com --apply)

Categorias (um item cai só na primeira que casar, nesta ordem):
  wa_copy    cópia do WhatsApp de uma foto/vídeo que também existe "original"
  chain      corrente: imagem do WhatsApp recebida várias vezes ou com texto
             de saudação/mensagem ("bom dia", "Deus", ...)
  screen     prints e gravações de tela
  sticker    figurinhas (.webp)
  wa_other   demais itens recebidos pelo WhatsApp
Itens fora dessas categorias ficam onde estão.

Variáveis: PHOTOS_CLIENT_ID, PHOTOS_CLIENT_SECRET e PHOTOS_RW_REFRESH_TOKEN
(OAuth com photoslibrary.readonly.appcreateddata + edit.appcreateddata).
Requisitos: pip install imagehash pillow requests; tesseract-ocr com idioma "por".
"""
import argparse
import io
import json
import logging
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import requests

PHOTOS_API = "https://photoslibrary.googleapis.com/v1"
TOKEN_URI = "https://oauth2.googleapis.com/token"
PHASH_MAX_DIST = 6  # distância de Hamming (64 bits) para "mesma imagem"
ALBUMS = {
    "wa_copy": "REVISAR - WhatsApp cópia de original",
    "chain": "REVISAR - Correntes (bom dia etc.)",
    "screen": "REVISAR - Prints e gravações de tela",
    "sticker": "REVISAR - Figurinhas",
    "wa_other": "REVISAR - WhatsApp outros",
}
CHAIN_WORDS = re.compile(
    r"\b(bom dia|boa tarde|boa noite|bom domingo|boa semana|bom final de semana|bom fim de semana|"
    r"feliz (domingo|segunda|ter[cç]a|quarta|quinta|sexta|s[aá]bado|natal|ano novo|p[aá]scoa)|"
    r"sextou|deus|jesus|senhor|am[eé]m|aben[cç]o|b[eê]n[cç][aã]o|gratid[aã]o|paz|f[eé]|ora[cç][aã]o|"
    r"mensagem|compartilhe|repasse|envie para)\b", re.I)

WA_RE = re.compile(r"(^(img|vid|aud|ptt)-\d{8}-wa\d+)|-wa\d+|whatsapp", re.I)
SCREEN_RE = re.compile(r"screenshot|screen_shot|screen_recording|screenrecord|captura|^print", re.I)

log = logging.getLogger("review")


class Photos:
    def __init__(self):
        self.tok, self.exp, self.lock = None, 0, threading.Lock()

    def token(self):
        with self.lock:
            if time.time() > self.exp - 120:
                r = requests.post(TOKEN_URI, data={
                    "client_id": os.environ["PHOTOS_CLIENT_ID"],
                    "client_secret": os.environ["PHOTOS_CLIENT_SECRET"],
                    "refresh_token": os.environ["PHOTOS_RW_REFRESH_TOKEN"],
                    "grant_type": "refresh_token"}, timeout=30)
                r.raise_for_status()
                self.tok, self.exp = r.json()["access_token"], time.time() + r.json()["expires_in"]
            return self.tok

    def call(self, method, path, **kw):
        for i in range(8):
            r = requests.request(method, PHOTOS_API + path, timeout=60,
                                 headers={"Authorization": "Bearer " + self.token()}, **kw)
            if r.status_code == 429 or r.status_code >= 500:
                time.sleep(min(65, 2 ** (i + 1)))
                continue
            r.raise_for_status()
            return r.json()
        r.raise_for_status()

    def list_items(self):
        out, tok = [], None
        while True:
            params = {"pageSize": 100}
            if tok:
                params["pageToken"] = tok
            j = self.call("GET", "/mediaItems", params=params)
            out += j.get("mediaItems", [])
            tok = j.get("nextPageToken")
            if not tok:
                return out


def kind_of(name, mime):
    low = name.lower()
    if low.endswith(".webp"):
        return "sticker"
    if SCREEN_RE.search(low):
        return "screen"
    if WA_RE.search(low):
        return "wa"
    return "own"


def ocr(img):
    with tempfile.NamedTemporaryFile(suffix=".png") as f:
        img.save(f.name)
        p = subprocess.run(["tesseract", f.name, "-", "-l", "por", "--psm", "3"],
                           capture_output=True, text=True, timeout=120)
    return " ".join(p.stdout.split())


def collect(args):
    from PIL import Image
    import imagehash
    out = Path(args.work_dir)
    out.mkdir(parents=True, exist_ok=True)
    db_path = out / "features.jsonl"
    done = set()
    if db_path.exists():
        for line in db_path.open(encoding="utf-8"):
            try:
                r = json.loads(line)
                if "error" not in r:  # itens com erro são tentados de novo
                    done.add(r["id"])
            except (json.JSONDecodeError, KeyError):
                continue
    ph = Photos()
    items = ph.list_items()
    log.info("itens do app no Google Fotos: %d (já analisados: %d)", len(items), len(done))
    todo = [m for m in items if m["id"] not in done]
    wlock = threading.Lock()
    blocked = {"streak": 0, "stop": False}  # o Google responde 403 em série quando limita o ritmo
    db = db_path.open("a", encoding="utf-8")
    t0 = time.time()

    def work(m):
        if blocked["stop"]:
            return
        name, mime = m.get("filename", ""), m.get("mimeType", "")
        kind = kind_of(name, mime)
        rec = {"id": m["id"], "name": name, "mime": mime, "kind": kind,
               "created": m.get("mediaMetadata", {}).get("creationTime"),
               "w": m.get("mediaMetadata", {}).get("width"), "h": m.get("mediaMetadata", {}).get("height"),
               "video": "video" in m.get("mediaMetadata", {})}
        try:
            size = "=w800-h800" if kind in ("wa", "sticker", "screen") else "=w256-h256"
            r = requests.get(m["baseUrl"] + size, timeout=60)
            if r.status_code == 403:
                with wlock:
                    blocked["streak"] += 1
                    if blocked["streak"] >= 30:
                        blocked["stop"] = True
                return  # não registra: tenta de novo na próxima rodada
            r.raise_for_status()
            blocked["streak"] = 0
            img = Image.open(io.BytesIO(r.content)).convert("RGB")
            rec["phash"] = str(imagehash.phash(img))
            rec["dhash"] = str(imagehash.dhash(img))
            if kind in ("wa", "sticker") and not rec["video"]:
                rec["text"] = ocr(img)[:500]
        except Exception as e:  # noqa: BLE001
            rec["error"] = str(e)[:200]
        with wlock:
            db.write(json.dumps(rec, ensure_ascii=False) + "\n")
            db.flush()

    with ThreadPoolExecutor(args.workers) as ex:
        for n, _ in enumerate(ex.map(work, todo), 1):
            if n % 250 == 0:
                log.info("%d/%d (%.0fs)", n, len(todo), time.time() - t0)
    db.close()
    if blocked["stop"]:
        log.warning("Google limitou os downloads (403 em série); rode de novo em alguns minutos")
        return 2
    log.info("coleta concluída")


def load_features(work_dir):
    recs = {}
    for line in (Path(work_dir) / "features.jsonl").open(encoding="utf-8"):
        try:
            r = json.loads(line)
        except json.JSONDecodeError:
            continue
        if "error" in r and r["id"] in recs:
            continue
        recs[r["id"]] = r
    return list(recs.values())


def classify(args):
    recs = load_features(args.work_dir)
    hashed = [r for r in recs if r.get("phash")]
    hs = [int(r["phash"], 16) for r in hashed]
    # grupos de imagens visualmente iguais (union-find; vídeos só com vídeos)
    parent = list(range(len(hashed)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i
    for i in range(len(hashed)):
        for j in range(i + 1, len(hashed)):
            if (hs[i] ^ hs[j]).bit_count() <= PHASH_MAX_DIST and hashed[i]["video"] == hashed[j]["video"]:
                parent[find(i)] = find(j)
    groups = {}
    for i, r in enumerate(hashed):
        groups.setdefault(find(i), []).append(r)
    for g in groups.values():
        for r in g:
            r["group"] = [x["id"] for x in g if x is not r]
            r["group_kinds"] = sorted({x["kind"] for x in g if x is not r})
            r["group_wa"] = sum(1 for x in g if x["kind"] == "wa")
    out = {}
    for r in recs:
        k = r["kind"]
        if k == "wa" and "own" in r.get("group_kinds", []):
            cat = "wa_copy"
        elif k in ("wa", "sticker") and not r.get("video") and (
                r.get("group_wa", 0) >= 2 or CHAIN_WORDS.search(r.get("text", ""))):
            cat = "chain"
        elif k == "screen":
            cat = "screen"
        elif k == "sticker":
            cat = "sticker"
        elif k == "wa":
            cat = "wa_other"
        else:
            continue
        out[r["id"]] = {"cat": cat, "name": r["name"], "text": r.get("text", "")[:120],
                        "same_as": [x for x in r.get("group", [])][:5]}
    (Path(args.work_dir) / "classification.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=0), encoding="utf-8")
    from collections import Counter
    c = Counter(v["cat"] for v in out.values())
    for cat, title in ALBUMS.items():
        log.info("%-45s %5d", title, c.get(cat, 0))
    log.info("ficam como estão: %d de %d", len(recs) - len(out), len(recs))


def albums(args):
    cls = json.loads((Path(args.work_dir) / "classification.json").read_text(encoding="utf-8"))
    ph = Photos()
    existing = {}
    tok = None
    while True:
        j = ph.call("GET", "/albums", params={"pageSize": 50, "excludeNonAppCreatedData": "true",
                                              **({"pageToken": tok} if tok else {})})
        existing.update({a["title"]: a["id"] for a in j.get("albums", [])})
        tok = j.get("nextPageToken")
        if not tok:
            break
    for cat, title in ALBUMS.items():
        ids = [i for i, v in cls.items() if v["cat"] == cat]
        if not ids:
            continue
        if not args.apply:
            log.info("[simulação] %s: %d itens", title, len(ids))
            continue
        aid = existing.get(title) or ph.call("POST", "/albums", json={"album": {"title": title}})["id"]
        for k in range(0, len(ids), 50):
            ph.call("POST", f"/albums/{aid}:batchAddMediaItems", json={"mediaItemIds": ids[k:k + 50]})
        log.info("%s: %d itens adicionados", title, len(ids))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("step", choices=["collect", "classify", "albums"])
    ap.add_argument("--work-dir", default="photos_review")
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--apply", action="store_true", help="albums: cria de fato (sem isto, só simula)")
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    return {"collect": collect, "classify": classify, "albums": albums}[args.step](args)


if __name__ == "__main__":
    sys.exit(main())
