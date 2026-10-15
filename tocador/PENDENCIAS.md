# Pendências do Tocador

## Aguardando informação do usuário

- **Qualidade travada (YouTube e jw.org)** — desde a versão 2.2.
  O usuário vai enviar, para um vídeo do YouTube e um do jw.org:
  1. o que aparece na gaveta **Qualidade** (aviso do tipo "o app pediu X, mas o YouTube manteve Y", ou o motivo da falha na consulta ao jw.org);
  2. o texto de **Dicas → Copiar diagnóstico**, logo depois de tentar.
  Linhas que importam no diagnóstico: `Qualidade YouTube…`, `Versão para computador… Client Hints…` e `jw.org…`.
  Com isso, decidir: se o comando de qualidade do YouTube é ignorado, se os Client Hints foram aceitos pelo WebView do aparelho, e se a API do jw.org respondeu.

## Dublagem com o Whisper (versões 2.5 e 2.6): falta testar no celular

Feito e testado aqui no computador:
- Whisper em Java + ONNX Runtime 1.17.3: espectrograma igual ao do Whisper oficial e vocabulário igual ao do tiktoken. Transcrição com o modelo **tiny real** (o mesmo formato do base/small) numa gravação de fala em inglês: texto e horário corretos.
- Recorte do áudio em janelas, com o horário do vídeo certo em 1x e 2x, depois de avanços no vídeo e com o som baixo. Sem frases inventadas no silêncio.
- Token de acesso da voz neural idêntico ao do edge-tts. Protocolo testado contra um servidor simulado, inclusive a correção do relógio.
- Tradutor testado contra um servidor simulado, inclusive quando o serviço junta linhas.
- Tela da dublagem testada no Chromium.
- 2.6: o som do vídeo é lido dentro da página (Web Audio → 16 kHz → ponte `TocadorAudio`), sem autorização de gravar a tela. Testado no Chromium: pedaços de 0,5 s com o tempo certo do vídeo, transcritos corretamente pelo Whisper. Vídeo de outro domínio sem permissão é recusado sem mexer no som.
- 2.6: corrigido o sumiço da seção de dublagem fora do YouTube (a ponte chamava `getUrl()` fora da linha da tela e dava erro).

Não deu para testar aqui, porque a rede do ambiente bloqueia huggingface.co, speech.platform.bing.com e o Google Tradutor, e não há celular Android:
1. download dos modelos base/small (Xenova/whisper-base e Xenova/whisper-small no huggingface.co, com o hf-mirror.com como reserva);
2. a escuta do som pelo Web Audio no WebView do Galaxy S22 (inclusive com a tela apagada);
3. voz neural de verdade e tradução de verdade;
4. velocidade do Whisper no Snapdragon 8 Gen 1.
Se algo falhar, o usuário vai mandar o texto de **Copiar diagnóstico**. As linhas que começam com `Whisper`, `Voz neural`, `Tradução`, `Ouvir o vídeo` e `Dublagem` dizem o que aconteceu.
