# Pendências do Tocador

## Aguardando informação do usuário

- **Qualidade travada (YouTube e jw.org)** — desde a versão 2.2.
  O usuário vai enviar, para um vídeo do YouTube e um do jw.org:
  1. o que aparece na gaveta **Qualidade** (aviso do tipo "o app pediu X, mas o YouTube manteve Y", ou o motivo da falha na consulta ao jw.org);
  2. o texto de **Dicas → Copiar diagnóstico**, logo depois de tentar.
  Linhas que importam no diagnóstico: `Qualidade YouTube…`, `Versão para computador… Client Hints…` e `jw.org…`.
  Com isso, decidir: se o comando de qualidade do YouTube é ignorado, se os Client Hints foram aceitos pelo WebView do aparelho, e se a API do jw.org respondeu.

## Decisão pendente

- **Dublagem de vídeos sem legenda (Whisper) com voz natural** — pedido na versão 2.4.
  A versão 2.4 já dubla a partir da legenda (manual ou automática) traduzida pelo YouTube, com a voz do Android.
  Falta o usuário escolher o caminho para o Whisper e a voz humanizada:
  (A) tudo no celular (código aberto, offline); (B) serviços online; (C) o próprio computador dele como servidor (yt-dlp + faster-whisper + tradução + XTTS).
