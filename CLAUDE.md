# Instruções para o Claude

## Tocador (app Android em `tocador/`)

- **Entrega do APK pela conversa (pedido do usuário em 22/10/2026):** a cada atualização do Tocador, depois de compilar (`tocador/construir.sh`) e verificar, envie o `tocador/dist/Tocador.apk` **direto na conversa**, como arquivo para baixar, com uma legenda curta dizendo a versão e o que mudou. O usuário instala pelo celular a partir desse arquivo.
- **O APK não vai mais para o GitHub (pedido do usuário em 02/11/2026):** envie **só** pela conversa. `tocador/dist/` está no `.gitignore`; não commite o APK. O código continua sendo commitado e enviado normalmente.
- Antes de mexer, busque a branch remota (`git fetch`): outras sessões também publicam versões nela. Se chegou versão nova, junte (merge) antes de numerar a sua, para não haver duas versões com o mesmo número e nada da outra se perder.
