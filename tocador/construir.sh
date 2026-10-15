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
ORT=1.17.3        # ONNX Runtime (Whisper). A 1.17 é a última em Java 8, que o dx converte.
OKHTTP=3.12.13    # WebSocket da voz neural (versão compatível com Java 7/8)
OKIO=1.17.6
ASM=9.7
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
baixar "$MAVEN/com/microsoft/onnxruntime/onnxruntime-android/$ORT/onnxruntime-android-$ORT.aar" "$CACHE/ort-$ORT.aar"
baixar "$MAVEN/com/squareup/okhttp3/okhttp/$OKHTTP/okhttp-$OKHTTP.jar" "$CACHE/okhttp-$OKHTTP.jar"
baixar "$MAVEN/com/squareup/okio/okio/$OKIO/okio-$OKIO.jar" "$CACHE/okio-$OKIO.jar"
baixar "$MAVEN/org/ow2/asm/asm/$ASM/asm-$ASM.jar" "$CACHE/asm-$ASM.jar"

rm -rf "$OBRA" && mkdir -p "$OBRA/aar" "$OBRA/classes" "$OBRA/ort" "$OBRA/ort-classes" "$OBRA/nativas/jni" "$OBRA/ferr"
unzip -q -o "$CACHE/libvlc-$LIBVLC.aar" -d "$OBRA/aar"

echo "preparando o ONNX Runtime…"
unzip -q -o "$CACHE/ort-$ORT.aar" -d "$OBRA/ort"
unzip -q -o "$OBRA/ort/classes.jar" -d "$OBRA/ort-classes"
# Opções de GPU de computador (CUDA/TensorRT) não existem no celular e usam recursos de Java novo demais para o dx.
rm -f "$OBRA"/ort-classes/ai/onnxruntime/providers/{OrtCUDAProviderOptions,OrtTensorRTProviderOptions,StringConfigProviderOptions}*.class
rm -rf "$OBRA/ort-classes/META-INF"
javac --release 8 -nowarn -Xlint:-options -cp "$CACHE/asm-$ASM.jar" -d "$OBRA/ferr" "$AQUI/ferramentas/Podar.java"
java -cp "$OBRA/ferr:$CACHE/asm-$ASM.jar" Podar "$OBRA/ort-classes/ai/onnxruntime/TensorInfo.class" toString 'lambda$toString$0'
# O Whisper só roda em 64 bits (arm64): em 32 bits o app funciona, mas sem o Whisper (e fica menor).
cp -r "$OBRA/ort/jni/arm64-v8a" "$OBRA/nativas/jni/"

echo "compilando Java…"
javac --release 8 -nowarn -Xlint:-options -encoding UTF-8 \
  -cp "$CACHE/android-all.jar:$OBRA/aar/classes.jar:$OBRA/ort-classes:$CACHE/okhttp-$OKHTTP.jar:$CACHE/okio-$OKIO.jar" \
  -d "$OBRA/classes" $(find "$AQUI/src" -name '*.java')

echo "convertendo para Dalvik (dx)…"
java -Xmx3g -cp "$CACHE/dx.jar" com.android.dx.command.Main --dex --min-sdk-version=24 --output="$OBRA/classes.dex" \
  "$OBRA/classes" "$OBRA/aar/classes.jar" "$OBRA/ort-classes" "$CACHE/okhttp-$OKHTTP.jar" "$CACHE/okio-$OKIO.jar"

[ -s "$AQUI/res/icone.png" ] || python3 "$AQUI/ferramentas/icone.py"

echo "empacotando e assinando…"
node "$AQUI/ferramentas/empacotar.mjs" "$OBRA/classes.dex" "$OBRA/aar" "$AQUI/dist/Tocador.apk" "$OBRA/nativas"
