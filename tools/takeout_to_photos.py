#!/usr/bin/env python3
"""Processa exportações do Google Takeout (Google Fotos) que chegam ao Drive.

Para cada .zip "takeout" visível para a service account:
  1. baixa o zip (com retomada por Range e checagem de md5);
  2. extrai membro a membro (nunca precisa de 2x o tamanho do zip em disco);
  3. restaura photoTakenTime do .json no EXIF (exiftool);
  4. detecta duplicatas por SHA-256 do conteúdo (persistido entre zips/execuções);
  5. envia ao Google Fotos (photoslibrary.appendonly) no álbum "AAAA - Fotos" /
     "AAAA - Vídeos", ou "DUPLICATAS-PARA-REVISAR" se for duplicata;
  6. item que não sobe é copiado para --failed-dir (análise manual) e não trava o zip;
     só apaga o zip do Drive se todos os itens foram enviados ou separados;
  7. limpa os temporários locais antes de seguir para o próximo zip.

Progresso persiste em --state-dir (snapshot JSON + journal por item + log +
relatorio.md). Pode ser interrompido e re-executado a qualquer momento.

Variáveis de ambiente:
  GDRIVE_SA_KEY_B64        JSON da service account (base64)
  PHOTOS_CLIENT_ID / PHOTOS_CLIENT_SECRET / PHOTOS_REFRESH_TOKEN
                           OAuth com escopo photoslibrary.appendonly

Requisitos: exiftool no PATH; pip install -r tools/requirements.txt
"""
import argparse
import base64
import gzip
import hashlib
import json
import logging
import mimetypes
import os
import re
import shutil
import signal
import subprocess
import sys
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path

DRIVE_API = "https://www.googleapis.com/drive/v3"
DRIVE_UPLOAD_API = "https://www.googleapis.com/upload/drive/v3"
PHOTOS_API = "https://photoslibrary.googleapis.com/v1"
DRIVE_SCOPES = ["https://www.googleapis.com/auth/drive"]
PHOTOS_SCOPES = ["https://www.googleapis.com/auth/photoslibrary.appendonly"]
TOKEN_URI = "https://oauth2.googleapis.com/token"

DUP_ALBUM = "DUPLICATAS-PARA-REVISAR"
CHUNK = 8 * 1024 * 1024
SNAPSHOT_EVERY = 500  # itens entre snapshots completos (o journal cobre o resto)
STATE_SYNC_SECS = 300  # envia o estado ao Drive no meio do zip (sessões efêmeras somem)
STATE_PREFIX = "takeout-state-gz-b64:"  # estado no Drive = prefixo + base64(gzip(JSON))
GDOC_MIME = "application/vnd.google-apps.document"
GDOC_MAX_CHARS = 1_000_000  # Google Docs aceita ~1,02 milhão de caracteres

PHOTO_EXT = {".jpg", ".jpeg", ".png", ".gif", ".heic", ".heif", ".webp", ".tif",
             ".tiff", ".bmp", ".dng", ".cr2", ".nef", ".arw", ".raw"}
VIDEO_EXT = {".mp4", ".mov", ".m4v", ".avi", ".mkv", ".3gp", ".wmv", ".mpg",
             ".mpeg", ".mts", ".m2ts", ".webm"}
EXTRA_MIME = {".heic": "image/heic", ".heif": "image/heif", ".dng": "image/x-adobe-dng",
              ".mts": "video/mp2t", ".m2ts": "video/mp2t", ".3gp": "video/3gpp"}

EDIT_SUFFIXES = ("-editado", "-edited", "-bearbeitet", "-modifié", "-editada")
SUPP_FULL = "supplemental-metadata"

log = logging.getLogger("takeout")


class QuotaExceeded(Exception):
    """Cota da API do Google Fotos esgotada; parar e retomar depois."""


# --------------------------------------------------------------------------
# Estado persistente: snapshot + journal
# --------------------------------------------------------------------------
class State:
    def __init__(self, state_dir: Path):
        self.dir = state_dir
        self.dir.mkdir(parents=True, exist_ok=True)
        self.snap = self.dir / "state.json"
        self.journal = self.dir / "journal.jsonl"
        self.data = {"version": 1, "albums": {}, "hashes": {}, "zips": {},
                     "items": {}, "meta": {}}
        self._since_snapshot = 0
        self._load()
        self._jf = open(self.journal, "a", encoding="utf-8")

    def _load(self):
        if self.snap.exists():
            self.data = json.loads(self.snap.read_text(encoding="utf-8"))
        if self.journal.exists():
            with open(self.journal, encoding="utf-8") as f:
                for line in f:
                    try:
                        rec = json.loads(line)
                    except json.JSONDecodeError:  # última linha truncada por crash
                        continue
                    self._apply(rec)

    def _apply(self, rec):
        self.data["items"][rec["k"]] = rec["v"]
        if rec.get("h"):
            self.data["hashes"].setdefault(rec["h"], rec["v"].get("name", ""))

    def record_item(self, key, value, sha=None):
        rec = {"k": key, "v": value, "h": sha}
        self._apply(rec)
        self._jf.write(json.dumps(rec, ensure_ascii=False) + "\n")
        self._jf.flush()
        os.fsync(self._jf.fileno())
        self._since_snapshot += 1
        if self._since_snapshot >= SNAPSHOT_EVERY:
            self.save()

    def save(self):
        tmp = self.snap.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data, ensure_ascii=False), encoding="utf-8")
        os.replace(tmp, self.snap)
        self._jf.truncate(0)
        self._jf.seek(0)
        self._since_snapshot = 0

    # atalhos
    @property
    def albums(self): return self.data["albums"]
    @property
    def hashes(self): return self.data["hashes"]
    @property
    def zips(self): return self.data["zips"]
    @property
    def items(self): return self.data["items"]
    @property
    def meta(self): return self.data["meta"]


# --------------------------------------------------------------------------
# HTTP com retry
# --------------------------------------------------------------------------
def request_retry(session, method, url, *, attempts=6, file_path=None, **kw):
    """Retry com backoff em 429/5xx/erros de rede. Se file_path, reabre o arquivo a cada tentativa."""
    import requests
    delay = 2
    minute_waits = 0
    i = 0
    while i < attempts:
        try:
            if file_path is not None:
                with open(file_path, "rb") as fh:
                    r = session.request(method, url, data=fh, timeout=(30, 900), **kw)
            else:
                r = session.request(method, url, timeout=(30, 300), **kw)
        except requests.RequestException as e:
            log.warning("rede: %s (tentativa %d/%d)", e, i + 1, attempts)
            r = None
        if r is not None and r.status_code < 400:
            return r
        if r is not None and r.status_code not in (429, 500, 502, 503, 504):
            return r  # erro definitivo; quem chamou decide
        if r is not None and r.status_code == 429 and "per minute" in r.text and minute_waits < 30:
            # limite por minuto não é a cota diária: espera a janela virar e tenta de novo
            minute_waits += 1
            log.warning("HTTP 429 (limite por minuto) em %s; aguardando 65s (%d)", url, minute_waits)
            time.sleep(65)
            continue
        if r is not None:
            log.warning("HTTP %s em %s (tentativa %d/%d)", r.status_code, url, i + 1, attempts)
            if r.status_code == 429 and i >= 2:
                raise QuotaExceeded(f"429 em {url}: {r.text[:200]}")
        time.sleep(delay)
        delay = min(delay * 2, 120)
        i += 1
    raise RuntimeError(f"falhou após {attempts} tentativas: {method} {url}")


# --------------------------------------------------------------------------
# Google Drive (REST via AuthorizedSession; só precisa de google-auth + requests)
# --------------------------------------------------------------------------
def drive_session():
    from google.oauth2 import service_account
    from google.auth.transport.requests import AuthorizedSession
    info = json.loads(base64.b64decode(os.environ["GDRIVE_SA_KEY_B64"]))
    creds = service_account.Credentials.from_service_account_info(info, scopes=DRIVE_SCOPES)
    return AuthorizedSession(creds)


def list_takeout_zips(sess, folder_id=None):
    q = ("trashed = false and name contains 'takeout' "
         "and mimeType != 'application/vnd.google-apps.folder'")
    if folder_id:
        q += f" and '{folder_id}' in parents"
    out, token = [], None
    while True:
        params = {"q": q, "pageSize": 100, "supportsAllDrives": "true",
                  "includeItemsFromAllDrives": "true",
                  "fields": "nextPageToken, files(id,name,size,md5Checksum,parents,ownedByMe)"}
        if token:
            params["pageToken"] = token
        r = request_retry(sess, "GET", f"{DRIVE_API}/files", params=params)
        r.raise_for_status()
        j = r.json()
        out += [f for f in j.get("files", [])
                if f["name"].lower().endswith(".zip") and "takeout" in f["name"].lower()]
        token = j.get("nextPageToken")
        if not token:
            return sorted(out, key=lambda f: f["name"])


def download_zip(sess, f, dest: Path):
    """Baixa com retomada via Range; valida tamanho e md5."""
    import requests
    size = int(f["size"])
    url = f"{DRIVE_API}/files/{f['id']}"
    for restart in range(2):
        fails = 0
        while (dest.stat().st_size if dest.exists() else 0) < size:
            done = dest.stat().st_size if dest.exists() else 0
            try:
                with sess.get(url, params={"alt": "media", "supportsAllDrives": "true"},
                              headers={"Range": f"bytes={done}-"}, stream=True,
                              timeout=(30, 120)) as r:
                    if r.status_code not in (200, 206):
                        raise RuntimeError(f"download HTTP {r.status_code}: {r.text[:200]}")
                    mode = "ab" if (r.status_code == 206 and done) else "wb"
                    with open(dest, mode) as out:
                        for chunk in r.iter_content(CHUNK):
                            out.write(chunk)
            except (requests.RequestException, RuntimeError) as e:
                fails += 1
                log.warning("download interrompido (%s); retomando (%d/8)", e, fails)
                if fails >= 8:
                    raise
                time.sleep(min(2 ** fails, 60))
        want = f.get("md5Checksum")
        if not want:
            return
        h = hashlib.md5()
        with open(dest, "rb") as fh:
            for chunk in iter(lambda: fh.read(CHUNK), b""):
                h.update(chunk)
        if h.hexdigest() == want:
            return
        log.warning("md5 do zip não confere; baixando de novo")
        dest.unlink()
    raise RuntimeError(f"md5 inválido para {f['name']} após re-download")


def delete_from_drive(sess, f):
    """Apaga de vez; se não for permitido (não-dono), manda para a lixeira. Retorna 'deleted'|'trashed'|'failed'."""
    r = request_retry(sess, "DELETE", f"{DRIVE_API}/files/{f['id']}",
                      params={"supportsAllDrives": "true"})
    if r.status_code in (200, 204):
        return "deleted"
    log.warning("delete falhou (HTTP %s: %s); tentando lixeira", r.status_code, r.text[:200])
    r = request_retry(sess, "PATCH", f"{DRIVE_API}/files/{f['id']}",
                      params={"supportsAllDrives": "true"}, json={"trashed": True})
    return "trashed" if r.status_code == 200 else "failed"


def _decode_state(text):
    """Aceita JSON puro (formato antigo) ou STATE_PREFIX + base64(gzip(JSON))."""
    text = text.lstrip("﻿").strip()
    if text.startswith(STATE_PREFIX):
        raw = base64.b64decode("".join(text[len(STATE_PREFIX):].split()))
        text = gzip.decompress(raw).decode("utf-8")
    json.loads(text)  # valida antes de sobrescrever qualquer coisa local
    return text


def sync_state_from_drive(sess, file_id, state_dir: Path):
    """Restaura o estado do Drive. Estado existente mas ilegível ABORTA: começar do
    zero reenviaria tudo ao Google Fotos (duplicatas em massa)."""
    r = request_retry(sess, "GET", f"{DRIVE_API}/files/{file_id}",
                      params={"fields": "mimeType", "supportsAllDrives": "true"})
    r.raise_for_status()
    if r.json()["mimeType"] == GDOC_MIME:  # Google Doc não baixa com alt=media (403)
        r = request_retry(sess, "GET", f"{DRIVE_API}/files/{file_id}/export",
                          params={"mimeType": "text/plain"})
    else:
        r = request_retry(sess, "GET", f"{DRIVE_API}/files/{file_id}",
                          params={"alt": "media", "supportsAllDrives": "true"})
    r.raise_for_status()
    text = r.content.decode("utf-8-sig").strip()
    if not text:
        log.info("arquivo de estado no Drive vazio; começando do zero")
        return
    try:
        text = _decode_state(text)
    except Exception as e:  # noqa: BLE001
        sys.exit(f"estado no Drive ilegível ({e}); abortando para não reenviar tudo")
    (state_dir / "state.json").write_text(text, encoding="utf-8")
    (state_dir / "journal.jsonl").write_text("")
    log.info("estado restaurado do Drive (%d bytes)", len(text))


def sync_state_to_drive(sess, file_id, state: State):
    state.save()
    payload = STATE_PREFIX + base64.b64encode(gzip.compress(state.snap.read_bytes())).decode("ascii")
    if len(payload) > GDOC_MAX_CHARS:
        m = request_retry(sess, "GET", f"{DRIVE_API}/files/{file_id}",
                          params={"fields": "mimeType", "supportsAllDrives": "true"})
        if m.status_code != 200 or m.json().get("mimeType") == GDOC_MIME:
            # gravar truncaria o Doc; manter a última versão válida no Drive
            log.error("estado comprimido (%d chars) excede o limite de um Google Doc; NÃO salvo no "
                      "Drive. Use um arquivo comum (não-Docs) em --state-drive-file-id", len(payload))
            return False
    try:
        r = request_retry(sess, "PATCH", f"{DRIVE_UPLOAD_API}/files/{file_id}",
                          params={"uploadType": "media", "supportsAllDrives": "true"},
                          data=payload.encode("ascii"), headers={"Content-Type": "text/plain"})
    except RuntimeError as e:  # rede fora: o estado local continua valendo
        log.warning("não consegui salvar estado no Drive: %s", e)
        return False
    if r.status_code != 200:
        log.warning("não consegui salvar estado no Drive: HTTP %s %s", r.status_code, r.text[:200])
        return False
    log.info("estado salvo no Drive (%d chars)", len(payload))
    return True


# --------------------------------------------------------------------------
# Google Fotos
# --------------------------------------------------------------------------
class Photos:
    def __init__(self, state: State):
        from google.oauth2.credentials import Credentials
        from google.auth.transport.requests import AuthorizedSession, Request
        creds = Credentials(None, refresh_token=os.environ["PHOTOS_REFRESH_TOKEN"],
                            token_uri=TOKEN_URI, client_id=os.environ["PHOTOS_CLIENT_ID"],
                            client_secret=os.environ["PHOTOS_CLIENT_SECRET"],
                            scopes=PHOTOS_SCOPES)
        creds.refresh(Request())  # falha cedo se o refresh token estiver inválido
        self.sess = AuthorizedSession(creds)
        self.state = state

    def album_id(self, title):
        # Com appendonly só dá pra usar álbuns criados por este app, e não há
        # como listá-los: os IDs ficam SOMENTE no estado persistido.
        if title in self.state.albums:
            return self.state.albums[title]
        r = request_retry(self.sess, "POST", f"{PHOTOS_API}/albums", json={"album": {"title": title}})
        r.raise_for_status()
        self.state.albums[title] = r.json()["id"]
        self.state.save()
        log.info("álbum criado: %s", title)
        return self.state.albums[title]

    def upload(self, path: Path, mime, album_title, description=None):
        """Envia bytes + cria o item no álbum. Retorna media item id."""
        r = request_retry(self.sess, "POST", f"{PHOTOS_API}/uploads", file_path=path,
                          headers={"Content-Type": "application/octet-stream",
                                   "X-Goog-Upload-Content-Type": mime,
                                   "X-Goog-Upload-Protocol": "raw"})
        r.raise_for_status()
        token = r.text
        item = {"simpleMediaItem": {"uploadToken": token, "fileName": path.name}}
        if description:
            item["description"] = description
        body = {"albumId": self.album_id(album_title), "newMediaItems": [item]}
        r = request_retry(self.sess, "POST", f"{PHOTOS_API}/mediaItems:batchCreate", json=body)
        r.raise_for_status()
        res = r.json()["newMediaItemResults"][0]
        if res.get("status", {}).get("code", 0) != 0:
            raise RuntimeError(f"batchCreate: {res['status']}")
        return res["mediaItem"]["id"]


# --------------------------------------------------------------------------
# Metadados do Takeout (.json) e EXIF
# --------------------------------------------------------------------------
def json_base(fname):
    """'foto.jpg.supplemental-metadata(1).json' / '...supplemen.json' -> 'foto.jpg(1)' / 'foto.jpg'."""
    b = fname[:-5]
    m = re.match(r"^(.*?)\.(s[a-z-]*)(\(\d+\))?$", b)
    if m and SUPP_FULL.startswith(m.group(2)):
        return m.group(1) + (m.group(3) or "")
    return b


def candidate_bases(name):
    """Nomes de json prováveis para uma mídia ('foto(1).jpg' -> 'foto.jpg(1)', 'foto-editado.jpg' -> 'foto.jpg')."""
    stem, ext = os.path.splitext(name)
    num = ""
    m = re.match(r"^(.*)(\(\d+\))$", stem)
    if m:
        stem, num = m.group(1), m.group(2)
    stems = [stem] + [stem[:-len(s)] for s in EDIT_SUFFIXES if stem.endswith(s)]
    out = []
    for st in stems:
        for c in (f"{st}{ext}{num}", f"{st}{num}{ext}", f"{st}{ext}"):
            if c not in out:
                out.append(c)
    return out


def index_json(zf, names):
    """{diretório: {chave_minúscula: timestamp}} com chaves 'base' e 'title:<título>'."""
    idx = {}
    for n in names:
        p = Path(n)
        if p.suffix.lower() != ".json" or p.name.lower() == "metadata.json":
            continue
        try:
            j = json.loads(zf.read(n))
            ts = int(j["photoTakenTime"]["timestamp"])
        except (KeyError, ValueError, TypeError, json.JSONDecodeError):
            continue
        d = idx.setdefault(str(p.parent), {})
        d[json_base(p.name).lower()] = ts
        if j.get("title"):
            d["title:" + str(j["title"]).lower()] = ts
    return idx


def lookup_ts(member, *indexes):
    p = Path(member)
    d, name = str(p.parent), p.name
    for idx in indexes:
        entry = idx.get(d)
        if not entry:
            continue
        cands = [c.lower() for c in candidate_bases(name)]
        for c in cands:
            if c in entry:
                return entry[c]
        for c in cands:
            if "title:" + c in entry:
                return entry["title:" + c]
        stem = p.stem.lower()
        # live photo (.MP4 ao lado de .HEIC) e nomes truncados pelo Takeout (~46 chars)
        for k, ts in entry.items():
            kb = k[6:] if k.startswith("title:") else k
            if os.path.splitext(kb)[0] == stem:
                return ts
            if len(stem) >= 40 and kb.startswith(stem):
                return ts
    return None


def exiftool_read_ts(path):
    try:
        out = subprocess.run(
            ["exiftool", "-j", "-d", "%s", "-api", "QuickTimeUTC=1", "-DateTimeOriginal",
             "-CreateDate", "-MediaCreateDate", str(path)],
            capture_output=True, text=True, timeout=120).stdout
        d = json.loads(out)[0]
        for k in ("DateTimeOriginal", "CreateDate", "MediaCreateDate"):
            v = str(d.get(k, ""))
            if v.isdigit() and int(v) > 86400 * 366:  # ignora 0000:00:00
                return int(v)
    except Exception:
        pass
    return None


def exiftool_write_ts(path, ts, is_video):
    dt = datetime.fromtimestamp(ts, tz=timezone.utc).strftime("%Y:%m:%d %H:%M:%S")
    args = ["exiftool", "-overwrite_original", "-m", "-q", "-q", f"-AllDates={dt}",
            f"-FileModifyDate={dt}+00:00"]
    if is_video:
        args += ["-api", "QuickTimeUTC=1"] + [f"-{t}={dt}" for t in (
            "CreateDate", "ModifyDate", "TrackCreateDate", "TrackModifyDate",
            "MediaCreateDate", "MediaModifyDate")]
    r = subprocess.run(args + [str(path)], capture_output=True, text=True, timeout=600)
    if r.returncode != 0:
        log.warning("exiftool falhou em %s: %s", path.name, (r.stderr or r.stdout).strip()[:200])
        return False
    return True


# --------------------------------------------------------------------------
# Processamento de um zip
# --------------------------------------------------------------------------
def kind_of(name):
    ext = Path(name).suffix.lower()
    if ext in PHOTO_EXT:
        return "photo"
    if ext in VIDEO_EXT:
        return "video"
    return None


def mime_of(name, kind):
    ext = Path(name).suffix.lower()
    return EXTRA_MIME.get(ext) or mimetypes.guess_type(name)[0] or (
        "video/mp4" if kind == "video" else "image/jpeg")


def process_zip(f, state: State, photos: Photos, workdir: Path, sess, args):
    zid = f["id"]
    zrec = state.zips.setdefault(zid, {"name": f["name"], "status": "new"})
    zrec["status"] = "in_progress"
    state.save()

    zpath = workdir / f["name"]
    tmpdir = workdir / "member"
    stats = {"uploaded": 0, "duplicate": 0, "failed": 0, "failed_saved": 0, "skipped": 0, "no_date": 0}
    try:
        need = int(f["size"])
        free = shutil.disk_usage(workdir).free
        if free < need + 2 * 1024 ** 3:
            raise RuntimeError(f"disco insuficiente: livre {free/1e9:.1f}GB, zip {need/1e9:.1f}GB (+2GB)")
        log.info("baixando %s (%.1f GB)", f["name"], need / 1e9)
        download_zip(sess, f, zpath)

        with zipfile.ZipFile(zpath) as zf:
            infos = [i for i in zf.infolist() if not i.is_dir()]
            biggest = max((i.file_size for i in infos), default=0)
            if shutil.disk_usage(workdir).free < biggest * 2 + 1024 ** 3:
                raise RuntimeError("disco insuficiente para extrair o maior arquivo")
            local_idx = index_json(zf, [i.filename for i in infos])
            for d, m in local_idx.items():  # guarda para JSONs que chegarem em outro zip
                state.meta.setdefault(d, {}).update(m)
            state.save()

            last_sync = time.monotonic()
            for n, info in enumerate(infos, 1):
                if args.state_drive_file_id and time.monotonic() - last_sync > STATE_SYNC_SECS:
                    sync_state_to_drive(sess, args.state_drive_file_id, state)
                    last_sync = time.monotonic()
                name = info.filename
                key = f"{zid}:{name}"
                if key in state.items:
                    if state.items[key]["status"] != "failed":
                        continue
                    # falhou numa execução anterior: não reenvia, separa para análise manual
                    err = state.items[key].get("error", "")
                    stats["failed_saved"] += 1
                    state.record_item(key, save_failed(zf, info, f["name"], args.failed_dir, err))
                    continue
                kind = kind_of(name)
                if kind is None:
                    if Path(name).suffix.lower() != ".json":
                        log.debug("ignorado (não é mídia): %s", name)
                    stats["skipped"] += 1
                    state.record_item(key, {"status": "skipped", "name": name})
                    continue
                try:
                    res = process_member(zf, info, key, kind, local_idx, state, photos, tmpdir)
                    stats[res] = stats.get(res, 0) + 1
                except QuotaExceeded:
                    raise
                except Exception as e:  # noqa: BLE001 - um item ruim não pára o zip
                    log.error("FALHA %s: %s", name, e)
                    stats["failed_saved"] += 1
                    state.record_item(key, save_failed(zf, info, f["name"], args.failed_dir, str(e)[:300]))
                if n % 200 == 0:
                    log.info("%s: %d/%d itens", f["name"], n, len(infos))

        for k, v in zrec.get("stats", {}).items():  # soma com rodadas anteriores deste zip
            if k != "failed":
                stats[k] = stats.get(k, 0) + v
        zrec["stats"] = stats
        if stats["failed"] == 0:
            if args.no_delete:
                zrec["status"] = "done_not_deleted"
                log.info("%s concluído; --no-delete: zip mantido no Drive", f["name"])
            else:
                zrec["status"] = "done_" + delete_from_drive(sess, f)
                log.info("%s concluído; Drive: %s", f["name"], zrec["status"])
        else:
            zrec["status"] = "partial"
            log.warning("%s com %d falhas: zip MANTIDO no Drive", f["name"], stats["failed"])
    except QuotaExceeded:
        zrec["status"] = "interrupted"
        raise
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)
        zpath.unlink(missing_ok=True)
        state.save()


def save_failed(zf, info, zip_name, failed_dir, error):
    """Item que não subiu: copia o arquivo original para failed_dir/<zip>/ (análise manual)
    e registra como tratado, para não travar o zip. Retorna o registro de estado."""
    name = info.filename
    dest = Path(failed_dir) / Path(zip_name).stem / name.replace("Takeout/Google Fotos/", "")
    rec = {"status": "failed_saved", "name": name, "error": error}
    try:
        dest.parent.mkdir(parents=True, exist_ok=True)
        with zf.open(info) as src, open(dest, "wb") as dst:
            shutil.copyfileobj(src, dst, CHUNK)
        rec["saved_to"] = str(dest)
        log.warning("item com falha separado para análise: %s", dest)
    except Exception as e:  # noqa: BLE001 - nem a cópia deu certo: fica registrado no relatório
        rec["save_error"] = str(e)[:300]
        log.error("não consegui separar %s: %s", name, e)
    return rec


def process_member(zf, info, key, kind, local_idx, state, photos, tmpdir):
    name = info.filename
    tmpdir.mkdir(parents=True, exist_ok=True)
    tmp = tmpdir / Path(name).name
    sha = hashlib.sha256()
    try:
        with zf.open(info) as src, open(tmp, "wb") as dst:
            for chunk in iter(lambda: src.read(CHUNK), b""):
                sha.update(chunk)
                dst.write(chunk)
        digest = sha.hexdigest()  # hash do conteúdo ORIGINAL (antes de mexer no EXIF)

        ts = lookup_ts(name, local_idx, state.meta)
        source = "json"
        if ts is None:
            ts, source = exiftool_read_ts(tmp), "exif"
        date_written = False
        if ts is not None and source == "json":
            date_written = exiftool_write_ts(tmp, ts, kind == "video")

        label = "Fotos" if kind == "photo" else "Vídeos"
        is_dup = digest in state.hashes
        if is_dup:
            album = DUP_ALBUM
        elif ts is not None:
            album = f"{datetime.fromtimestamp(ts, tz=timezone.utc).year} - {label}"
        else:
            album = f"SEM-DATA - {label}"  # nem json nem EXIF: vai para revisão

        desc = f"Takeout: {name}" + (f" | igual a: {state.hashes[digest]}" if is_dup else "")
        mid = photos.upload(tmp, mime_of(name, kind), album, desc[:1000])
        state.record_item(key, {
            "status": "duplicate" if is_dup else "uploaded", "name": name, "album": album,
            "ts": ts, "date_src": source if ts is not None else None,
            "exif_written": date_written, "sha": digest, "media_id": mid,
            "dup_of": state.hashes.get(digest) if is_dup else None,
        }, sha=None if is_dup else digest)
        return "duplicate" if is_dup else ("uploaded" if ts is not None else "no_date")
    finally:
        tmp.unlink(missing_ok=True)


# --------------------------------------------------------------------------
# Relatório
# --------------------------------------------------------------------------
def write_report(state: State):
    items = state.items.values()
    by_album, dups, failed, nodate, saved = {}, [], [], [], []
    for it in items:
        if it["status"] in ("uploaded", "duplicate"):
            by_album[it["album"]] = by_album.get(it["album"], 0) + 1
        if it["status"] == "duplicate":
            dups.append(it)
        if it["status"] == "failed":
            failed.append(it)
        if it["status"] == "failed_saved":
            saved.append(it)
        if it["status"] == "uploaded" and it.get("ts") is None:
            nodate.append(it)
    up = sum(1 for i in state.items.values() if i["status"] == "uploaded")
    L = [f"# Relatório Takeout → Google Fotos", f"_Atualizado: {datetime.now(timezone.utc):%Y-%m-%d %H:%M} UTC_", "",
         f"- Enviados (únicos): **{up}**", f"- Duplicatas enviadas p/ revisão: **{len(dups)}**",
         f"- Falhas pendentes: **{len(failed)}**", f"- Falhas separadas p/ análise: **{len(saved)}**", f"- Sem data (json/EXIF): **{len(nodate)}**", "",
         "## Zips", "", "| Zip | Status | Enviados | Duplicatas | Falhas |", "|---|---|---|---|---|"]
    for z in state.zips.values():
        s = z.get("stats", {})
        L.append(f"| {z['name']} | {z['status']} | {s.get('uploaded', 0) + s.get('no_date', 0)} | "
                 f"{s.get('duplicate', 0)} | {s.get('failed', 0)} |")
    L += ["", "## Álbuns (itens)", ""] + [f"- {a}: {n}" for a, n in sorted(by_album.items())]
    if failed:
        L += ["", "## Falhas", ""] + [f"- `{i['name']}`: {i.get('error', '')}" for i in failed[:200]]
    if saved:
        L += ["", "## Falhas separadas para análise manual", ""] + [
            f"- `{i.get('saved_to') or i['name']}`: {i.get('error', '')}" for i in saved]
    if dups:
        L += ["", "## Duplicatas (primeiras 200)", ""] + [
            f"- `{i['name']}` = `{i.get('dup_of')}`" for i in dups[:200]]
    tmp = state.dir / "relatorio.md.tmp"
    tmp.write_text("\n".join(L) + "\n", encoding="utf-8")
    os.replace(tmp, state.dir / "relatorio.md")


# --------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--state-dir", default="takeout_state", help="onde ficam state/journal/log/relatório")
    ap.add_argument("--workdir", default=str(Path.home() / "takeout_work"), help="temporários (zip + membro extraído)")
    ap.add_argument("--folder-id", help="restringe a busca de zips a esta pasta do Drive")
    ap.add_argument("--state-drive-file-id", help="arquivo do Drive (compartilhado com a SA, editor) usado p/ "
                    "guardar o estado entre sessões efêmeras")
    ap.add_argument("--failed-dir", default="takeout_falhas",
                    help="onde copiar itens que não subiram (para análise/exclusão manual)")
    ap.add_argument("--max-zips", type=int, help="processa no máximo N zips nesta execução")
    ap.add_argument("--no-delete", action="store_true", help="não apaga o zip do Drive (útil no 1º teste)")
    ap.add_argument("--dry-run", action="store_true", help="só valida credenciais/ferramentas e lista zips")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    state_dir = Path(args.state_dir)
    state_dir.mkdir(parents=True, exist_ok=True)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s",
                        handlers=[logging.StreamHandler(), logging.FileHandler(state_dir / "takeout.log")])

    # preflight: falhar antes de baixar 10 GB
    missing = [v for v in ("GDRIVE_SA_KEY_B64", "PHOTOS_CLIENT_ID", "PHOTOS_CLIENT_SECRET",
                           "PHOTOS_REFRESH_TOKEN") if not os.environ.get(v)]
    if missing:
        sys.exit(f"variáveis de ambiente ausentes: {', '.join(missing)}")
    if not shutil.which("exiftool"):
        sys.exit("exiftool não encontrado (apt install libimage-exiftool-perl)")

    sess = drive_session()
    if args.state_drive_file_id and not (state_dir / "state.json").exists():
        sync_state_from_drive(sess, args.state_drive_file_id, state_dir)
    state = State(state_dir)
    photos = Photos(state)

    zips = list_takeout_zips(sess, args.folder_id)
    pending = [z for z in zips if state.zips.get(z["id"], {}).get("status") not in
               ("done_deleted", "done_trashed", "done_not_deleted")]
    # ordem: zip interrompido no meio, depois zips com falhas ("partial": só separa os itens
    # que falharam, e o zip fica liberado para apagar), depois os novos
    def _order(z):
        st = state.zips.get(z["id"], {}).get("status")
        return (st != "in_progress", st != "partial")
    pending.sort(key=_order)
    log.info("zips takeout no Drive: %d (pendentes: %d)", len(zips), len(pending))
    if args.dry_run or not pending:
        for z in pending:
            log.info("  %s  %.1f GB", z["name"], int(z["size"]) / 1e9)
        write_report(state)
        return 0

    workdir = Path(args.workdir)
    workdir.mkdir(parents=True, exist_ok=True)

    def _interrupt(signum, _frame):  # SIGTERM/SIGHUP (fim da sessão) = Ctrl+C: salva e sincroniza
        raise KeyboardInterrupt(signal.Signals(signum).name)
    signal.signal(signal.SIGTERM, _interrupt)
    signal.signal(signal.SIGHUP, _interrupt)
    code = 0
    try:
        for z in pending[:args.max_zips]:
            process_zip(z, state, photos, workdir, sess, args)
            write_report(state)
            if args.state_drive_file_id:
                sync_state_to_drive(sess, args.state_drive_file_id, state)
    except QuotaExceeded as e:
        log.error("cota da API esgotada; progresso salvo, rode de novo depois: %s", e)
        code = 3
    except KeyboardInterrupt:
        log.warning("interrompido; progresso salvo")
        code = 130
    finally:
        state.save()
        write_report(state)
        if args.state_drive_file_id:
            sync_state_to_drive(sess, args.state_drive_file_id, state)
        shutil.rmtree(workdir / "member", ignore_errors=True)
    return code


if __name__ == "__main__":
    sys.exit(main())
