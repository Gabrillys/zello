# Pendências do Tocador

## Aguardando informação do usuário

- **Qualidade travada (YouTube e jw.org)** — desde a versão 2.2.
  O usuário vai enviar, para um vídeo do YouTube e um do jw.org:
  1. o que aparece na gaveta **Qualidade** (aviso do tipo "o app pediu X, mas o YouTube manteve Y", ou o motivo da falha na consulta ao jw.org);
  2. o texto de **Dicas → Copiar diagnóstico**, logo depois de tentar.
  Linhas que importam no diagnóstico: `Qualidade YouTube…`, `Versão para computador… Client Hints…` e `jw.org…`.
  Com isso, decidir: se o comando de qualidade do YouTube é ignorado, se os Client Hints foram aceitos pelo WebView do aparelho, e se a API do jw.org respondeu.

## Próxima etapa (decidida) — aguardando acesso à rede do ambiente

- **Dublagem com Whisper e voz humanizada** — o usuário não gostou da dublagem da 2.4 (voz da legenda pelo Android): ficou entrecortada e sintética.
  Caminho escolhido (celular Samsung Galaxy S22 Ultra, Snapdragon 8 Gen 1; o computador dele é lento e sem placa de vídeo):
  1. Whisper no celular via ONNX Runtime (onnxruntime-android, do Maven Central), modelo *base* ou *small* baixado no primeiro uso;
  2. áudio obtido gravando a reprodução (AudioPlaybackCapture/MediaProjection), já que o YouTube bloqueia o download;
  3. tradução do texto para português (online leve, ou modelo aberto offline);
  4. voz neural natural (vozes da Microsoft usadas pelo projeto edge-tts; a do Google como alternativa), com frases inteiras e sem cortes;
  5. modos "Preparar dublagem" (sincronia perfeita) e "Ao vivo" (alguns segundos de atraso).
  **Bloqueio:** liberar na rede do ambiente `huggingface.co`, `cdn-lfs.huggingface.co`, `github.com`, `objects.githubusercontent.com` e `speech.platform.bing.com`, para testar os modelos e a voz de verdade antes de entregar.
