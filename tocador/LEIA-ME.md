# Tocador

Aplicativo Android que toca **vídeos de redes sociais e rádios em segundo plano, com a tela apagada**, com **equalizador visual**. Usa o libVLC (código aberto) para links diretos de áudio e vídeo.

Arquivo para instalar: [`dist/Tocador.apk`](dist/Tocador.apk). Funciona em celulares arm64 e arm32 com Android 7 ou mais novo.

## Como usar

- **Pelo Compartilhar (mais prático):** no YouTube, Instagram, TikTok, Facebook, X etc., toque em *Compartilhar* e escolha **Tocador**.
- **Colando o link:** abra o Tocador, toque em *Colar* e depois em *Tocar*.
- Depois disso pode **apagar a tela** ou trocar de app, que o som continua. A notificação tem os botões *Pausar* e *Parar*, e os fones e a tela de bloqueio também controlam a reprodução.
- O botão **voltar** não fecha o app, só manda para segundo plano. Para encerrar, toque em **■ Parar**.

## Os dois modos

| Link | Modo | Equalizador |
|---|---|---|
| Rádio online, `.mp3`, `.aac`, `.m3u8`, `.mp4`, `rtsp://` … | **Player VLC** | completo: 10 bandas, pré-amplificação e presets |
| YouTube, Instagram, TikTok, Facebook, X, Kwai, Vimeo … | **Site** (navegador interno) | o do Android, quando o aparelho permite |

No **modo site**, o vídeo é tocado pelo player da própria rede social. O app impede que o site perceba que a tela apagou, e por isso o som continua. Se a página carregar um arquivo de mídia direto, ele aparece em *Mídias encontradas nesta página*. Aí dá para tocar pelo **player VLC**, com o equalizador completo.

Se o modo automático errar, use *Abrir como: Player VLC / Site*.

## Limitações conhecidas

- Redes sociais mudam com frequência e podem bloquear a reprodução em segundo plano a qualquer momento.
- Em alguns aparelhos (Xiaomi, Samsung, Motorola…), a economia de bateria pode cortar o som com a tela apagada. Use *Abrir ajuste de bateria* no app e deixe o Tocador **sem restrição**.
- O equalizador do modo site depende do aparelho. Alguns não permitem.
- Se você arrastar o app para fora da lista de recentes, o modo site para. O modo VLC continua tocando.

## Como compilar

Não precisa do Android SDK. Basta ter Java 11+, Node 22+, Python 3 com Pillow e acesso ao Maven Central.

```bash
./construir.sh        # gera dist/Tocador.apk
```

- `src/app/tocador/`: código Java (`Principal`: tela; `Motor`: libVLC; `Navegador`: modo site; `Servico`: segundo plano; `EqSistema`: equalizador do Android).
- `assets/painel.html`: painel de controle e equalizador.
- `ferramentas/empacotar.mjs`: monta o manifesto, compacta e assina o APK (assinatura v2).
- `chave-assinatura.json`: chave que assina o app. **Guarde-a**: sem ela, uma versão nova não instala por cima da antiga, e é preciso desinstalar antes. Qualquer pessoa com acesso a esta chave consegue assinar versões que instalam por cima da sua.
