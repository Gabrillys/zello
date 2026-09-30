#!/usr/bin/env python3
"""Reorganiza a pasta do Drive em <pasta de topo>/<ANO>/<TIPO>/arquivo.

Ano = ano de modifiedTime. Arquivos soltos na raiz vão para <raiz>/<ANO>/<TIPO>.
Padrão: só gera o plano (reorg_plan.csv), sem mexer no Drive.
Com --apply: move os arquivos (addParents/removeParents; nada é apagado) e grava
reorg_log.csv com o pai original de cada arquivo, permitindo reverter.
Subpastas antigas que ficarem vazias NÃO são removidas.

Uso: python reorganize.py [--apply] [--plan reorg_plan.csv] [--log reorg_log.csv]
"""
import argparse
import csv
import os
from collections import Counter

from googleapiclient.discovery import build
from google.oauth2 import service_account

from dedup import FOLDER_MIME, SCOPES, list_children, write_credentials

TIPOS = {
    "Imagens": ("image/",),
    "Vídeos": ("video/",),
    "Áudio": ("audio/",),
    "Documentos": (
        "application/pdf", "text/", "application/msword", "application/rtf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.google-apps.document",
    ),
    "Planilhas": (
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.google-apps.spreadsheet",
    ),
    "Apresentações": (
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml",
        "application/vnd.oasis.opendocument.presentation",
        "application/vnd.google-apps.presentation",
    ),
    "Compactados": (
        "application/zip", "application/x-rar", "application/vnd.rar",
        "application/x-7z-compressed", "application/x-tar", "application/gzip",
        "application/x-zip-compressed",
    ),
}
EXT_TIPOS = {
    "Compactados": {"zip", "rar", "7z", "tar", "gz", "iso"},
    "Imagens": {"jpg", "jpeg", "png", "gif", "bmp", "heic", "webp", "tif", "tiff", "raw"},
    "Vídeos": {"mp4", "mov", "avi", "mkv", "wmv", "3gp"},
    "Áudio": {"mp3", "wav", "m4a", "flac", "ogg"},
    "Documentos": {"pdf", "doc", "docx", "txt", "rtf", "odt"},
    "Planilhas": {"xls", "xlsx", "csv", "ods"},
    "Apresentações": {"ppt", "pptx", "odp"},
}


def tipo_de(f):
    mime = f["mimeType"]
    for tipo, prefixos in TIPOS.items():
        if mime.startswith(prefixos):
            return tipo
    ext = f["name"].rsplit(".", 1)[-1].lower() if "." in f["name"] else ""
    for tipo, exts in EXT_TIPOS.items():
        if ext in exts:
            return tipo
    return "Outros"


def collect(svc, root_id):
    """Percorre a árvore. Retorna lista de arquivos com 'topo' (id da pasta de topo)."""
    files = []
    stack = [(root_id, root_id)]  # (pasta atual, pasta de topo)
    while stack:
        fid, topo = stack.pop()
        for item in list_children(svc, fid):
            if item["mimeType"] == FOLDER_MIME:
                stack.append((item["id"], item["id"] if fid == root_id else topo))
            else:
                item["topo"] = topo
                item["pai"] = fid
                files.append(item)
    return files


class Pastas:
    """Cria (ou reaproveita) pastas destino; em modo plano não cria nada."""

    def __init__(self, svc, apply):
        self.svc, self.apply, self.cache = svc, apply, {}

    def get(self, parent_id, name):
        key = (parent_id, name)
        if key in self.cache:
            return self.cache[key]
        safe = name.replace("'", "\\'")
        res = [] if parent_id.startswith("(nova:") else self.svc.files().list(
            q=f"'{parent_id}' in parents and name = '{safe}' and mimeType = '{FOLDER_MIME}' and trashed = false",
            fields="files(id)", supportsAllDrives=True, includeItemsFromAllDrives=True,
        ).execute().get("files", [])
        if res:
            fid = res[0]["id"]
        elif self.apply:
            fid = self.svc.files().create(
                body={"name": name, "mimeType": FOLDER_MIME, "parents": [parent_id]},
                fields="id", supportsAllDrives=True).execute()["id"]
        else:
            fid = f"(nova:{parent_id}/{name})"
        self.cache[key] = fid
        return fid


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--plan", default="reorg_plan.csv")
    ap.add_argument("--log", default="reorg_log.csv")
    args = ap.parse_args()

    root = os.environ["DRIVE_FOLDER_ID"]
    creds = service_account.Credentials.from_service_account_file(
        write_credentials(), scopes=SCOPES)
    svc = build("drive", "v3", credentials=creds, cache_discovery=False)

    files = collect(svc, root)
    pastas = Pastas(svc, args.apply)
    plano = []
    for f in files:
        ano = f["modifiedTime"][:4]
        tipo = tipo_de(f)
        dest = pastas.get(pastas.get(f["topo"], ano), tipo)
        plano.append((f, ano, tipo, dest))

    with open(args.plan, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["arquivo_id", "nome", "pai_atual", "ano", "tipo", "pasta_destino", "acao"])
        for f, ano, tipo, dest in plano:
            w.writerow([f["id"], f["name"], f["pai"], ano, tipo, dest,
                        "já no lugar" if f["pai"] == dest else "mover"])

    a_mover = [p for p in plano if p[0]["pai"] != p[3]]
    print(f"Arquivos: {len(files)} | a mover: {len(a_mover)}")
    print("Por tipo:", dict(Counter(p[2] for p in a_mover)))
    print("Por ano:", dict(sorted(Counter(p[1] for p in a_mover).items())))

    if not args.apply:
        print(f"Plano em {args.plan}. Nada foi movido (use --apply).")
        return

    import threading
    from concurrent.futures import ThreadPoolExecutor
    local = threading.local()
    ok = fail = 0
    novo = not os.path.exists(args.log)

    def mover(item):
        f, _, _, dest = item
        if not hasattr(local, "svc"):  # httplib2 não é thread-safe: um cliente por thread
            local.svc = build("drive", "v3", credentials=creds, cache_discovery=False)
        try:
            local.svc.files().update(
                fileId=f["id"], addParents=dest, removeParents=f["pai"],
                fields="id", supportsAllDrives=True).execute()
            return f["id"], f["pai"], dest, "ok"
        except Exception as e:  # noqa: BLE001
            return f["id"], f["pai"], dest, f"falha: {str(e)[:200]}"

    # log em append: execuções interrompidas podem ser retomadas sem perder o histórico
    with open(args.log, "a", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        if novo:
            w.writerow(["arquivo_id", "pai_original", "pai_novo", "status"])
        with ThreadPoolExecutor(max_workers=8) as ex:
            for row in ex.map(mover, a_mover):
                w.writerow(row)
                fh.flush()
                if row[3] == "ok":
                    ok += 1
                else:
                    fail += 1
    print(f"Movidos: {ok} | Falhas: {fail} | Log de reversão: {args.log}")


if __name__ == "__main__":
    main()
