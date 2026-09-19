# Atalho instalável da Sapataria Zello (tela cheia, sem barra de endereço)

Esta pasta é uma página sua, publicada de graça, que abre o sistema em tela
cheia e pode ser **instalada** como aplicativo no celular e no computador.
Ela também tira da tela o aviso "Este aplicativo foi criado por um usuário do
Google Apps Script", porque o sistema passa a aparecer dentro da sua página.

## Arquivos

| Arquivo | Para que serve |
| --- | --- |
| `index.html` | Abre o sistema de gestão em tela cheia. **É aqui que fica o link do sistema.** |
| `orcamento.html` | Página pública para divulgar nas redes sociais — o cliente marca um horário de orçamento sem precisar do link do Google. |
| `manifest.json` | Diz ao navegador o nome, os ícones e que o app abre em tela cheia. |
| `sw.js` | Faz o app abrir na hora e mostrar um aviso claro quando estiver sem internet. |
| `icone-192.png`, `icone-512.png`, `icone-512-mascara.png` | Ícones do app. |
| `icone-180-apple.png`, `favicon-64.png` | Ícones do iPhone e da aba do navegador. |

## Publicar (GitHub Pages, grátis, uma vez só)

1. Crie uma conta em **github.com** (gratuita).
2. Clique em **New repository**. Nome: `zello`. Marque **Public**. Clique em **Create repository**.
3. Na página do repositório, clique em **uploading an existing file** e arraste
   **todos os arquivos desta pasta** (menos este LEIA-ME, se preferir).
   Clique em **Commit changes**.
4. Vá em **Settings > Pages**. Em "Source", escolha **Deploy from a branch**;
   em "Branch", escolha **main** e **/ (root)**. Clique em **Save**.
5. Espere de 1 a 2 minutos. O endereço fica assim:
   `https://SEU-USUARIO.github.io/zello/`

Alternativas igualmente gratuitas, se preferir arrastar a pasta em vez de usar
o GitHub: **Cloudflare Pages** ou **Netlify Drop**.

## Instalar no celular

1. Abra esse endereço no **Chrome** do celular.
2. Toque no menu (três pontinhos) e escolha **Instalar app**
   (ou "Adicionar à tela inicial" → **Instalar**).
3. O ícone da Sapataria aparece na tela inicial. Ao abrir, o sistema ocupa a
   tela toda, sem barra de endereço e sem o aviso do Google.

No **iPhone**: abra no Safari, toque em Compartilhar e em
**Adicionar à Tela de Início**.

No **computador**: abra no Chrome e clique no ícone de instalar, do lado
direito da barra de endereço.

## Se o link do sistema mudar um dia

Este é o ganho principal: o endereço que você instalou e divulga é **seu**.

1. No GitHub, abra `index.html` e clique no lápis (editar).
2. Troque o endereço na linha `var LINK_DO_SISTEMA = "..."`.
3. Clique em **Commit changes**. Feche e abra o app de novo.

## Página pública de Orçamento (para divulgar nas redes sociais)

O arquivo `orcamento.html` funciona exatamente como o `index.html`, mas abre
só a página onde qualquer pessoa marca um horário de orçamento — sem cadastro,
sem senha. Depois de publicar (mesmo passo a passo acima), o link fica:

`https://SEU-USUARIO.github.io/zello/orcamento.html`

É esse link que você divulga no Instagram, no WhatsApp, etc., no lugar do link
antigo do Google. Ele também não muda mais, mesmo que o link do sistema por
trás precise ser trocado um dia — nesse caso, edite a mesma linha
`LINK_DO_SISTEMA`, mas dentro do arquivo `orcamento.html` (ele tem a sua
própria cópia da linha, com `?page=orcamento-publico` no final).

A faixa superior e a marca d'água configuradas na aba Empresa aparecem
normalmente nessa página, do mesmo jeito que aparecem no sistema.

## Bom saber

- O sistema continua sendo o do Google: os dados, a planilha e as senhas não mudam.
- Continua precisando de internet. Sem rede, o app abre e avisa.
- Depois de instalar, teste uma vez dentro do app: copiar o link público,
  gerar um backup e abrir uma mensagem de WhatsApp. Se algo não funcionar
  dentro da moldura, use o link direto do sistema para essa tarefa e me avise.
