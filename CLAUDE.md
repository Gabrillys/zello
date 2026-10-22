# Instruções para o Claude

## Tocador (app Android em `tocador/`)

- **Entrega do APK pela conversa (pedido do usuário em 22/10/2026):** a cada atualização do Tocador, depois de compilar (`tocador/construir.sh`) e verificar, envie o `tocador/dist/Tocador.apk` **direto na conversa**, como arquivo para baixar, com uma legenda curta dizendo a versão e o que mudou. O usuário instala pelo celular a partir desse arquivo.
- O APK continua sendo commitado em `tocador/dist/` enquanto couber no limite de 100 MB por arquivo do GitHub (em 22/10/2026 estava com cerca de 96,5 MiB). Se passar do limite, não commite o APK: só envie pela conversa e avise o usuário.
