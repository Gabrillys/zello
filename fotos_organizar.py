#!/usr/bin/env python3
"""Organiza o Google Fotos via Photos Library API.

Uso:
  python3 fotos_organizar.py --limit 25            # amostra, SIMULAÇÃO (nada é criado)
  python3 fotos_organizar.py --limit 25 --apply    # amostra, cria álbuns de verdade
  python3 fotos_organizar.py --apply               # biblioteca inteira

Nunca apaga nada. Credenciais vêm das variáveis de ambiente
PHOTOS_CLIENT_ID, PHOTOS_CLIENT_SECRET, PHOTOS_REFRESH_TOKEN.
"""
import argparse, hashlib, json, os, re, sys, time
from collections import defaultdict
from datetime import datetime, timezone

import requests

API = "https://photoslibrary.googleapis.com/v1"
TOKEN_URL = "https://oauth2.googleapis.com/token"
DUP_ALBUM = "DUPLICATAS-PARA-REVISAR"
TIME_WINDOW_S = 2  # "data próxima"


class Auth:
    def __init__(self):
        try:
            self.cid = os.environ["PHOTOS_CLIENT_ID"]
            self.secret = os.environ["PHOTOS_CLIENT_SECRET"]
            self.refresh = os.environ["PHOTOS_REFRESH_TOKEN"]
        except KeyError as e:
            sys.exit(f"Variável de ambiente ausente: {e}")
        self.token, self.exp = None, 0

    def header(self):
        if not self.token or time.time() > self.exp - 60:
            r = requests.post(TOKEN_URL, data={
                "client_id": self.cid, "client_secret": self.secret,
                "refresh_token": self.refresh, "grant_type": "refresh_token"}, timeout=30)
            if r.status_code != 200:
                sys.exit(f"Falha ao autenticar: {r.status_code} {r.text}")
            j = r.json()
            self.token, self.exp = j["access_token"], time.time() + j.get("expires_in", 3600)
            print(f"[auth] ok, escopos: {j.get('scope')}")
        return {"Authorization": f"Bearer {self.token}"}


def call(auth, method, url, **kw):
    for attempt in range(6):
        r = requests.request(method, url, headers=auth.header(), timeout=60, **kw)
        if r.status_code in (429, 500, 502, 503, 504):
            time.sleep(2 ** attempt)
            continue
        if not r.ok:
            sys.exit(f"{method} {url} -> {r.status_code}: {r.text[:500]}")
        return r
    sys.exit(f"Desisti após retries: {url}")


def list_items(auth, limit=None):
    items, token = [], None
    while True:
        params = {"pageSize": 100}
        if token:
            params["pageToken"] = token
        j = call(auth, "GET", f"{API}/mediaItems", params=params).json()
        items += j.get("mediaItems", [])
        print(f"[list] {len(items)} itens...", end="\r")
        token = j.get("nextPageToken")
        if not token or (limit and len(items) >= limit):
            break
    print()
    return items[:limit] if limit else items


def meta(it):
    m = it.get("mediaMetadata", {})
    ts = datetime.fromisoformat(m["creationTime"].replace("Z", "+00:00")) if "creationTime" in m else None
    return {
        "id": it["id"], "name": it.get("filename", ""), "mime": it.get("mimeType", ""),
        "ts": ts, "w": m.get("width"), "h": m.get("height"),
        "is_video": "video" in m or it.get("mimeType", "").startswith("video/"),
        "base": it.get("baseUrl"),
    }


def norm_name(n):
    # "IMG_1 (1).jpg" / "IMG_1-1.jpg" contam como o mesmo nome base
    stem, ext = os.path.splitext(n.lower())
    return re.sub(r"(\s\(\d+\)|-\d+|_copy|\scopy)$", "", stem) + ext


def candidates(metas):
    # A API NÃO expõe tamanho em bytes; usa-se largura x altura como proxy.
    groups = defaultdict(list)
    for m in metas:
        groups[(norm_name(m["name"]), m["w"], m["h"])].append(m)
    out = []
    for g in groups.values():
        if len(g) < 2:
            continue
        g.sort(key=lambda m: m["ts"] or datetime.min.replace(tzinfo=timezone.utc))
        cluster = [g[0]]
        for m in g[1:]:
            if m["ts"] and cluster[-1]["ts"] and abs((m["ts"] - cluster[-1]["ts"]).total_seconds()) <= TIME_WINDOW_S:
                cluster.append(m)
            else:
                if len(cluster) > 1:
                    out.append(cluster)
                cluster = [m]
        if len(cluster) > 1:
            out.append(cluster)
    return out


def sha256_of(auth, m):
    # baseUrl expira em 60 min; =d baixa foto original, =dv vídeo original
    suffix = "=dv" if m["is_video"] else "=d"
    h = hashlib.sha256()
    with requests.get(m["base"] + suffix, headers=auth.header(), stream=True, timeout=300) as r:
        r.raise_for_status()
        for chunk in r.iter_content(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def find_duplicates(auth, metas):
    dups, originals = [], []
    for cluster in candidates(metas):
        by_hash = defaultdict(list)
        for m in cluster:
            by_hash[sha256_of(auth, m)].append(m)
        for same in by_hash.values():
            if len(same) > 1:
                # original = o de nome mais "limpo"/mais antigo (já ordenado por data)
                # sort estável: nome sem sufixo "(1)" primeiro; empate mantém o mais antigo
                same.sort(key=lambda m: os.path.splitext(m["name"].lower())[0] != os.path.splitext(norm_name(m["name"]))[0])
                originals.append(same[0])
                dups += same[1:]
    return dups, originals


def create_album(auth, title):
    return call(auth, "POST", f"{API}/albums", json={"album": {"title": title}}).json()["id"]


def add_to_album(auth, album_id, ids):
    for i in range(0, len(ids), 50):
        call(auth, "POST", f"{API}/albums/{album_id}:batchAddMediaItems",
             json={"mediaItemIds": ids[i:i + 50]})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, help="processa só N itens (amostra)")
    ap.add_argument("--apply", action="store_true", help="cria álbuns (senão é simulação)")
    ap.add_argument("--state", default="fotos_state.json", help="guarda IDs de álbuns criados p/ não duplicar")
    a = ap.parse_args()

    auth = Auth()
    metas = [meta(i) for i in list_items(auth, a.limit)]
    print(f"[list] total: {len(metas)}")

    dups, originals = find_duplicates(auth, metas)
    dup_ids = {m["id"] for m in dups}
    print(f"[dup] {len(dups)} duplicatas confirmadas por SHA-256")
    for d in dups:
        print(f"   dup: {d['name']} ({d['id'][:8]})")

    plan = {DUP_ALBUM: [m["id"] for m in dups]}
    for m in metas:
        if m["id"] in dup_ids:
            continue
        year = m["ts"].year if m["ts"] else "SemData"
        plan.setdefault(f"{year} - {'Vídeos' if m['is_video'] else 'Fotos'}", []).append(m["id"])
    plan = {k: v for k, v in plan.items() if v}

    state = json.load(open(a.state)) if os.path.exists(a.state) else {}
    for title, ids in sorted(plan.items()):
        print(f"[plano] {title}: {len(ids)} itens")
        if a.apply:
            if title not in state:
                state[title] = create_album(auth, title)
                json.dump(state, open(a.state, "w"))
            add_to_album(auth, state[title], ids)

    print("\n===== RELATÓRIO =====")
    print(f"Modo: {'APLICADO' if a.apply else 'SIMULAÇÃO'}")
    print(f"Total de itens: {len(metas)}")
    print(f"Duplicatas: {len(dups)}")
    print(f"Álbuns {'criados' if a.apply else 'a criar'}: {len(plan)}")
    for t, ids in sorted(plan.items()):
        print(f"  {t}: {len(ids)}")


if __name__ == "__main__":
    main()
