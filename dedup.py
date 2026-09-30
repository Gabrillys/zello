#!/usr/bin/env python3
"""Simulação de deduplicação no Google Drive (SOMENTE LEITURA).

Lê GDRIVE_SA_KEY_B64 (service account em base64) e DRIVE_FOLDER_ID do ambiente,
lista recursivamente a pasta, agrupa por md5Checksum e gera dedup_report.csv.
Nada é apagado, movido ou modificado no Drive.

Uso: python dedup.py [--out dedup_report.csv]
"""
import argparse
import base64
import csv
import os
import re
import tempfile
from collections import defaultdict

from google.oauth2 import service_account
from googleapiclient.discovery import build

SCOPES = ["https://www.googleapis.com/auth/drive"]
FIELDS = "id,name,mimeType,md5Checksum,size,parents,createdTime,modifiedTime"
QUARANTINE = "Duplicatas-para-apagar"
FOLDER_MIME ="application/vnd.google-apps.folder"
SUFFIX_RE = re.compile(r"\s*\(\d+\)(?=\.[^.]*$|$)|\s*-?\s*(copy|copia|cópia)\b", re.I)


def write_credentials():
    tmpdir = tempfile.mkdtemp(prefix="gdrive_")
    path = os.path.join(tmpdir, "service_account.json")
    with open(path, "wb") as f:
        f.write(base64.b64decode(os.environ["GDRIVE_SA_KEY_B64"]))
    os.chmod(path, 0o600)
    return path


def list_children(svc, folder_id):
    token = None
    while True:
        resp = svc.files().list(
            q=f"'{folder_id}' in parents and trashed = false",
            fields=f"nextPageToken, files({FIELDS})",
            pageSize=1000,
            pageToken=token,
            supportsAllDrives=True,
            includeItemsFromAllDrives=True,
        ).execute()
        yield from resp.get("files", [])
        token = resp.get("nextPageToken")
        if not token:
            break


def walk(svc, root_id):
    """Retorna (arquivos, pastas) com 'path' preenchido em cada arquivo."""
    files, folders = [], 0
    stack = [(root_id, "")]
    while stack:
        fid, path = stack.pop()
        for item in list_children(svc, fid):
            if item["mimeType"] == FOLDER_MIME:
                folders += 1
                stack.append((item["id"], f"{path}/{item['name']}"))
            else:
                item["path"] = f"{path}/{item['name']}"
                files.append(item)
    return files, folders


def sort_key(f):
    # mais antigo primeiro; desempate: sem sufixo "(1)"/"copy", depois nome mais curto
    return (f["createdTime"], bool(SUFFIX_RE.search(f["name"])), len(f["name"]), f["name"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="dedup_report.csv")
    ap.add_argument("--quarantine", action="store_true",
                    help="MOVE as duplicatas para a pasta 'Duplicatas-para-apagar' (reversível)")
    ap.add_argument("--delete", action="store_true",
                    help="APAGA DEFINITIVAMENTE (sem lixeira) as duplicatas marcadas 'apagar'")
    args = ap.parse_args()

    creds = service_account.Credentials.from_service_account_file(
        write_credentials(), scopes=SCOPES)
    svc = build("drive", "v3", credentials=creds, cache_discovery=False)

    files, n_folders = walk(svc, os.environ["DRIVE_FOLDER_ID"])
    candidates = [f for f in files if f.get("md5Checksum")]
    native = len(files) - len(candidates)

    groups = defaultdict(list)
    for f in candidates:
        groups[f["md5Checksum"]].append(f)
    dup_groups = [sorted(g, key=sort_key) for g in groups.values() if len(g) > 1]
    dup_groups.sort(key=lambda g: -int(g[0].get("size", 0)) * (len(g) - 1))

    to_delete = freed = 0
    with open(args.out, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["grupo_id", "arquivo_id", "nome", "caminho_completo_aproximado",
                    "tamanho", "é_original", "ação_proposta"])
        for gid, g in enumerate(dup_groups, 1):
            for i, f in enumerate(g):
                orig = i == 0
                size = int(f.get("size", 0))
                if not orig:
                    to_delete += 1
                    freed += size
                w.writerow([gid, f["id"], f["name"], f["path"], size,
                            "sim" if orig else "não", "manter" if orig else "apagar"])

    if args.quarantine:
        qid = svc.files().create(
            body={"name": QUARANTINE, "mimeType": FOLDER_MIME,
                  "parents": [os.environ["DRIVE_FOLDER_ID"]]},
            fields="id", supportsAllDrives=True).execute()["id"]
        ok = fail = 0
        with open(args.out.replace(".csv", "_quarentena_log.csv"), "w",
                  newline="", encoding="utf-8") as lf:
            lw = csv.writer(lf)
            lw.writerow(["arquivo_id", "nome", "pai_original", "status"])
            for g in dup_groups:
                for f in g[1:]:  # g[0] é o original: nunca movido
                    pais = ",".join(f.get("parents", []))
                    try:
                        svc.files().update(
                            fileId=f["id"], addParents=qid, removeParents=pais,
                            fields="id", supportsAllDrives=True).execute()
                        ok += 1
                        lw.writerow([f["id"], f["name"], pais, "ok"])
                    except Exception as e:  # noqa: BLE001
                        fail += 1
                        lw.writerow([f["id"], f["name"], pais, f"falha: {str(e)[:150]}"])
        print(f"Pasta de quarentena: {qid} | Movidos: {ok} | Falhas: {fail}")

    if args.delete:
        ok = fail = 0
        for g in dup_groups:
            for f in g[1:]:  # g[0] é o original: nunca apagado
                try:
                    svc.files().delete(fileId=f["id"], supportsAllDrives=True).execute()
                    ok += 1
                except Exception as e:  # noqa: BLE001
                    fail += 1
                    print(f"FALHA {f['id']} {f['path']}: {str(e)[:150]}")
        print(f"Apagados: {ok} | Falhas: {fail}")

    print(f"Arquivos totais (não-pasta):        {len(files)}")
    print(f"  com md5 (candidatos):             {len(candidates)}")
    print(f"  nativos Google (sem md5, pulados): {native}")
    print(f"Subpastas percorridas:              {n_folders}")
    print(f"Grupos de duplicata:                {len(dup_groups)}")
    print(f"Arquivos que seriam apagados:       {to_delete}")
    print(f"Espaço liberado: {freed/1024**2:.2f} MB ({freed/1024**3:.3f} GB)")
    print(f"Relatório: {args.out}")


if __name__ == "__main__":
    main()
