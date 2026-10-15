#!/usr/bin/env python3
"""Verifica a saúde do PC: CPU, memória, disco, processos e programas de inicialização.

Somente leitura: não altera nada no sistema.
Uso: python saude_pc.py        (Windows: py saude_pc.py)
Opcional, para resultados mais completos: pip install psutil
"""
import os
import platform
import shutil
import subprocess
import sys
import time

try:
    import psutil
except ImportError:
    psutil = None

SISTEMA = platform.system()  # Windows, Linux, Darwin
alertas = []


def titulo(texto):
    print(f"\n=== {texto} ===")


def rodar(cmd):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=30, shell=isinstance(cmd, str))
        return r.stdout.strip()
    except Exception:
        return ""


def gb(n):
    return f"{n / 1024**3:.1f} GB"


def sistema():
    titulo("Sistema")
    print(f"SO: {platform.platform()}")
    print(f"CPU: {platform.processor() or platform.machine()} | núcleos lógicos: {os.cpu_count()}")
    if psutil:
        up = time.time() - psutil.boot_time()
        dias = up / 86400
        print(f"Ligado há: {dias:.1f} dias")
        if dias > 14:
            alertas.append(f"O PC está ligado há {dias:.0f} dias sem reiniciar. Reinicie.")


def cpu_memoria():
    titulo("CPU e memória")
    if not psutil:
        print("Instale o psutil (pip install psutil) para ver uso de CPU e memória.")
        return
    uso = psutil.cpu_percent(interval=2)
    print(f"Uso de CPU: {uso}%")
    if uso > 80:
        alertas.append(f"CPU com uso alto ({uso}%) em repouso.")
    m = psutil.virtual_memory()
    print(f"RAM: {gb(m.used)} usados de {gb(m.total)} ({m.percent}%)")
    if m.percent > 85:
        alertas.append(f"Memória RAM quase cheia ({m.percent}%). Feche programas ou adicione RAM.")
    s = psutil.swap_memory()
    print(f"Swap/arquivo de paginação: {s.percent}% usado")
    if s.percent > 60:
        alertas.append("Uso alto de swap: o PC está sem RAM suficiente e usa o disco, o que deixa tudo lento.")
    try:
        temps = psutil.sensors_temperatures()
        for nome, lista in temps.items():
            for t in lista[:1]:
                print(f"Temperatura {nome}: {t.current}°C")
                if t.current > 85:
                    alertas.append(f"Temperatura alta em {nome}: {t.current}°C. Limpe os coolers.")
    except Exception:
        pass


def disco():
    titulo("Disco")
    raiz = os.path.abspath(os.sep)
    total, usado, livre = shutil.disk_usage(raiz)
    pct_livre = livre / total * 100
    print(f"{raiz}: {gb(usado)} usados, {gb(livre)} livres de {gb(total)} ({pct_livre:.0f}% livre)")
    if pct_livre < 15:
        alertas.append(f"Pouco espaço livre no disco ({pct_livre:.0f}%). Abaixo de 15% o sistema desacelera.")
    if SISTEMA == "Windows":
        tipo = rodar('powershell -NoProfile "Get-PhysicalDisk | Select -Expand MediaType"')
        if tipo:
            print(f"Tipo de disco: {tipo.replace(chr(10), ', ')}")
            if "HDD" in tipo:
                alertas.append("Você usa HDD. Trocar por SSD é a melhoria de velocidade mais perceptível.")
    elif SISTEMA == "Linux":
        for dev in os.listdir("/sys/block"):
            if dev.startswith(("loop", "ram", "zram", "dm-", "md", "sr", "nbd")):
                continue
            try:
                with open(f"/sys/block/{dev}/queue/rotational") as f:
                    if f.read().strip() == "1":
                        alertas.append(f"{dev} é um disco rotacional (HDD). Um SSD deixaria o PC bem mais rápido.")
            except OSError:
                pass


def processos():
    titulo("Processos que mais consomem")
    if not psutil:
        if SISTEMA == "Windows":
            print(rodar("tasklist"))
        else:
            print(rodar("ps aux --sort=-%cpu | head -11" if SISTEMA == "Linux" else "ps aux | sort -nrk 3 | head -11"))
        return
    procs = list(psutil.process_iter(["name", "memory_info"]))
    for p in procs:
        try:
            p.cpu_percent(None)
        except Exception:
            pass
    time.sleep(2)
    dados = []
    for p in procs:
        try:
            dados.append((p.cpu_percent(None), p.info["memory_info"].rss, p.info["name"], p.pid))
        except Exception:
            continue
    print("Top 8 por CPU:")
    for cpu, mem, nome, pid in sorted(dados, reverse=True)[:8]:
        print(f"  {cpu:5.1f}%  {mem / 1024**2:8.0f} MB  {nome} (pid {pid})")
    print("Top 8 por memória:")
    for cpu, mem, nome, pid in sorted(dados, key=lambda d: d[1], reverse=True)[:8]:
        print(f"  {mem / 1024**2:8.0f} MB  {cpu:5.1f}%  {nome} (pid {pid})")
    print(f"Total de processos: {len(dados)}")


def inicializacao():
    titulo("Programas que iniciam com o sistema")
    if SISTEMA == "Windows":
        out = rodar('powershell -NoProfile "Get-CimInstance Win32_StartupCommand | Select Name,Command | Format-Table -Auto"')
        print(out or "Não foi possível listar.")
        qtd = max(len(out.splitlines()) - 3, 0)
        if qtd > 10:
            alertas.append(f"Cerca de {qtd} itens de inicialização. Desative os desnecessários no Gerenciador de Tarefas > Inicializar.")
    elif SISTEMA == "Linux":
        print(rodar("systemctl list-unit-files --state=enabled --type=service --no-legend | head -40") or "Indisponível.")
        autostart = os.path.expanduser("~/.config/autostart")
        if os.path.isdir(autostart):
            print("Autostart do usuário:", ", ".join(os.listdir(autostart)) or "(vazio)")
    elif SISTEMA == "Darwin":
        print(rodar("ls ~/Library/LaunchAgents /Library/LaunchAgents /Library/LaunchDaemons 2>/dev/null"))


def resumo():
    titulo("RESUMO")
    if not alertas:
        print("Nada crítico encontrado nas verificações básicas.")
    for a in alertas:
        print(f"- {a}")
    print("\nDica: rode o script de novo quando a lentidão estiver acontecendo, para flagrar o culpado.")


if __name__ == "__main__":
    print("Verificando a saúde do PC (leva alguns segundos)...")
    if not psutil:
        print("Aviso: psutil não instalado; o relatório será parcial. Instale com: pip install psutil")
    for etapa in (sistema, cpu_memoria, disco, processos, inicializacao, resumo):
        try:
            etapa()
        except Exception as e:
            print(f"[erro em {etapa.__name__}: {e}]", file=sys.stderr)
