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
YTDL_ANDROID=0.18.1   # Python 3.12, QuickJS e FFmpeg compilados para Android (projeto youtubedl-android)
YTDLP=2026.8.19       # yt-dlp (o app pode atualizar sozinho depois)
YTDLP_EJS=0.8.0       # resolvedor de JavaScript do YouTube usado pelo yt-dlp
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
baixar "$MAVEN/io/github/junkfood02/youtubedl-android/library/$YTDL_ANDROID/library-$YTDL_ANDROID.aar" "$CACHE/ytdl-library-$YTDL_ANDROID.aar"
baixar "$MAVEN/io/github/junkfood02/youtubedl-android/ffmpeg/$YTDL_ANDROID/ffmpeg-$YTDL_ANDROID.aar" "$CACHE/ytdl-ffmpeg-$YTDL_ANDROID.aar"
roda() { # pacote versão destino — wheel do PyPI
  [ -s "$3" ] && return 0
  local u
  u=$(curl -fsSL "https://pypi.org/pypi/$1/$2/json" | python3 -c "import json,sys;print([x['url'] for x in json.load(sys.stdin)['urls'] if x['filename'].endswith('.whl')][0])")
  baixar "$u" "$3"
}
roda yt-dlp "$YTDLP" "$CACHE/yt_dlp-$YTDLP.whl"
roda yt-dlp-ejs "$YTDLP_EJS" "$CACHE/yt_dlp_ejs-$YTDLP_EJS.whl"

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

echo "preparando o motor de download (yt-dlp)…"
# Também só arm64: Python, QuickJS e FFmpeg (executáveis com nome .so, como o Android exige).
mkdir -p "$OBRA/ytdl" "$OBRA/nativas/assets/ytdlp"
unzip -q -o "$CACHE/ytdl-library-$YTDL_ANDROID.aar" 'jni/arm64-v8a/*' -d "$OBRA/ytdl"
unzip -q -o "$CACHE/ytdl-ffmpeg-$YTDL_ANDROID.aar" 'jni/arm64-v8a/*' -d "$OBRA/ytdl"
cp "$OBRA"/ytdl/jni/arm64-v8a/*.so "$OBRA/nativas/jni/arm64-v8a/"
cp "$CACHE/yt_dlp-$YTDLP.whl" "$OBRA/nativas/assets/ytdlp/yt_dlp.whl"
cp "$CACHE/yt_dlp_ejs-$YTDLP_EJS.whl" "$OBRA/nativas/assets/ytdlp/yt_dlp_ejs.whl"
printf %s "$YTDLP" > "$OBRA/nativas/assets/ytdlp/yt_dlp.whl.versao"
printf %s "$YTDLP_EJS" > "$OBRA/nativas/assets/ytdlp/yt_dlp_ejs.whl.versao"

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
