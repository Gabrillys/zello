# Tocador

Aplicativo Android para assistir e ouvir **vídeos do YouTube e de outros sites**, além de **rádios e links diretos**. Ele continua tocando **em segundo plano e com a tela apagada**, tem **tela cheia só com o vídeo**, **qualidade máxima**, **toque duplo para voltar e avançar**, **legendas** (com download em texto puro) e **equalizador**.

Arquivo para instalar: [`dist/Tocador.apk`](dist/Tocador.apk). Funciona em celulares arm64 e arm32 com Android 7 ou mais novo. As versões novas instalam por cima das antigas.

## Como usar

- **Pelo Compartilhar:** no app do YouTube (ou de outro site), toque em *Compartilhar* e escolha **Tocador**.
- **Colando o link:** abra o Tocador, toque em *Colar* e depois em *Tocar*. Os atalhos levam direto ao YouTube, Vimeo, Dailymotion e Twitch.
- Pode **apagar a tela** ou trocar de app: o som continua. A notificação, os fones e a tela de bloqueio controlam a reprodução.
- O botão **voltar** não fecha o app. Para encerrar, use **Parar**.

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
  - Na tela cheia, a legenda continua sendo a do próprio site (uma só, por cima do vídeo).
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

Não precisa do Android SDK. Basta ter Java 11+, Node 22+, Python 3 com Pillow e acesso ao Maven Central.

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
  - `AgenteCH`: Client Hints da versão para computador;
  - `Arquivos`: salvar na pasta Download;
  - `EqSistema`: equalizador do Android;
  - `Diagnostico`: registro para suporte.
- `assets/painel.html`: painel (início, player, gavetas de legendas, qualidade e equalizador).
- `assets/controles.html`: controles sobre o vídeo (toque duplo, progresso, legenda na tela cheia).
- `ferramentas/empacotar.mjs`: monta o manifesto, compacta e assina o APK (assinatura v2).
- `chave-assinatura.json`: chave que assina o app. **Guarde-a**: sem ela, uma versão nova não instala por cima da antiga. Qualquer pessoa com acesso a esta chave consegue assinar versões que instalam por cima da sua.
