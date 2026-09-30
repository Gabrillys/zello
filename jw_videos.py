#!/usr/bin/env python3
"""Agrupa vídeos baixados do jw.org numa pasta separada para revisão (nada é apagado).

Critério: vídeo cujo nome termina com o sufixo de resolução usado nos downloads do
jw.org, ex.: osg_T_033_r240P.mp4, S-123-23v_T_01_r720P.mp4 (regex _r\\d{3,4}P).
Nomes que só parecem do jw.org (JW_HINTS) são listados como "possíveis", sem mover.

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
JW_RE = re.compile(r"_r\d{3,4}P\.[A-Za-z0-9]+$")
JW_HINTS = re.compile(r"discurso especial|discours publics|watchtower|jw", re.I)
VIDEO_EXT = {"mp4", "m4v", "mov", "avi", "mkv", "wmv", "3gp", "mpg", "mpeg"}


def is_video(f):
    ext = f["name"].rsplit(".", 1)[-1].lower() if "." in f["name"] else ""
    return f["mimeType"].startswith("video/") or ext in VIDEO_EXT


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
    videos = [f for f in files if is_video(f)]
    sure = [f for f in videos if JW_RE.search(f["name"])]
    maybe = [f for f in videos if f not in sure and JW_HINTS.search(f["name"])]

    for titulo, lista in (("JW.org (serão movidos)", sure), ("Possíveis (NÃO movidos)", maybe)):
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
