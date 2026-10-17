# Bancada de avaliação: Tocador 4.4 "Ultra"

Avaliação **simulada**: os pareceres abaixo seguem os critérios que cada especialidade usa, mas não vêm de pessoas reais. Os números da bateria de testes, ao contrário, são **medidos** (Chromium, com as dimensões de tela de celulares Android).

## 1. Bateria de testes medida

Ferramenta: `ferramentas/banca.mjs` (precisa do `playwright-core` e do Chromium; uso: `node ferramentas/banca.mjs assets/painel.html pasta-das-fotos`). Ela carrega o painel do app com dados simulados e mede, em cada cena:
- quanto falta de tela (rolagem necessária);
- se algo vaza para os lados;
- quantos botões têm menos de 40 px;
- erros de script.

Telas usadas: 360×740 (Android compacto), 384×823 (Galaxy S22 Ultra), 412×915 (Android grande) e 823×384 (S22 Ultra deitado). Cenas: início, música com letra, rádio, YouTube (painel aberto e barra recolhida) e vídeo no VLC (Instagram).

| | Antes (4.3) | Depois (4.4) |
|---|---|---|
| Cenas que cabem na tela sem rolar | 7 de 24 | **24 de 24** |
| Rolagem máxima no início | 741 px (2 telas) | **0 px** |
| Rolagem no player de música | 109 a 440 px | **0 px** |
| Rolagem no player do YouTube | 66 a 370 px | **0 px** |
| Alvos de toque menores que 40 px | 4 por tela | **0** |
| Vazamento para os lados | nenhum | nenhum |
| Erros de script (12 passos de navegação) | n/a | **0** |

## 2. Pareceres

**Designer de aplicativos (UX móvel).** "O app tinha funções demais à vista e escondia as melhores. A dublagem, que é o diferencial, ficava no fundo de uma gaveta com tudo junto."
- **Feito:** quatro abas fixas embaixo (Início, Biblioteca, Baixar, Ajustes), o padrão que o usuário já conhece de apps de música.
- **Feito:** player em tela inteira com minimizar e mini player.
- **Feito:** botões do player que mudam conforme o conteúdo (Dublar no YouTube, Letra nas músicas, Tela cheia nos vídeos), com o resto em "Mais".
- **Feito:** a gaveta "Áudio e legendas" foi dividida em três folhas com um assunto cada: **Letra**, **Dublar** e **Legendas**.

**Estilista web (direção visual).** "Paleta boa, mas sem hierarquia."
- **Feito:** um só acento dourado, em degradê metálico, só nas ações principais.
- **Feito:** superfícies em vidro escuro, raios de 18 a 26 px, saudação por horário e selo "ULTRA".
- **Feito:** ícones em blocos coloridos e títulos grandes com espaçamento negativo.
- **Feito:** animações curtas (entrada de página, folhas com mola, pulso no cartão de dublagem ativa).
- **Feito:** as cores das barras do Android acompanham o fundo.

**Engenheiro web (front-end).** "Painel em rolagem única crescia sem limite."
- **Feito:** estrutura de moldura fixa (topo, páginas com rolagem própria, mini player, abas) e área segura das bordas.
- **Feito:** modo "tela baixa" automático quando o vídeo ocupa a parte de cima ou o celular está deitado: nada some, tudo encolhe.
- **Feito:** checagem automática de 192 referências entre código e tela, sem nenhuma quebrada.
- **Feito:** a animação para por completo quando não está à vista.

**Engenheiro de qualidade (QA).** "Sem teste automatizado de tela, cada versão pode quebrar o layout."
- **Feito:** `ferramentas/banca.mjs` (24 cenas) e `ferramentas/fluxo.mjs` (12 passos: letra, Mais, equalizador, minimizar, biblioteca, lista, baixar, ajustes, voltar ao player, YouTube, dublar).
- **Pendente:** o teste no celular de verdade continua sendo seu. O ambiente daqui não tem o Android.

**Especialista em acessibilidade.** "Botões de 31 a 36 px e texto cinza claro demais em alguns pontos."
- **Feito:** todos os alvos com 40 px ou mais (a maioria com 44 px).
- **Feito:** rótulos de leitor de tela nos botões só de ícone.
- **Recomendação:** testar com o TalkBack e com a fonte do sistema em tamanho grande.

**CEO de prospecção de mercados (internacional).** "Como produto pago internacional, o Tocador hoje **não passa** na Google Play nem resiste a uma análise jurídica." Os motivos não estão no visual:
- **Download do YouTube e bloqueio de anúncios:** violam os termos do YouTube e as regras da Play Store, que remove apps assim.
- **Letras pelo Musixmatch, NetEase e Kugou:** acesso não oficial. Para vender, é preciso licença (por exemplo, a API paga do Musixmatch, ou LyricFind).
- **Vozes da Microsoft pelo serviço do Edge:** uso não previsto. Para vender, é preciso o Azure Speech pago.
- **Idioma:** o mercado internacional pede o app em inglês e espanhol, no mínimo. Hoje tudo está em português.
- **Caminho viável:** uma edição "Store" sem download do YouTube nem bloqueio de anúncios, com letras e voz licenciadas e venda por assinatura. A edição pessoal continua como está, instalada fora da loja.

**Especialista em produto (retenção).** "Os recursos que o usuário mais citou precisam ser os mais fáceis de achar."
- **Destaques:** dublagem (cartão no meio do player e botão na barra recolhida), letra sincronizada (botão dourado quando há letra), animação, biblioteca com Tocar todos e Aleatório, downloads (aba própria, com ponto verde enquanto baixa), contador de anúncios bloqueados no início, salvar vídeo do cache e Instagram no VLC sem conta.

## 3. O que saiu ou foi recolhido

| Item | Destino | Motivo |
|---|---|---|
| Atalhos Vimeo, Dailymotion, Twitch | **removidos** | nunca usados; o YouTube ficou como bloco |
| Cartão "Dicas" na tela inicial | recolhido em Ajustes | ocupava meia tela |
| Recentes (até 10) | 3 no início (2 deitado) | caber na tela |
| Grade de 6 botões fixos (Qualidade, Equalizador, Novo link, Parar…) | ações do momento + **Mais** | menos ruído; o que importa à vista |
| Linha separada da fila (aleatório/repetir) | dentro dos controles, como no Spotify | menos uma linha |
| Gaveta única "Áudio e legendas" | três folhas (Letra, Dublar, Legendas) | cada uma com um assunto |
| Biblioteca e Baixar na tela inicial | abas próprias | a tela inicial cabe na tela |

Nenhuma função que você pediu nesta conversa foi retirada.

## 4. Próximas recomendações, por prioridade

1. **Ícone do app** desenhado à altura do novo visual (o atual é simples).
2. **Primeira abertura guiada** (3 telas: Compartilhar → Tocador, Dublar, Letra).
3. **Inglês e espanhol** na interface, se a ideia de vender fora for adiante.
4. **Edição Store**, separada da pessoal, se a ideia de vender for séria (ver o parecer do CEO).
5. **Capa do álbum** no mini player e no fundo da letra, quando o arquivo tiver capa.
