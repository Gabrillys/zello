# Pendências do Tocador

## Aguardando informação do usuário

- **Qualidade travada (YouTube e jw.org)** — desde a versão 2.2.
  O usuário vai enviar, para um vídeo do YouTube e um do jw.org:
  1. o que aparece na gaveta **Qualidade** (aviso do tipo "o app pediu X, mas o YouTube manteve Y", ou o motivo da falha na consulta ao jw.org);
  2. o texto de **Dicas → Copiar diagnóstico**, logo depois de tentar.
  Linhas que importam no diagnóstico: `Qualidade YouTube…`, `Versão para computador… Client Hints…` e `jw.org…`.
  Com isso, decidir: se o comando de qualidade do YouTube é ignorado, se os Client Hints foram aceitos pelo WebView do aparelho, e se a API do jw.org respondeu.

## Dublagem com o Whisper (versão 2.5): falta testar no celular

Feito e testado aqui no computador:
- Whisper em Java + ONNX Runtime 1.17.3: espectrograma igual ao do Whisper oficial e vocabulário igual ao do tiktoken. Transcrição com o modelo **tiny real** (o mesmo formato do base/small) numa gravação de fala em inglês: texto e horário corretos.
- Recorte do áudio em janelas, com o horário do vídeo certo em 1x e 2x, depois de avanços no vídeo e com o som baixo. Sem frases inventadas no silêncio.
- Token de acesso da voz neural idêntico ao do edge-tts. Protocolo testado contra um servidor simulado, inclusive a correção do relógio.
- Tradutor testado contra um servidor simulado, inclusive quando o serviço junta linhas.
- Tela da dublagem testada no Chromium.

Não deu para testar aqui, porque a rede do ambiente bloqueia huggingface.co, speech.platform.bing.com e o Google Tradutor, e não há celular Android:
1. download dos modelos base/small (Xenova/whisper-base e Xenova/whisper-small no huggingface.co, com o hf-mirror.com como reserva);
2. gravação do som do vídeo (captura da reprodução + autorização de gravar a tela) no Galaxy S22;
3. voz neural de verdade e tradução de verdade;
4. velocidade do Whisper no Snapdragon 8 Gen 1.
Se algo falhar, o usuário vai mandar o texto de **Copiar diagnóstico**. As linhas que começam com `Whisper`, `Voz neural`, `Tradução`, `Gravação do áudio` e `Dublagem` dizem o que aconteceu.
