#!/usr/bin/env python3
"""Agrupa arquivos do jw.org (qualquer tipo) numa pasta separada para revisão (nada é apagado).

Critério: nome com marca do jw.org (ver JW_RES / JW_PUB / JW_TOKEN / EXTRA abaixo).

Padrão: só lista. Com --apply: cria a pasta e move (addParents/removeParents),
gravando jw_videos_log.csv com o pai original de cada arquivo (reversível).
"""
import argparse
import csv
import os
import re

from google.oauth2 import service_account
from googleapiclient.discovery import build

from dedup import FOLDER_MIME, SCOPES, walk, write_credentials

DEST = "Vídeos JW.org (para revisar)"
# Marcas do jw.org: sufixo de resolução (_r480P), código de publicação com idioma
# (S-34_T_074, w_T_202107, 502017217_T_cnt_1), tokens jw/jwb/jwpub/jwlibrary, jw.org.
JW_RES = re.compile(r"_r\d{3,4}P\b", re.I)
JW_PUB = re.compile(r"(^|[\s_-])[A-Za-z0-9-]+[_ ]T[_ .](cnt|\d)|^[A-Za-z0-9-]+_T\.[a-z]+$")
JW_TOKEN = re.compile(r"(?<![A-Za-z])jw(b|pub|library|\.org)?(?![A-Za-z])", re.I)
EXTRA = ("Discurso Especial 2023",)
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--log", default="jw_videos_log.csv")
    args = ap.parse_args()

    root = os.environ["DRIVE_FOLDER_ID"]
    creds = service_account.Credentials.from_service_account_file(
        write_credentials(), scopes=SCOPES)
    svc = build("drive", "v3", credentials=creds, cache_discovery=False)

    files, _ = walk(svc, root)
    def marca(f):
        n = f["name"]
        return bool(JW_RES.search(n) or JW_PUB.search(n) or JW_TOKEN.search(n)
                    or any(x in n for x in EXTRA))

    sure = [f for f in files if marca(f) and not f["path"].startswith(f"/{DEST}/")]
    maybe = []

    for titulo, lista in (("JW.org (serão movidos)", sure),):
        print(f"\n== {titulo}: {len(lista)} ==")
        for f in lista:
            print(f"  {int(f.get('size', 0))/1024**2:8.1f} MB  {f['path']}")
    print(f"\nTotal em MB (certos): {sum(int(f.get('size', 0)) for f in sure)/1024**2:.1f}")

    if not args.apply or not sure:
        print("\nNada foi movido (use --apply).")
        return

    q = f"'{root}' in parents and name = '{DEST}' and mimeType = '{FOLDER_MIME}' and trashed = false"
    res = svc.files().list(q=q, fields="files(id)", supportsAllDrives=True,
                           includeItemsFromAllDrives=True).execute()["files"]
    dest = res[0]["id"] if res else svc.files().create(
        body={"name": DEST, "mimeType": FOLDER_MIME, "parents": [root]},
        fields="id", supportsAllDrives=True).execute()["id"]

    ok = fail = 0
    with open(args.log, "a", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["arquivo_id", "nome", "pai_original", "status"])
        for f in sure:
            pais = ",".join(f.get("parents", []))
            if pais == dest:
                continue
            try:
                svc.files().update(fileId=f["id"], addParents=dest, removeParents=pais,
                                   fields="id", supportsAllDrives=True).execute()
                ok += 1
                w.writerow([f["id"], f["name"], pais, "ok"])
            except Exception as e:  # noqa: BLE001
                fail += 1
                w.writerow([f["id"], f["name"], pais, f"falha: {str(e)[:150]}"])
    print(f"Pasta {dest} | Movidos: {ok} | Falhas: {fail}")


if __name__ == "__main__":
    main()
