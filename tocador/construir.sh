#!/usr/bin/env bash
# Compila o Tocador e gera dist/Tocador.apk (sem Android SDK).
# Precisa de: Java 11+ (javac), Node 22+, Python 3 com Pillow, curl.
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
CACHE="${CACHE:-$AQUI/.cache}"
OBRA="$CACHE/obra"
LIBVLC=3.6.0
ANDROID=14-robolectric-10818077
DX=14.0.0_r21
MAVEN=https://repo1.maven.org/maven2

mkdir -p "$CACHE" "$AQUI/dist"

baixar() { # url destino
  [ -s "$2" ] && return 0
  echo "baixando $(basename "$2")…"
  for t in 1 2 3 4; do
    if curl -fsSL --max-time 600 -o "$2.tmp" "$1"; then mv "$2.tmp" "$2"; return 0; fi
    sleep $((t * 5))
  done
  echo "falhou: $1" >&2; exit 1
}

baixar "$MAVEN/org/videolan/android/libvlc-all/$LIBVLC/libvlc-all-$LIBVLC.aar" "$CACHE/libvlc-$LIBVLC.aar"
baixar "$MAVEN/org/robolectric/android-all/$ANDROID/android-all-$ANDROID.jar" "$CACHE/android-all.jar"
baixar "$MAVEN/com/jakewharton/android/repackaged/dalvik-dx/$DX/dalvik-dx-$DX.jar" "$CACHE/dx.jar"

rm -rf "$OBRA" && mkdir -p "$OBRA/aar" "$OBRA/classes"
unzip -q -o "$CACHE/libvlc-$LIBVLC.aar" -d "$OBRA/aar"

echo "compilando Java…"
javac --release 8 -nowarn -Xlint:-options -encoding UTF-8 \
  -cp "$CACHE/android-all.jar:$OBRA/aar/classes.jar" \
  -d "$OBRA/classes" $(find "$AQUI/src" -name '*.java')

echo "convertendo para Dalvik (dx)…"
java -cp "$CACHE/dx.jar" com.android.dx.command.Main --dex --output="$OBRA/classes.dex" "$OBRA/classes" "$OBRA/aar/classes.jar"

[ -s "$AQUI/res/icone.png" ] || python3 "$AQUI/ferramentas/icone.py"

echo "empacotando e assinando…"
node "$AQUI/ferramentas/empacotar.mjs" "$OBRA/classes.dex" "$OBRA/aar" "$AQUI/dist/Tocador.apk"
