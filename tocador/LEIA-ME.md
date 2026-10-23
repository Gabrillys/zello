# Tocador

Aplicativo Android para assistir e ouvir **vídeos do YouTube e de outros sites**, **rádios, TVs e câmeras de listas M3U**, e **vídeos e músicas do próprio aparelho**. Ele continua tocando **em segundo plano e com a tela apagada**, tem **tela cheia só com o vídeo**, **qualidade máxima**, **toque duplo para voltar e avançar**, **legendas** (com download em texto puro) e **equalizador**.

Arquivo para instalar: [`dist/Tocador.apk`](dist/Tocador.apk). Funciona em celulares arm64 e arm32 com Android 7 ou mais novo. As versões novas instalam por cima das antigas.

## Como usar

- **Pelo Compartilhar:** no app do YouTube (ou de outro site), toque em *Compartilhar* e escolha **Tocador**.
- **Colando o link:** abra o Tocador, toque em *Colar* e depois em *Tocar*. O bloco **YouTube** abre o site direto.
- **Navegação (versão 4.4):** quatro abas embaixo: **Início**, **Biblioteca**, **Baixar** e **Ajustes**. O que está tocando abre o **player em tela inteira**; a seta ⌄ no alto o minimiza e um **mini player** fica acima das abas (toque nele para voltar). Tudo cabe na tela do celular, em pé ou deitado.
- **Player:** os botões de baixo mudam conforme o que toca. Por exemplo, **Dublar** no YouTube, **Letra** nas músicas e **Tela cheia** nos vídeos. **Qualidade**, **Equalizador**, **Tocar outro link** e **Parar** ficam em **Mais** (⋯).
- Pode **apagar a tela** ou trocar de app: o som continua. A notificação, os fones e a tela de bloqueio controlam a reprodução.
- O botão **voltar** não fecha o app. Para encerrar, use **Mais → Parar**.

## Rádio com animação

- Enquanto uma rádio (ou música) toca no player VLC, a tela mostra uma **animação que dança com o som**, no estilo do antigo Windows Media Player. São quatro efeitos: **Alquimia**, **Barras e ondas**, **Batalha** e **Ondas**. Toque na animação (ou no botão de setas) para trocar; o botão de tela cheia mostra só a animação.
- **Rodapé da rádio**: nome em destaque e, em letras menores, a música tocando agora (rolando quando é comprida), etiquetas de estilo, cidade e país com a bandeira, idioma, qualidade do som (formato, kbps, kHz, estéreo/mono), site e descrição, além do logotipo. Os dados vêm da própria rádio (cabeçalhos "icy-" e metadados), do nome na lista e do **Radio Browser**, um catálogo aberto de rádios.
- **Playlists de áudio e músicas do aparelho** também têm a animação. O rodapé mostra título, artista e álbum, a capa, a playlist e a posição ("Faixa 7 de 42"), além da qualidade.
- Links de rádio sem extensão (`…/stream`, `…:8000/`) são reconhecidos e abrem direto no player VLC.
- A animação aparece só enquanto algo está tocando; em pausa ou parado, ela some e para de gastar bateria.
- Para a animação reagir à música, o Android exige a permissão de áudio (pedida uma vez). Sem ela, a animação segue num modo ambiente, sem reagir ao som. Com a tela apagada, a animação para e não gasta bateria.

## Letra sincronizada (músicas sem vídeo)

- Quando uma **música sem vídeo** toca no player VLC (arquivo do aparelho, playlist de áudio, link direto), o Tocador procura a letra com horários, nesta ordem:
  1. letra já achada ou criada antes (fica guardada no aparelho, funciona sem internet);
  2. arquivo **.lrc** com o mesmo nome da música, na mesma pasta;
  3. catálogos na internet: **LRCLIB**, **Musixmatch** (o maior, forte em música brasileira), **NetEase Cloud Music** e **Kugou**;
  4. se nenhum tiver, o **Whisper** ouve a música no próprio celular e **cria a letra** com os horários. Acontece sozinho quando o Whisper já está baixado (dá para desligar), ou pelo botão **Criar letra** no palco.
- Artista e música vêm dos metadados do arquivo ou do título; número da faixa ("01 - ") e marcas de versão ("- Remastered 2009", "- 2015 Mix", "(Live)") são ignorados. Títulos escritos como "Música - Artista" também são reconhecidos. Covers e gravações de duração muito diferente são descartados.
- Em **Dicas → Copiar diagnóstico**, cada música procurada deixa uma linha dizendo o que cada catálogo respondeu (achou, nada, ou o erro).
- Se não achar, a seção **Letra da música** mostra exatamente o que foi procurado.
- Cada linha aparece **meio segundo antes** de ser cantada, para dar tempo de ler, e um degradê dourado percorre a linha no ritmo da música.
- O botão **Letra / Animação**, no alto do palco, alterna entre as duas vistas: a **letra sozinha**, rolando em letras grandes como a letra do YouTube, ou a **animação sozinha**, sem letra. A escolha fica guardada. Na vista da letra a animação para de vez e não gasta bateria.
- Em **Áudio e legendas → Letra da música** dá para desligar a letra e fazer o **ajuste fino da sincronia**: uma barra de −1 s (letra mais tarde) a +1 s (letra mais cedo), em passos de 0,1 s, com botões −0,1 / +0,1 e "Zerar ajuste". O ajuste vale na hora e fica guardado para aquela música.
- Vídeos nunca recebem essa letra; ela é só para músicas sem imagem.

## Biblioteca: listas M3U, M3U8 e PLS

- Toque em **Importar lista** e cole o endereço da lista (ou escolha um arquivo do aparelho). Funciona com listas de IPTV (com logotipo e grupos), de rádios, de músicas e com listas simples de endereços.
- Escolha o **estilo**: Músicas, Rádios, TVs, Câmeras ou Outros (ou *Automático*, que adivinha pelo conteúdo). Em **Músicas**, as faixas tocam em sequência; nos outros estilos, um item de cada vez.
- Dentro da lista: **Tocar todos**, **Aleatório**, **busca** (sem se importar com acentos) e **grupos**.
- No player: **anterior/próximo**, **aleatório** (sorteia sem repetir faixa até passar por todas; o "anterior" volta para a faixa que tocou antes) e **repetir** (desligado → lista toda → só a faixa, com um "1" no ícone).
- **Apagar uma lista**: na lixeira ao lado dela na Biblioteca (ou dentro da lista). Nas listas de downloads, dá para apagar também os arquivos do aparelho.
- Em *Gerenciar lista*: renomear, trocar o estilo e **atualizar** (baixa de novo do mesmo endereço).
- Se o endereço for de uma transmissão (um único canal `.m3u8`), ele toca direto.

## Criar playlist com os links de um canal (versão 4.11)

- Na aba **Baixar**, cole o link de um **canal**, **playlist** ou **álbum** do YouTube ou do YouTube Music e toque em **Criar playlist (só os links, sem baixar)**.
- O app lê a lista com o yt-dlp (`--flat-playlist`, sem baixar nada). Num canal sem aba escolhida, ele lê a aba **Vídeos**. Abas, álbuns e playlists que aparecem dentro do link também são abertos.
- A playlist entra na **Biblioteca** como lista de **Músicas** e é salva como arquivo `.m3u` em **Download/Tocador**, no formato `#EXTM3U` / `#EXTINF:-1,Título` / link.
- Ao tocar, o app busca só o **áudio** de cada música e toca no player VLC, seguindo sozinho para a próxima. Música indisponível é pulada.

## Baixar músicas, vídeos e playlists

- Na tela inicial, toque em **Baixar músicas, vídeos e playlists**. No player, use **Baixar este vídeo ou playlist**.
- Cole o link de um vídeo, de uma **playlist** ou de um **canal**. Para todas as músicas de um cantor, use a playlist dele (por exemplo, o álbum ou a página "Músicas" no YouTube Music) ou o canal, e ligue **Playlist ou canal inteiro**.
- **Música (áudio)**: m4a na melhor qualidade, com artista e título, em **Música/Tocador/<nome da playlist>**.
- **Vídeo**: mp4 até 1080p (vídeo e áudio juntados no próprio celular), em **Filmes/Tocador**.
- O que for baixado vira uma lista na **Biblioteca**, pronta para tocar em sequência.
- **Com a tela apagada (versões 4.6 e 4.7):** os trabalhos longos continuam mesmo com a tela apagando por tempo ocioso: **download** de vídeos, músicas e playlists, **Gerar legenda**, **download do modelo do Whisper** e o **preparo da dublagem** com o Whisper. Enquanto rodam, o app mantém um serviço em primeiro plano, com CPU e Wi-Fi acordados, e mostra uma notificação com o andamento (em playlists, "de 20") e um botão **Cancelar** para cada trabalho. No fim de cada um, outra notificação diz o resultado.
- **Economia de bateria:** na primeira vez que um desses trabalhos roda com o app à frente, o Tocador abre a janela do Android que pergunta se ele pode ficar **sem restrição de bateria** (uma vez só). Em **Ajustes → Tocar e baixar com a tela apagada** dá para ver se já está liberado e pedir de novo. Em Xiaomi, Samsung e Motorola essa liberação costuma ser o que faltava.
- O motor é o **yt-dlp** (código aberto, o mesmo de sites como o Cobalt), rodando no celular com Python, QuickJS e FFmpeg do projeto youtubedl-android. Se o YouTube mudar algo e os downloads falharem, toque em *Motor de download → Atualizar o motor*: o app baixa o yt-dlp mais novo do PyPI.
- Só em celulares de 64 bits. Na primeira vez, o motor é instalado (leva alguns segundos).
- Baixar vídeos do YouTube contraria os termos de uso do YouTube; use para fins pessoais.

## Instagram e outras redes no player VLC (sem conta)

- Links do **Instagram** (colados ou compartilhados do app do Instagram) tocam no **player VLC**, sem entrar em conta nenhuma: o Tocador busca o vídeo como visitante, guarda numa pasta temporária do app e o VLC toca de lá. Leva alguns segundos (uma faixa no topo mostra o progresso e tem um botão para cancelar).
- Em **TikTok, Facebook, X, Vimeo, YouTube** e outras redes, o mesmo acontece quando você escolhe **Abrir como: Player VLC**.
- Abrir o mesmo vídeo de novo toca na hora. A pasta temporária guarda no máximo ~1,5 GB e apaga os mais antigos sozinha; nada vai para a galeria sem você pedir.
- Para guardar um vídeo, toque em **Salvar este vídeo no aparelho** no player: ele é copiado da pasta temporária (sem baixar de novo) para **Filmes/Tocador** e aparece na Biblioteca, em "Vídeos baixados".
- Vídeos privados ou que o Instagram só mostra para quem tem conta não podem ser buscados assim: nesse caso o app abre o site.

## Vídeos e músicas do aparelho

- **Abrir arquivo** abre o seletor do Android. O arquivo toca no player VLC e fica nos *Recentes*.
- **Vídeos em pé** (gravados com o celular na vertical) aparecem inteiros e sem esticar. Na tela cheia, o celular fica de pé. Vale para qualquer formato (4:3, 21:9…).

## Tela do player

- **Barra de progresso** e botões de **voltar e avançar 10 s**.
- **Tela cheia**: mostra **só o vídeo**, sem menus, comentários ou botões do site. O celular gira sozinho conforme o formato do vídeo, e as barras do Android somem. Para sair, use o botão voltar ou o ícone de sair da tela cheia.
- **Toque duplo** sobre o vídeo, no lado direito, avança 10 s; no lado esquerdo, volta 10 s. Toques seguidos somam (20 s, 30 s…). Toque duplo no meio pausa ou continua. Um toque simples mostra ou esconde os controles. No YouTube fora da tela cheia, vale o toque duplo do próprio YouTube.
- **Qualidade**: no YouTube, "Máxima disponível" (padrão, reaplicada a cada vídeo) ou um nível fixo (4K, 1440p, 1080p…). Em outros sites, a chave **Versão para computador** costuma liberar qualidades maiores. No player VLC, "Máxima" impede que streams `.m3u8`/DASH baixem a qualidade quando a internet oscila.
- **Legendas**:
  - **YouTube**: escolha o idioma (inclusive as legendas automáticas) e, se quiser, **Traduzir para português**.
  - **Baixar legenda como texto (.txt)**: salva a legenda inteira do vídeo, sem horários, em **Download/Tocador**. Funciona no YouTube e em sites que usam legendas padrão da web.
  - **Arquivos de legenda encontrados**: qualquer arquivo de legenda (`.vtt`, `.srt`, `.ttml`) que o player de um site pedir aparece na gaveta de Legendas, pronto para baixar como texto. Isso cobre sites com player próprio, como o jw.org.
  - **Player VLC**: escolhe as legendas embutidas no arquivo ou transmissão. Elas não podem ser baixadas como texto.
  - Na tela cheia, a legenda continua sendo a do próprio site (uma só, por cima do vídeo). No YouTube, ela fica na base da tela, a 64 px do rodapé nas duas orientações, com o dobro do tamanho.
- **Áudio dublado (YouTube)**: em vídeos com o áudio em vários idiomas, a gaveta **Áudio e legendas** mostra as faixas para trocar com um toque. Se o YouTube não aceitar a troca por ali, use a engrenagem do próprio vídeo → *Faixa de áudio*.
- **Dublagem em português**: no YouTube, o cartão **Dublar em português** fica no meio do player (e o botão **Dublar** embaixo e na barra recolhida). Escolha de onde vem o texto e toque em **Dublar em português**.
  - **Legenda do vídeo** (só YouTube, instantâneo): usa a legenda do vídeo, traduzida para português pelo próprio YouTube.
  - **Whisper: ouvir o vídeo inteiro antes** (sincronia perfeita): o Whisper, a inteligência artificial de reconhecimento de fala da OpenAI (código aberto), roda no próprio celular. Ele ouve o vídeo do começo ao fim, na velocidade escolhida (1x é mais preciso; 2x é mais rápido). Depois o vídeo recomeça dublado, com cada frase na hora certa. A dublagem fica guardada: da próxima vez, aparece **Dublagem já preparada deste vídeo**.
  - **Whisper ao vivo**: dubla enquanto o vídeo toca. A voz vem alguns segundos depois da fala original.
  - O Whisper funciona **em vídeos sem legenda** e em qualquer site com vídeo, não só no YouTube.
  - **Modelos do Whisper** (com os nomes do TurboScribe), em *Ajustes → Dublagem e Whisper → Ajustes do Whisper → Escolher modelo* (não precisa ter um vídeo aberto). Toque em **Escolher modelo** para ver a lista, escolha e, se ainda não estiver baixado, toque em **Baixar o modelo agora** (ou ele baixa na primeira vez que for usado):
    - **Guepardo** (Whisper base, cerca de 80 MB): o mais rápido, mas erra bem mais as palavras.
    - **Golfinho** (Whisper small, cerca de 250 MB): equilibrado e preciso. É o padrão.
    - **Baleia** (Whisper large-v3-turbo, cerca de 1 GB): a melhor precisão, mas bem mais lenta (pode levar mais tempo do que o próprio vídeo). Pede um celular com uns 6 GB de memória; em celulares com menos, o app usa o Golfinho. Prefira "ouvir o vídeo inteiro antes" (o ao vivo não acompanha) e deixe a tela apagar: o serviço em segundo plano mantém o trabalho andando.
  - **Idioma do vídeo:** se você sabe o idioma, escolha em *Ajustes do Whisper*. Em *Automático*, o app só fixa o idioma quando o Whisper está confiante (ou depois de três trechos), para uma introdução com música não fazer o vídeo inteiro sair no idioma errado.
  - O app ouve o som do vídeo dentro do próprio navegador interno: **não pede nenhuma autorização** e não grava nada. O som não sai do celular; só o texto vai para a tradução.
  - Alguns sites protegem o arquivo de vídeo e não deixam o app ouvir o som (o app avisa). No YouTube funciona.
  - **Voz**: vozes neurais da Microsoft, as mesmas do "Ler em voz alta" do Edge: Francisca, Antônio e Thalita (Brasil), Raquel e Duarte (Portugal). Elas precisam de internet. Toque em **Ouvir a voz** para comparar. Se o serviço falhar, o app usa a voz do Android.
  - Cada frase é dita inteira, sem ser cortada pela seguinte. Se a frase for longa para o tempo dela, a voz acelera um pouco.
  - O áudio original fica baixo enquanto a voz fala (o volume é ajustável).
  - O Whisper requer celular de 64 bits (como o Galaxy S22). Em celulares de 32 bits, só a dublagem pela legenda funciona. A dublagem funciona no modo Site; no player VLC, não.
- **Legendador (Whisper)**: na gaveta **Áudio e legendas** de um arquivo do aparelho ou de um vídeo gravado aberto no player VLC, toque em **Gerar legenda**. O Whisper ouve o som direto do arquivo, mais rápido que assistir. A legenda aparece no vídeo e pode ser baixada em **.srt** (com horários) ou **.txt**, no idioma original ou **traduzida para português**. Funciona em vídeos sem legenda nenhuma. Transmissões ao vivo não podem ser legendadas.
  - No modo site, a legenda criada pela dublagem do Whisper também pode ser baixada em .srt.
- **Bloquear anúncios**: chave na aba *Ajustes* (ligada por padrão); a tela inicial mostra quantos foram bloqueados. No YouTube, o app apaga a lista de anúncios que vem com o vídeo, recusa os pedidos aos servidores de anúncio e, se algum anúncio ainda começar, deixa mudo, adianta até o fim e toca em "Pular". Também esconde os banners. O YouTube muda isso com frequência, e às vezes um anúncio pode escapar. Bloquear anúncios contraria os termos de uso do YouTube.
- **Equalizador**: no player VLC, 10 bandas, pré-amplificação e presets. No modo site, o equalizador do Android (depende do aparelho).

## jw.org

Em vídeos do jw.org, o app consulta a API pública do site. Ela informa as resoluções (até 720p) e a legenda de cada vídeo.

- Na gaveta **Qualidade**, em *Vídeo do jw.org*, escolha **Assistir em 720p** (ou outra resolução). O vídeo abre no player VLC com a qualidade fixa e a legenda do site.
- Na gaveta **Legendas**, a **Legenda do jw.org** pode ser baixada como texto.

## Os dois modos

| Link | Modo |
|---|---|
| YouTube, Vimeo, Dailymotion, Twitch e outros sites | **Site** (navegador interno, com o player do próprio site) |
| Rádio online, `.mp3`, `.aac`, `.m3u8`, `.mp4`, `rtsp://` … | **Player VLC** |

Se o modo automático errar, use *Abrir como: Site / Player VLC*.

## Limitações conhecidas

- Os sites mudam com frequência e podem bloquear a reprodução em segundo plano a qualquer momento.
- Em alguns aparelhos (Xiaomi, Samsung, Motorola…), a economia de bateria pode cortar o som com a tela apagada. Em *Dicas → Ajuste de bateria*, deixe o Tocador **sem restrição**.
- Qualidades muito altas (4K) dependem do celular conseguir decodificar o vídeo.
- Qualidade do YouTube: o app usa o comando do player e também grava a preferência no mesmo lugar onde o YouTube guarda a escolha manual. Se o YouTube não aceitar, a gaveta de Qualidade avisa. A **versão para computador** troca o User-Agent e também os *Client Hints* (o sinal de "sou celular") e mantém o YouTube em www.youtube.com.
- Se você arrastar o app para fora da lista de recentes, o modo site para. O modo VLC continua tocando.
- Algo não funcionou? Em *Dicas*, toque em **Copiar diagnóstico** e envie o texto.

## Como compilar

Não precisa do Android SDK. Basta ter Java 11+, Node 22+, Python 3 com Pillow e acesso ao Maven Central (libVLC, ONNX Runtime, OkHttp).

```bash
./construir.sh        # gera dist/Tocador.apk
```

- `src/app/tocador/`:
  - `Principal`: telas, tela cheia e legendas;
  - `Navegador`: modo site, qualidade, isolamento do vídeo e legendas da página;
  - `Motor`: player VLC;
  - `Servico`: segundo plano;
  - `Legendas`: conversão de legendas para texto puro;
  - `JwOrg`: API do jw.org;
  - `Dublador`: dublagem (fila de falas, sincronia, voz);
  - `Whisper` + `Transcritor` + `Modelos`: reconhecimento de fala no celular (ONNX Runtime) e download do modelo (o som chega da página pela ponte `TocadorAudio`);
  - `Tradutor`: tradução das falas para português;
  - `VozNeural`: vozes neurais (protocolo do projeto edge-tts);
  - `Roteiro`: falas e horários;
  - `Listas`: listas M3U/M3U8/PLS (leitura, estilos, busca);
  - `Fila`: ordem de reprodução (sequência, aleatório, repetir);
  - `Legendador`: legenda de arquivos com o Whisper (.srt/.txt);
  - `Anuncios`: bloqueio de anúncios;
  - `Visualizador` + `Estacoes`: animação da rádio (espectro pelo Visualizer do Android) e dados da rádio (Radio Browser);
  - `Baixador`: downloads com o yt-dlp (Python/QuickJS/FFmpeg do youtubedl-android, wheels do PyPI);
  - `ServicoTrabalho`: mantém downloads, legendas e o Whisper vivos com a tela apagada (serviço em primeiro plano, wake lock, notificação com Cancelar);
  - `AgenteCH`: Client Hints da versão para computador;
  - `Arquivos`: salvar na pasta Download;
  - `EqSistema`: equalizador do Android;
  - `Diagnostico`: registro para suporte.
- `assets/whisper/`: filtros de frequência (80 faixas para Guepardo e Golfinho, 128 para a Baleia) e vocabulário do Whisper (licença MIT, do pacote `openai-whisper`).
- `assets/painel.html`: painel (início, player, gavetas de legendas, qualidade e equalizador).
- `assets/controles.html`: controles sobre o vídeo (toque duplo, progresso, legenda na tela cheia).
- `ferramentas/empacotar.mjs`: monta o manifesto, compacta e assina o APK (assinatura v2).
- `chave-assinatura.json`: chave que assina o app. **Guarde-a**: sem ela, uma versão nova não instala por cima da antiga. Qualquer pessoa com acesso a esta chave consegue assinar versões que instalam por cima da sua.
