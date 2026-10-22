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

## Versão 3.0: falta testar no celular

Testado aqui no computador:
- leitor de listas (M3U de IPTV com vírgula no nome, grupos e logotipos, PLS, lista simples, endereços relativos, transmissão × lista, estilos, busca sem acento, grupos, salvar e recarregar);
- legenda .srt (horários e quebra de linha) e conversão 48 kHz → 16 kHz;
- script de bloqueio de anúncios no Chromium: lista de anúncios apagada (JSON.parse, fetch e ytInitialPlayerResponse), anúncio mudo, adiantado e "Pular" tocado, som de volta depois, banners escondidos;
- telas no Chromium em retrato, paisagem e barra compacta, sem nenhum elemento passando da largura da tela.

Falta no celular:
- vídeo em pé sem esticar (encaixe pelo tamanho que o VLC informa) e arquivos do aparelho (content://) no VLC;
- legendador: decodificação com o MediaCodec e desempenho do Whisper;
- bloqueio de anúncios no YouTube real (script de início do WebView) e o contador;
- recorte da câmera na horizontal (agora o app nunca desenha embaixo dele fora da tela cheia).

## Versão 3.1: downloads com o yt-dlp, falta testar no celular

- Motor: youtubedl-android 0.18.1 (Maven Central: Python 3.12, QuickJS, FFmpeg, só arm64) + yt-dlp 2026.8.19 e yt-dlp-ejs 0.8.0 (wheels do PyPI, usados direto pelo PYTHONPATH). Atualização pelo app via PyPI.
- Testado aqui: extração do Python com os atalhos (symlinks) lida do diretório central do zip (942 arquivos idênticos ao original), comparação de versões, tela.
- Não testado aqui: a política do ambiente não deixou rodar o yt-dlp baixado, e o YouTube é bloqueado nesta rede. Falta ver no celular: o processo Python rodando, o download de uma playlist, a gravação em Música/Tocador e a lista na Biblioteca.
- O APK passou de 45 para 96 MB (perto do limite de 100 MB por arquivo do GitHub).

## Versão 3.2: animação da rádio, falta testar no celular

- O libVLC do app vem sem projectM e sem goom (compilado com --disable-projectm/--disable-goom). Por isso a animação é nossa: quatro efeitos em canvas no painel, alimentados pelo Visualizer do Android na sessão de áudio do VLC (--aout=android_audiotrack --audiotrack-session-id). Precisa da permissão de áudio; sem ela, modo ambiente.
- Rodapé: metadados ICY do VLC (Title, Genre, Description, NowPlaying, ArtworkURL), o nome do item da lista M3U e o Radio Browser (byurl e byname).
- Testado aqui: os quatro efeitos e a tela cheia no Chromium, com som simulado e no modo ambiente.
- Falta no celular: o Visualizer na sessão do VLC (e se a troca de saída de som para AudioTrack não mudou nada no som), os metadados reais das rádios e o Radio Browser.

## Versão 3.9: falta testar no celular

- Animação só com `tocando` (tolera 2,5 s de "carregando"); fora disso o palco some e o Visualizer desliga.
- Instagram sem conta: `Baixador.paraCache` usa o yt-dlp para buscar o vídeo (melhor até 1080p, áudio junto) para `cache/tocar/<hash>.mp4` e o VLC toca o arquivo. Cache de até ~1,5 GB. Outras redes passam pelo mesmo caminho com "Abrir como: Player VLC". Se falhar, abre o site.
- Não testado aqui (YouTube/Instagram bloqueados na rede do ambiente e o yt-dlp não pode rodar aqui). Instagram sem conta pode limitar ou recusar alguns vídeos.

## Versão 4.0: letra sincronizada, falta testar no celular

- `Letras.java` busca no LRCLIB (`/api/search`), escolhe a versão de duração mais próxima e converte o LRC; `letreiro.js` desenha. Só com o VLC tocando áudio sem vídeo, de 45 s a 20 min.
- O lrclib.net está bloqueado na rede do ambiente: a busca real não foi testada, só a tela (com letra de exemplo, em pé e deitado, nas duas vistas).
- Gravações com introdução diferente da versão do catálogo podem sair deslocadas; para isso existe o ajuste manual por música.

## Versão 4.1: letras dos Beatles e afins

- Títulos como "Here Comes The Sun - Remastered 2009", "01 - Come Together" e "Hey Jude - 2015 Mix" eram procurados errado (o sufixo virava a música, o número virava o artista). Corrigido e testado aqui com um servidor falso.
- A busca agora tenta três formas no LRCLIB e cai no NetEase Cloud Music (API pública não oficial, a mesma usada por players de código aberto). Nenhum dos dois pôde ser testado de verdade: a rede do ambiente bloqueia ambos.

## Versão 4.2: mais letras (10% era pouco)

- Fontes novas: Musixmatch (API do app de computador, com ficha "usertoken"), Kugou (busca + download em base64). Todas testadas só com servidor falso; a rede do ambiente bloqueia os quatro catálogos. O Musixmatch às vezes nega a ficha (captcha/limite); aí o app segue para os outros.
- Letra guardada em `files/letras/<hash>.json`; `.lrc` ao lado só para caminhos file:// (content:// não).
- Letra pelo Whisper: usa o Legendador (aparece também na gaveta de legendas). Qualidade depende do modelo e da mistura voz/instrumento; o modelo tiny erra bastante em música.

## Versão 4.6: downloads com a tela apagada, falta testar no celular

- **Problema:** o download rodava numa thread comum do app (com o Python como processo filho) e nada o mantinha vivo. Sem música tocando, o serviço em primeiro plano não existia; com a tela apagada o Android suspendia CPU e rede e podia matar o processo.
- **Feito:** `ServicoDownload` (serviço em primeiro plano, tipo `dataSync`, permissão `FOREGROUND_SERVICE_DATA_SYNC` por causa do Android 14). Sobe em `Baixador.baixar`, segura um wake lock parcial (renovado a cada segundo, prazo de 10 min, para um serviço travado não prender o aparelho) e um Wi-Fi lock, e mostra notificação com andamento e botão **Cancelar**. Só encerra depois de guardar na Biblioteca; deixa uma notificação final com o resultado. É separado do serviço de reprodução: parar a música não derruba o download.
- **Testado aqui:** compila; manifesto binário decodificado do APK (permissão e serviço com tipo `0x1`); conta do andamento da playlist; tela da aba Baixar. **Não testado:** o serviço em si (não há Android aqui).
- **Testar no celular:** (1) baixar uma playlist de ~10 músicas, apagar a tela e esperar o tempo ocioso: ao ligar, a notificação deve mostrar "de 10" avançando ou o aviso final; (2) tocar **Cancelar** na notificação com a tela apagada; (3) baixar com música tocando e parar a música: o download deve seguir; (4) Android 13+: negar a permissão de notificações e ver se o download ainda segue (deve seguir, só sem a notificação).
- **Se ainda parar:** mandar o **Copiar diagnóstico** (a linha `Download: o serviço em primeiro plano não subiu` indica recusa do Android) e o modelo do celular. Fabricantes como Xiaomi e Samsung podem exigir "sem restrição" de bateria; se for preciso, o app pode pedir essa isenção.
- **Fora desta versão (mesmo problema, ainda sem proteção):** o download do modelo do Whisper, o **Gerar legenda** e o preparo da dublagem também podem parar com a tela apagada. O `ServicoDownload` pode ser reaproveitado para eles.

## Versão 4.5: legendas voltam a funcionar (erro do 4.4) e letras com diagnóstico, falta testar no celular

- **Corrigido (causa confirmada):** no 4.4, ao dividir a gaveta "Áudio e legendas", o código da gaveta Legendas passou a mexer num elemento (`lgDubBox`) que agora só existe na gaveta Dublar. Dava erro de script ao abrir **Legendas**, e isso parava a montagem da gaveta: a lista de legendas ficava vazia, o botão **Baixar legenda como texto** ficava sem função e o **Gerar legenda** não mostrava andamento nem o botão de baixar o resultado. Reproduzido no Chromium; `ferramentas/fluxo.mjs` agora abre a gaveta Legendas (arquivo no VLC e YouTube), confere a lista e o botão de baixar, e **falha** se houver qualquer erro de script. Com o painel do 4.4 ele falha; com o 4.5, passa.
- **Letras só em algumas músicas (causa não confirmada):** não dá para chamar os catálogos reais daqui (rede bloqueada). Mudanças feitas e testadas com servidor falso: (1) o título "Música - Artista" (ordem trocada) agora acha a letra; antes era recusado; (2) cada busca grava no diagnóstico uma linha com o resultado de cada catálogo (LRCLIB com quantos resultados vieram e a melhor nota; os outros com "nada" ou o erro).
- **O que o usuário precisa mandar:** tocar umas 5 músicas que ficaram sem letra e enviar o texto de **Copiar diagnóstico**. As linhas `Letras "Artista — Música" …` dizem qual catálogo falhou e por quê. Com isso dá para saber se o problema é o catálogo (bloqueio, ficha do Musixmatch negada, endereço mudou) ou o casamento do nome.
- Falta no celular: abrir **Legendas** num vídeo do YouTube e baixar o .txt; **Gerar legenda** num arquivo do aparelho até o botão de baixar aparecer.

## Versão 4.4: interface nova (abas, player em tela inteira), falta testar no celular

- Testada só no Chromium com dados simulados (`ferramentas/banca.mjs` e `ferramentas/fluxo.mjs`): 24 cenas cabem na tela, 12 passos sem erro. No celular, conferir principalmente: o "minimizar" no modo site (recolhe o painel), o mini player, o cartão de dublagem no YouTube e a área segura das bordas no Android 16.
- Relatório da bancada em `BANCADA.md`.
