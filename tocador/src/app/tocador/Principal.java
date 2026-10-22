package app.tocador;

import java.io.File;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Principal extends Activity {

    /** Links que já são o arquivo/stream de áudio ou vídeo: vão direto para o VLC. */
    private static final Pattern DIRETO = Pattern.compile(
            "(?i)^(rtsp|rtmp|rtmps|mms|mmsh|rtp|udp|srt)://.*" +
            "|^[^?#]+\\.(mp3|aac|aacp|m4a|ogg|oga|opus|flac|wav|wma|m3u|m3u8|pls|xspf|asx|mp4|m4v|mkv|webm|mov|avi|ts|mpd|3gp)([?#].*)?$");
    /** Cara de rádio online (Icecast/Shoutcast e afins). */
    private static final Pattern RADIO = Pattern.compile("(?i)(icecast|shoutcast|radio|/stream|/listen|/;|:\\d{4,5}(/|$))");
    /** Redes sociais e sites de vídeo: sempre abrem no navegador. */
    private static final Pattern SOCIAL = Pattern.compile(
            "(?i)(youtube|youtu\\.be|instagram|tiktok|facebook|fb\\.watch|twitter|(^|\\.)x\\.com|kwai|vimeo|dailymotion|twitch|reddit|threads|pinterest|linkedin|globo|soundcloud|spotify|deezer|bilibili|rumble|odysee|tumblr|snapchat)");
    /** Redes que só entregam o vídeo pelo site: no player VLC, o vídeo é buscado antes (sem conta) e toca do cache. */
    private static final Pattern REDE_PARA_VLC = Pattern.compile(
            "(?i)(instagram\\.com|instagr\\.am|tiktok\\.com|facebook\\.com|fb\\.watch|twitter\\.com|(^|\\.)x\\.com|kwai|threads\\.net|vimeo\\.com|dailymotion|youtube\\.com|youtu\\.be|reddit|pinterest|rumble|bilibili)");
    private static final Pattern INSTAGRAM = Pattern.compile("(?i)(^|\\.)(instagram\\.com|instagr\\.am)$");
    private static final Pattern LINK = Pattern.compile("(?i)\\b(https?|rtsp|rtmp|mms)://[^\\s\"'<>]+");

    private final Handler h = new Handler(Looper.getMainLooper());
    private Motor motor;
    private SharedPreferences ajustes;
    private FrameLayout raiz, area, telaCheiaBox;
    private LinearLayout coluna;
    private SurfaceView superficie, superficieLegendas;
    private Navegador nav;
    static final String VERSAO = "4.10";
    private WebView painel, controles;
    private WebChromeClient.CustomViewCallback cbTelaCheia;
    private volatile boolean expandido, cheio;
    private String layoutAtual = "";
    /** Vídeo do jw.org da página aberta (arquivos em cada resolução e legenda), se houver. */
    private volatile JwOrg.Midia jw;
    private volatile String jwPagina = "";
    /** Andamento da consulta ao jw.org, mostrado na gaveta de Qualidade. */
    private volatile String jwEstado = "";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(0xFF07080B);
        getWindow().setNavigationBarColor(0xFF0A0B0F);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);

        motor = Motor.de(this);
        ajustes = getSharedPreferences("ajustes", MODE_PRIVATE);

        raiz = new FrameLayout(this);
        raiz.setBackgroundColor(0xFF07080B);
        coluna = new LinearLayout(this);
        coluna.setOrientation(LinearLayout.VERTICAL);
        raiz.addView(coluna, new FrameLayout.LayoutParams(-1, -1));

        area = new FrameLayout(this);
        area.setBackgroundColor(0xFF000000);
        superficie = new SurfaceView(this);
        area.addView(superficie, new FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER));
        // Legendas do VLC: superfície transparente sobre o vídeo.
        superficieLegendas = new SurfaceView(this);
        superficieLegendas.setZOrderMediaOverlay(true);
        superficieLegendas.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        area.addView(superficieLegendas, new FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER));
        // A área do vídeo mudou de tamanho (girou, tela cheia…): avisa o VLC e reencaixa o vídeo.
        area.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int bo, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || bo - t != ob - ot) { motor.tamanho(r - l, bo - t); h.post(encaixarVideo); }
            }
        });
        motor.aoMudarLayout(encaixarVideo);
        motor.aoTerminar(new Runnable() { public void run() { proximoDaFila(1, true); } });
        coluna.addView(area, new LinearLayout.LayoutParams(-1, 0));

        painel = criarPainelWeb("file:///android_asset/painel.html", 0xFF07080B);
        coluna.addView(painel, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Camada de controles sobre o vídeo: toque duplo para voltar/avançar, barra de progresso, legendas.
        controles = criarPainelWeb("file:///android_asset/controles.html", 0x00000000);
        controles.setVisibility(View.GONE);
        area.addView(controles, new FrameLayout.LayoutParams(-1, -1));

        telaCheiaBox = new FrameLayout(this);
        telaCheiaBox.setBackgroundColor(0xFF000000);
        telaCheiaBox.setVisibility(View.GONE);
        raiz.addView(telaCheiaBox, new FrameLayout.LayoutParams(-1, -1));

        setContentView(raiz);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 1);
        }

        ajustar();
        h.post(ciclo);
        tratar(getIntent());
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private WebView criarPainelWeb(String endereco, int fundo) {
        WebView w = new WebView(this);
        w.setBackgroundColor(fundo);
        w.getSettings().setJavaScriptEnabled(true);
        w.getSettings().setDomStorageEnabled(true);
        w.addJavascriptInterface(new Ponte(), "Tocador");
        w.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        w.loadUrl(endereco);
        return w;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        tratar(i);
    }

    /** Link recebido pelo "Compartilhar" de outro app ou ao abrir um arquivo de mídia. */
    private void tratar(Intent i) {
        if (i == null) return;
        String texto = null;
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            texto = t == null ? null : t.toString();
        } else if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            texto = i.getData().toString();
        }
        if (texto == null) return;
        String url = extrairLink(texto);
        if (url == null && texto.startsWith("content:")) url = texto;
        if (url != null) abrir(url, "auto");
        i.setAction(Intent.ACTION_MAIN); // não reabrir o mesmo link ao girar a tela
    }

    static String extrairLink(String texto) {
        Matcher m = LINK.matcher(texto);
        return m.find() ? m.group() : null;
    }

    private String decidirModo(String url) {
        if (url.startsWith("content:") || url.startsWith("file:")) return "vlc";
        if (DIRETO.matcher(url).matches()) return "vlc";
        String host = "";
        try { host = Uri.parse(url).getHost(); if (host == null) host = ""; } catch (Exception e) { }
        if (SOCIAL.matcher(host).find()) return "web";
        if (RADIO.matcher(url).find()) return "vlc";
        return "web";
    }

    /** modo: auto, vlc ou web. */
    void abrir(String url, String modo) {
        url = url.trim();
        if (url.length() == 0) return;
        if (!abrindoDaFila) { filaLista = null; filaIndice = -1; }
        Dublador dub = Dublador.seExistir();
        if (dub != null && dub.ativo()) dub.parar("");
        String hostLink = "";
        try { hostLink = Uri.parse(url).getHost(); if (hostLink == null) hostLink = ""; } catch (Exception e) { }
        // Instagram (sempre) e outras redes quando a pessoa escolhe "Player VLC": busca o vídeo antes e toca do cache.
        if (url.startsWith("http") && (("auto".equals(modo) && INSTAGRAM.matcher(hostLink).find())
                || ("vlc".equals(modo) && REDE_PARA_VLC.matcher(hostLink).find() && !DIRETO.matcher(url).matches()))) {
            abrirPorCache(url);
            return;
        }
        if ("auto".equals(modo)) {
            modo = decidirModo(url);
            String host = "";
            try { host = Uri.parse(url).getHost(); if (host == null) host = ""; } catch (Exception e) { }
            if ("web".equals(modo) && url.startsWith("http") && !SOCIAL.matcher(host).find()) {
                // Link sem cara de nada (ex.: …/stream sem extensão): pergunta ao servidor o que é antes de abrir.
                final String u = url;
                avisar("Abrindo…");
                new Thread(new Runnable() { public void run() {
                    final String m = sondar(u);
                    Diagnostico.anotar("Link " + Diagnostico.curto(u) + ": " + m);
                    runOnUiThread(new Runnable() { public void run() { abrirNoModo(u, m.startsWith("vlc") ? "vlc" : "web"); } });
                } }, "tocador-sonda").start();
                return;
            }
        }
        abrirNoModo(url, modo);
    }

    /**
     * Pede só o começo da resposta: se o servidor manda áudio/vídeo (ou cabeçalhos de rádio "icy-"), é uma transmissão
     * e vai para o player VLC; se manda uma página, abre no navegador.
     */
    static String sondar(String url) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("Icy-MetaData", "1");
            c.setRequestProperty("User-Agent", "VLC/3.0.20 LibVLC/3.0.20");
            c.setRequestProperty("Range", "bytes=0-1");
            c.getResponseCode();
            String tipo = c.getContentType() == null ? "" : c.getContentType().toLowerCase(java.util.Locale.ROOT);
            boolean icy = c.getHeaderField("icy-name") != null || c.getHeaderField("icy-br") != null || c.getHeaderField("icy-metaint") != null;
            if (icy || tipo.startsWith("audio/") || tipo.startsWith("video/") || tipo.contains("mpegurl") || tipo.contains("ogg")
                    || tipo.contains("dash+xml") || tipo.contains("scpls") || tipo.equals("application/octet-stream"))
                return "vlc (" + (icy ? "rádio icy" : tipo) + ")";
            return "web (" + tipo + ")";
        } catch (Exception e) {
            // Servidores Shoutcast antigos respondem "ICY 200 OK", que o Java não entende: é rádio.
            String m = String.valueOf(e.getMessage());
            if (m.contains("ICY") || m.contains("Unexpected status line")) return "vlc (shoutcast)";
            return "web (sem resposta: " + m + ")";
        } finally { if (c != null) c.disconnect(); }
    }

    /** Busca o vídeo (sem conta) com o yt-dlp e, pronto, toca no player VLC a partir do cache do app. */
    private void abrirPorCache(final String url) {
        if (!Baixador.disponivel()) { avisar("Tocar redes sociais no player VLC precisa de um celular de 64 bits. Abrindo o site."); abrirNoModo(url, "web"); return; }
        if ("web".equals(Controle.modo)) Navegador.comandar("pausar");
        motor.parar();
        avisar("Buscando o vídeo… em instantes ele toca no player VLC.");
        Baixador.de(this).paraCache(url, new Baixador.ProntoCache() {
            public void pronto(final java.io.File f, final String titulo) {
                runOnUiThread(new Runnable() { public void run() {
                    abrirNoModo(Uri.fromFile(f).toString(), "vlc");
                    motor.definirTitulo(titulo != null && titulo.length() > 0 ? titulo : "Vídeo");
                } });
            }
            public void falhou(final String motivo) {
                runOnUiThread(new Runnable() { public void run() {
                    if ("cancelado".equals(motivo)) return;
                    avisar("Não consegui buscar o vídeo (" + motivo + "). Abrindo no site.");
                    abrirNoModo(url, "web");
                } });
            }
        });
    }

    private void abrirNoModo(String url, String modo) {
        if ("vlc".equals(modo)) {
            if (Controle.modo.equals("web")) Navegador.comandar("pausar");
            EqSistema.de(this).soltar();
            Controle.modo = "vlc";
            motor.anexar(superficie, superficieLegendas, superficie.getWidth(), superficie.getHeight());
            motor.tocar(url, null, null);
        } else {
            motor.parar();
            Controle.modo = "web";
            if (nav == null) criarNavegador();
            nav.loadUrl(nav.ajustarEndereco(url));
        }
        Servico.iniciar(this);
        ajustar();
    }

    /**
     * O navegador abriu direto um arquivo de áudio/vídeo (a página é só o player simples do Chrome, com a bolinha):
     * é uma transmissão; passa para o player VLC, que tem animação, rodapé com o nome da rádio e equalizador.
     */
    private void verificarMidiaCrua(final String url) {
        if (nav == null || !"web".equals(Controle.modo) || url == null || !url.startsWith("http")) return;
        nav.evaluateJavascript("(function(){var m=document.querySelector('body>video,body>audio');var n=document.body?document.body.children.length:9;"
                + "return !!(m&&n<=1&&(m.currentSrc||m.src)==location.href)})()", new android.webkit.ValueCallback<String>() {
            public void onReceiveValue(String r) {
                if (!"true".equals(r) || !"web".equals(Controle.modo)) return;
                Diagnostico.anotar("O site era só um áudio/vídeo: passando para o player VLC");
                abrirNoModo(url, "vlc");
            }
        });
    }

    private void criarNavegador() {
        Anuncios.ligado = ajustes.getBoolean("anuncios", true);
        nav = new Navegador(this);
        nav.definirAoMudar(new Navegador.AoMudar() {
            public void pagina(final String url) { runOnUiThread(new Runnable() { public void run() { verificarJw(url); verificarMidiaCrua(url); } }); }
        });
        nav.definirQualidade(ajustes.getString("qualidade", "max"));
        if (ajustes.getBoolean("computador", false)) nav.definirComputador(true);
        nav.definirTelaCheia(new Navegador.TelaCheia() {
            public void mostrar(View v, WebChromeClient.CustomViewCallback cb) {
                // Tela cheia pedida pelo próprio site (botão de tela cheia do YouTube, por exemplo).
                cbTelaCheia = cb;
                telaCheiaBox.addView(v, new FrameLayout.LayoutParams(-1, -1));
                telaCheiaBox.setVisibility(View.VISIBLE);
                girarConformeVideo(proporcaoAtual());
                imersivo(true);
            }
            public void esconder() { sairTelaCheiaDoSite(); }
        });
        area.addView(nav, 2, new FrameLayout.LayoutParams(-1, -1)); // abaixo da camada de controles
    }

    /** Página de vídeo do jw.org: consulta a API do site para ter as resoluções e a legenda. */
    private void verificarJw(final String url) {
        final String[] id = JwOrg.identificar(url);
        if (id == null) {
            if (!JwOrg.ehJwOrg(url)) { jw = null; jwPagina = ""; jwEstado = ""; }
            else if (jw == null) jwEstado = "Abra um vídeo do jw.org para escolher a resolução.";
            return;
        }
        final String chave = id[0] + "/" + id[1];
        if (chave.equals(jwPagina)) return;
        jwPagina = chave;
        jw = null;
        jwEstado = "Consultando o jw.org…";
        final String ua = nav.agente();
        new Thread(new Runnable() { public void run() {
            JwOrg.Midia m = null;
            String falha = null;
            try { m = JwOrg.buscar(url, ua); } catch (Exception e) { falha = e.getMessage(); Diagnostico.anotar("jw.org: falha na API: " + falha); }
            final JwOrg.Midia r = m;
            final String motivo = r != null ? "" : "Não consegui obter as resoluções deste vídeo do jw.org" + (falha != null ? " (" + falha + ")" : "") + ".";
            Diagnostico.anotar("jw.org " + chave + ": " + (r == null ? "sem resultado" : r.arquivos.size() + " resoluções, legenda=" + (r.legenda != null)));
            runOnUiThread(new Runnable() { public void run() { if (chave.equals(jwPagina)) { jw = r; jwEstado = motivo; } } });
        } }, "tocador-jw").start();
    }

    /** Toca o vídeo do jw.org no player VLC, na resolução escolhida, com a legenda do site. */
    private void tocarJw(int i) {
        JwOrg.Midia m = jw;
        if (m == null || i < 0 || i >= m.arquivos.size()) return;
        if (Controle.modo.equals("web")) Navegador.comandar("pausar");
        EqSistema.de(this).soltar();
        Controle.modo = "vlc";
        motor.anexar(superficie, superficieLegendas, superficie.getWidth(), superficie.getHeight());
        motor.tocar(m.arquivos.get(i)[1], null, "https://www.jw.org/", null, m.legenda);
        motor.definirTitulo(m.titulo);
        Servico.iniciar(this);
        ajustar();
    }

    private void sairTelaCheiaDoSite() {
        if (telaCheiaBox.getVisibility() != View.VISIBLE) return;
        telaCheiaBox.removeAllViews();
        telaCheiaBox.setVisibility(View.GONE);
        if (!cheio) { imersivo(false); setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED); }
        if (cbTelaCheia != null) { cbTelaCheia.onCustomViewHidden(); cbTelaCheia = null; }
    }

    // ------------------------------------------------------------ tela cheia do app (só o vídeo)

    private double proporcaoAtual() {
        if ("vlc".equals(Controle.modo)) return motor.proporcao();
        JSONObject e = Navegador.estadoPagina;
        int w = e.optInt("vw"), hh = e.optInt("vh");
        return hh > 0 ? (double) w / hh : 0;
    }

    private void girarConformeVideo(double p) {
        if (p > 1.05) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        else if (p > 0 && p < 0.95) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        else setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    private void imersivo(boolean sim) {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(sim ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION : 0);
        if (Build.VERSION.SDK_INT >= 28) {
            // Fora da tela cheia, nada pode ficar embaixo do recorte da câmera (no celular deitado o texto "vazava").
            WindowManager.LayoutParams a = getWindow().getAttributes();
            a.layoutInDisplayCutoutMode = sim ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            getWindow().setAttributes(a);
        }
    }

    void telaCheia(boolean sim) {
        if (sim == cheio) return;
        if (sim && "nada".equals(Controle.modo)) return;
        if (sim && "vlc".equals(Controle.modo) && !motor.temVideoAgora()) return; // rádio: não há imagem
        cheio = sim;
        if ("web".equals(Controle.modo) && nav != null) nav.isolar(sim);
        if (sim) { girarConformeVideo(proporcaoAtual()); imersivo(true); }
        else { setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED); imersivo(false); }
        layoutAtual = "";
        ajustar();
    }

    /** Mantém a proporção do vídeo (em pé, 4:3, 21:9…): a superfície é encaixada na área, sem esticar. */
    private final Runnable encaixarVideo = new Runnable() {
        public void run() {
            int[] r = motor.encaixar(area.getWidth(), area.getHeight());
            for (SurfaceView sv : new SurfaceView[] { superficie, superficieLegendas }) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sv.getLayoutParams();
                int w = r == null ? -1 : r[0], hh = r == null ? -1 : r[1];
                if (lp.width == w && lp.height == hh) continue;
                lp.width = w; lp.height = hh; lp.gravity = android.view.Gravity.CENTER;
                sv.setLayoutParams(lp);
            }
            // Ao saber a proporção, a área do vídeo pode ficar do tamanho certo (vídeo em pé ocupa mais altura).
            String chave = layoutAtual;
            layoutAtual = "";
            ajustar();
            if (layoutAtual.length() == 0) layoutAtual = chave;
        }
    };

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Distribui a tela entre vídeo/página, a camada de controles e o painel. */
    private void ajustar() {
        boolean deitado = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        String modo = Controle.modo;
        boolean videoVlc = "vlc".equals(modo) && motor.temVideoAgora();
        if (cheio && ("nada".equals(modo) || ("vlc".equals(modo) && !videoVlc))) { telaCheia(false); return; }
        String chave = modo + videoVlc + expandido + deitado + cheio + raiz.getWidth() + "x" + raiz.getHeight() + "p" + Math.round(motor.proporcao() * 100);
        if (chave.equals(layoutAtual)) return;
        layoutAtual = chave;

        int largura = raiz.getWidth() > 0 ? raiz.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int altura = raiz.getHeight() > 0 ? raiz.getHeight() : getResources().getDisplayMetrics().heightPixels;
        boolean vlc = "vlc".equals(modo);
        superficie.setVisibility(vlc ? View.VISIBLE : View.GONE);
        superficieLegendas.setVisibility(vlc ? View.VISIBLE : View.GONE);
        if (nav != null) nav.setVisibility("web".equals(modo) ? View.VISIBLE : View.GONE);

        LinearLayout.LayoutParams pa, pp;
        if (cheio) {
            area.setVisibility(View.VISIBLE);
            pa = new LinearLayout.LayoutParams(-1, 0, 1f);
            pp = new LinearLayout.LayoutParams(-1, 0);
            painel.setVisibility(View.GONE);
            controles.setVisibility(View.VISIBLE);
        } else {
            painel.setVisibility(View.VISIBLE);
            if ("web".equals(modo)) {
                area.setVisibility(View.VISIBLE);
                pa = new LinearLayout.LayoutParams(-1, 0, 1f);
                pp = new LinearLayout.LayoutParams(-1, expandido ? (int) (altura * 0.62) : dp(deitado ? 76 : 88));
                controles.setVisibility(View.GONE);
            } else if (videoVlc) {
                area.setVisibility(View.VISIBLE);
                if (deitado && !expandido) {
                    pa = new LinearLayout.LayoutParams(-1, 0, 1f);
                    pp = new LinearLayout.LayoutParams(-1, dp(76));
                } else {
                    // Altura conforme o formato do vídeo (em pé ganha mais espaço), sem empurrar o painel para fora da tela.
                    double prop = motor.proporcao();
                    int alturaVideo = prop > 0 ? (int) (largura / prop) : largura * 9 / 16;
                    alturaVideo = Math.min(alturaVideo, (int) (altura * (deitado ? 0.62 : 0.66)));
                    pa = new LinearLayout.LayoutParams(-1, Math.max(dp(120), alturaVideo));
                    pp = new LinearLayout.LayoutParams(-1, 0, 1f);
                }
                controles.setVisibility(View.VISIBLE);
            } else {
                // Só áudio (rádio, música) ou nada aberto: o painel ocupa tudo.
                // A superfície fica com 1px para o VLC poder detectar vídeo quando ele aparecer.
                area.setVisibility(View.VISIBLE);
                pa = new LinearLayout.LayoutParams(-1, 1);
                pp = new LinearLayout.LayoutParams(-1, 0, 1f);
                controles.setVisibility(View.GONE);
            }
        }
        area.setLayoutParams(pa);
        painel.setLayoutParams(pp);
    }

    private final Runnable ciclo = new Runnable() {
        public void run() {
            ajustar();
            boolean tocando = Controle.tocando(Principal.this);
            boolean telaLigada = tocando && (cheio || telaCheiaBox.getVisibility() == View.VISIBLE
                    || ("vlc".equals(Controle.modo) && motor.temVideoAgora()) || "web".equals(Controle.modo));
            if (telaLigada) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            try { cuidarDaLetra(); } catch (Exception e) { }
            if (ServicoTrabalho.ocupado() && hasWindowFocus()) sugerirSemRestricaoBateria();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        layoutAtual = "";
        ajustar();
        if (cheio || telaCheiaBox.getVisibility() == View.VISIBLE) imersivo(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if ("vlc".equals(Controle.modo)) motor.anexar(superficie, superficieLegendas, superficie.getWidth(), superficie.getHeight());
        if (vizQuer) ligarAnimacao(true); // a tela voltou: a animação volta junto
    }

    @Override
    protected void onStop() {
        // Tela apagada / outro app na frente: o vídeo do VLC sai, o som continua.
        // O navegador NÃO é pausado de propósito, para o som do site continuar.
        if ("vlc".equals(Controle.modo)) motor.desanexar();
        visualizador.desligar(); // a animação não gasta bateria com a tela apagada; volta sozinha ao reabrir
        vizLigada = false;
        super.onStop();
    }

    // ------------------------------------------------------------ letra sincronizada (só músicas, sem vídeo)

    private String letraChave = "", letraEstado = "", letraTitulo = "", letraProcurada = "", letraIaErro = "";
    private final java.util.Set<String> letraIaTentada = new java.util.HashSet<String>();
    private volatile JSONObject letraDados;

    private boolean letrasLigadas() { return ajustes.getBoolean("letras", true); }

    /** Criar a letra com o Whisper quando nenhum catálogo tiver (liga por padrão; só roda com o Whisper já baixado). */
    private boolean letraIaAuto() { return ajustes.getBoolean("letraIaAuto", true); }

    /** Ajuste fino da letra nesta música: de -1 s (mais tarde) a +1 s (mais cedo), em passos de 0,1 s. */
    private int ajusteLetra() { return Math.max(-1000, Math.min(1000, ajustes.getInt("letraAjuste:" + letraChave, 0))); }

    /**
     * Uma vez por segundo: se uma música (só áudio, com duração) toca no player VLC, busca a letra uma vez
     * e a entrega à tela da animação. Vídeos e rádios ao vivo não têm letra.
     */
    private void cuidarDaLetra() {
        if (!letrasLigadas()) { if (letraChave.length() > 0) { letraEstado = "desligada"; trocarMusica("", "", "", 0); } return; }
        if (!"vlc".equals(Controle.modo) || motor.temVideoAgora()) { trocarMusica("", "", "", 0); return; }
        String url = motor.url() == null ? "" : motor.url();
        long dur = motor.estado().optLong("duracao");
        if (url.length() == 0 || dur < 45000 || dur > 20 * 60000) { trocarMusica("", "", "", 0); return; } // rádio ao vivo ou longo demais
        if ("criando".equals(letraEstado) && !Legendador.de(this).trabalhando()) { // o Whisper parou sem entregar
            letraEstado = "ia-falhou";
            letraIaErro = Legendador.de(this).estado();
        }
        if ("sem-letra".equals(letraEstado) && letraIaAuto() && !letraIaTentada.contains(letraChave)) criarLetraComIA(false);
        JSONObject m = motor.meta();
        String artista = m.optString("artista"), musica = m.optString("tituloFaixa");
        if (musica.length() == 0) musica = motor.estado().optString("titulo");
        String[] p = Letras.separar(musica, artista);
        if (artista.length() == 0) artista = p[0];
        else if (p[0].length() > 0 && !Letras.mesmoArtista(p[0], artista)) p[1] = Letras.limpar(musica); // o traço era da versão, não do artista
        trocarMusica(url, artista, p[1], dur / 1000.0);
    }

    private void trocarMusica(final String chave, final String artista, final String musica, final double durSeg) {
        if (chave.equals(letraChave)) return;
        letraChave = chave;
        letraDados = null;
        letraTitulo = "";
        empurrarLetra();
        if (chave.length() == 0) { if (!"desligada".equals(letraEstado)) letraEstado = ""; return; }
        letraProcurada = (artista.length() > 0 ? artista + " — " : "") + musica;
        if (musica.length() == 0) { letraEstado = "sem-titulo"; return; }
        letraEstado = "buscando";
        new Thread(new Runnable() { public void run() {
            // 1) já achada (ou criada) antes  2) arquivo .lrc ao lado da música  3) catálogos na internet
            JSONObject g = letraGuardada(chave);
            if (g == null) g = lrcAoLado(chave);
            if (g == null) { g = Letras.buscar(artista, musica, durSeg); if (g.optBoolean("sincronizada")) guardarLetra(chave, g); }
            final JSONObject r = g;
            runOnUiThread(new Runnable() { public void run() {
                if (!chave.equals(letraChave)) return;
                if (r.optBoolean("sincronizada")) {
                    letraTitulo = "Whisper".equals(r.optString("fonte")) ? letraProcurada + " · criada pelo Whisper"
                            : r.optString("artista") + " — " + r.optString("musica");
                    letraEstado = "pronta";
                    try {
                        letraDados = new JSONObject().put("linhas", r.optJSONArray("linhas")).put("adiant", 500)
                                .put("ajuste", ajusteLetra()).put("titulo", letraTitulo).put("diferenca", r.optInt("diferenca"));
                    } catch (Exception e) { }
                    Diagnostico.anotar("Letra (" + r.optString("fonte") + "): " + letraTitulo + " (" + r.optJSONArray("linhas").length() + " linhas, diferença de duração " + r.optInt("diferenca") + " s)");
                } else {
                    letraEstado = r.has("erro") && !"sem letra".equals(r.optString("erro")) ? "erro" : "sem-letra";
                    Diagnostico.anotar("Letra não encontrada para \"" + artista + " — " + musica + "\": " + r.optString("erro", "só existe letra sem horários"));
                    if ("erro".equals(letraEstado)) letraChave = ""; // falha de rede: tenta de novo depois
                }
                empurrarLetra();
            } });
        } }, "tocador-letra").start();
    }

    private File arquivoLetra(String chave) { return new File(new File(getFilesDir(), "letras"), Dublador.hash(chave) + ".json"); }

    private JSONObject letraGuardada(String chave) {
        try {
            File f = arquivoLetra(chave);
            if (!f.exists()) return null;
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try { return new JSONObject(Listas.ler(in, 4 << 20)); } finally { in.close(); }
        } catch (Exception e) { return null; }
    }

    private void guardarLetra(String chave, JSONObject r) {
        try {
            File f = arquivoLetra(chave);
            f.getParentFile().mkdirs();
            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
            o.write(r.toString().getBytes("UTF-8"));
            o.close();
        } catch (Exception e) { }
    }

    /** "Musica.mp3" com "Musica.lrc" na mesma pasta: usa esse arquivo. */
    private static JSONObject lrcAoLado(String chave) {
        try {
            if (!chave.startsWith("file://") && !chave.startsWith("/")) return null;
            String c = chave.startsWith("file://") ? android.net.Uri.parse(chave).getPath() : chave;
            int ponto = c.lastIndexOf('.');
            File f = new File((ponto > c.lastIndexOf('/') ? c.substring(0, ponto) : c) + ".lrc");
            if (!f.canRead()) return null;
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            JSONArray l;
            try { l = Letras.lrc(Listas.ler(in, 2 << 20)); } finally { in.close(); }
            if (l.length() < 3) return null;
            return new JSONObject().put("sincronizada", true).put("linhas", l).put("fonte", "arquivo .lrc")
                    .put("artista", "").put("musica", f.getName().replaceAll("\\.lrc$", ""));
        } catch (Exception e) { return null; }
    }

    /** Sem letra nos catálogos: o Whisper ouve a música e cria a letra com os horários (fica guardada). */
    private void criarLetraComIA(boolean pedido) {
        final String chave = letraChave;
        if (chave.length() == 0) return;
        letraIaTentada.add(chave);
        final Legendador lg = Legendador.de(this);
        if (!whisperDisponivel()) { if (pedido) avisar("Criar a letra precisa do Whisper, que pede um celular de 64 bits."); return; }
        if (lg.trabalhando()) { if (pedido) avisar("O Whisper está ocupado com outra legenda. Tente quando terminar."); return; }
        String escolhido = Dublador.de(this).modelo();
        // Automático: só com um Whisper já baixado (o escolhido, ou outro que já esteja no aparelho).
        if (!pedido && !Modelos.pronto(this, escolhido)) escolhido = Modelos.prontoAlternativo(this, escolhido);
        if (escolhido == null) return;
        final String mod = escolhido;
        if (!Modelos.pronto(this, mod)) {
            if (!pedido) return;
            if (!Modelos.baixando()) Modelos.baixar(this, mod, null);
            avisar("Baixando o Whisper " + Modelos.apelido(mod) + " (só na primeira vez" + (Modelos.ehTurbo(mod) ? "; é grande, use o Wi-Fi" : "") + "). Toque de novo quando terminar.");
            return;
        }
        letraEstado = "criando";
        letraIaErro = "";
        Diagnostico.anotar("Letra: criando com o Whisper para \"" + letraProcurada + "\"");
        lg.iniciar(chave, letraProcurada, false, mod, new Legendador.Pronto() {
            public void legenda(File srt) {
                JSONArray linhas = Legendador.linhasDeLetra(lg.falas());
                final JSONObject r = new JSONObject();
                try { r.put("sincronizada", linhas.length() >= 3).put("linhas", linhas).put("fonte", "Whisper").put("artista", "").put("musica", ""); } catch (Exception e) { }
                if (linhas.length() >= 3) guardarLetra(chave, r);
                runOnUiThread(new Runnable() { public void run() {
                    if (!chave.equals(letraChave)) return;
                    if (!r.optBoolean("sincronizada")) { letraEstado = "ia-falhou"; letraIaErro = "O Whisper não ouviu canto nesta música."; return; }
                    letraTitulo = letraProcurada + " · criada pelo Whisper";
                    letraEstado = "pronta";
                    try {
                        letraDados = new JSONObject().put("linhas", r.optJSONArray("linhas")).put("adiant", 500)
                                .put("ajuste", ajusteLetra()).put("titulo", letraTitulo).put("diferenca", 0);
                    } catch (Exception e) { }
                    Diagnostico.anotar("Letra criada pelo Whisper: " + r.optJSONArray("linhas").length() + " linhas");
                    empurrarLetra();
                } });
            }
        });
    }

    /** Entrega a letra (ou nada) à tela da animação. */
    private void empurrarLetra() {
        js(painel, "window.letra&&letra(" + (letraDados == null ? "null" : letraDados.toString()) + ")");
    }

    // ------------------------------------------------------------ animação da rádio

    private final Visualizador visualizador = new Visualizador();
    private boolean vizLigada;
    /** A página está mostrando a animação (independe de a tela estar apagada). */
    private boolean vizQuer;
    private String vizMotivo = "";
    private String radioUrlBuscada = "";
    private volatile JSONObject radioInfo = new JSONObject(), radioIcy = new JSONObject();

    /** Rodapé de música (arquivo, download ou playlist de áudio): título, artista, álbum, capa e posição na lista. */
    private JSONObject infoMusica(JSONObject m, Listas.Lista lf, String nomeLista) throws Exception {
        JSONObject o = new JSONObject();
        String titulo = primeiro(m.optString("tituloFaixa"), nomeLista, Listas.nomeDe(motor.url() == null ? "" : motor.url()));
        titulo = titulo.replaceAll("^\\d{1,3} - ", "").replaceAll("\\.(m4a|mp3|opus|webm|flac|ogg|wav|aac)$", "");
        String artista = m.optString("artista"), album = m.optString("album");
        JSONArray estilos = new JSONArray();
        if (lf != null) estilos.put(lf.nome);
        if (m.optString("genero").length() > 0) estilos.put(m.optString("genero"));
        StringBuilder q = new StringBuilder(m.optString("codec"));
        if (m.optInt("kbps") > 0) q.append(q.length() > 0 ? " · " : "").append(m.optInt("kbps")).append(" kbps");
        if (m.optInt("khz") > 0) q.append(q.length() > 0 ? " · " : "").append(String.format(java.util.Locale.ROOT, "%.1f", m.optInt("khz") / 1000.0).replace(".0", "").replace('.', ',')).append(" kHz");
        if (lf != null && filaIndice >= 0) q.insert(0, "Faixa " + (fila.posicao() + 1) + " de " + lf.itens.size() + (q.length() > 0 ? " · " : ""));
        o.put("musica", true).put("nome", titulo).put("agora", artista.length() > 0 || album.length() > 0 ? (artista + (album.length() > 0 ? (artista.length() > 0 ? " — " : "") + album : "")) : "")
         .put("estilos", estilos).put("qualidade", q.toString()).put("logo", capa(m.optString("arte")))
         .put("viz", vizLigada).put("vizMotivo", vizMotivo);
        return o;
    }

    private String capaArquivo = "", capaDados = "";

    /** A capa que o VLC extrai fica num arquivo do app: vira "data:" para a página poder mostrar. */
    private String capa(String arte) {
        if (arte == null || arte.length() == 0) return "";
        if (!arte.startsWith("file://")) return arte;
        if (arte.equals(capaArquivo)) return capaDados;
        capaArquivo = arte;
        capaDados = "";
        try {
            java.io.File f = new java.io.File(Uri.parse(arte).getPath());
            if (f.length() > 0 && f.length() < 600 * 1024) {
                byte[] b = new byte[(int) f.length()];
                java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(f));
                in.readFully(b);
                in.close();
                String tipo = b.length > 3 && (b[0] & 0xff) == 0x89 ? "image/png" : "image/jpeg";
                capaDados = "data:" + tipo + ";base64," + android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
            }
        } catch (Exception e) { }
        return capaDados;
    }

    private static String primeiro(String... v) {
        for (String x : v) if (x != null && x.trim().length() > 0 && !"null".equals(x)) return x.trim();
        return "";
    }

    private final Visualizador.Saida vizSaida = new Visualizador.Saida() {
        public void quadro(final String b, final String w) {
            h.post(new Runnable() { public void run() { if (vizLigada) js(painel, "window.vizQuadro&&vizQuadro(" + b + "," + w + ")"); } });
        }
    };

    void ligarAnimacao(boolean sim) {
        if (sim != vizQuer) Diagnostico.anotar("Animação da rádio: " + (sim ? "mostrada" : "escondida") + " (vídeo=" + motor.temVideoAgora() + ")");
        vizQuer = sim;
        if (!sim) { visualizador.desligar(); vizLigada = false; h.removeCallbacks(conferirAnimacao); return; }
        if (vizLigada) return;
        if (checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
            if (!ajustes.getBoolean("vizPerguntou", false)) {
                ajustes.edit().putBoolean("vizPerguntou", true).apply();
                requestPermissions(new String[] { "android.permission.RECORD_AUDIO" }, 4);
            }
            vizMotivo = "sem-permissao";
            return;
        }
        String r = visualizador.ligar(motor.sessaoAudio(), vizSaida);
        if (!"ok".equals(r)) {
            Diagnostico.anotar("Animação da rádio: sessão do VLC (" + motor.sessaoAudio() + ") recusada: " + r + "; tentando a saída geral");
            r = visualizador.ligar(0, vizSaida);
        }
        vizLigada = "ok".equals(r);
        vizMotivo = vizLigada ? "" : r;
        Diagnostico.anotar("Animação da rádio: " + (vizLigada ? "ligada na sessão " + visualizador.sessaoAtual : r));
        h.removeCallbacks(conferirAnimacao);
        if (vizLigada) h.postDelayed(conferirAnimacao, 3000);
    }

    /** Se a sessão escolhida só entrega silêncio, tenta a saída geral de som (sessão 0). */
    private final Runnable conferirAnimacao = new Runnable() {
        public void run() {
            if (!vizLigada) return;
            Diagnostico.anotar("Animação da rádio: sessão " + visualizador.sessaoAtual + ", quadros=" + visualizador.quadros + ", com som=" + visualizador.comSom
                    + ", tocando=" + Controle.tocando(Principal.this));
            if (visualizador.comSom == 0 && Controle.tocando(Principal.this) && visualizador.sessaoAtual != 0) {
                String r = visualizador.ligar(0, vizSaida);
                vizLigada = "ok".equals(r);
                vizMotivo = vizLigada ? "" : r;
                Diagnostico.anotar("Animação da rádio: sem som na sessão do VLC; saída geral: " + r);
                if (vizLigada) h.postDelayed(this, 3000);
            } else if (visualizador.comSom == 0 && Controle.tocando(Principal.this)) {
                vizMotivo = "sem-dados";
            }
        }
    };

    @Override
    public void onRequestPermissionsResult(int pedido, String[] permissoes, int[] resultados) {
        super.onRequestPermissionsResult(pedido, permissoes, resultados);
        if (pedido == 4 && resultados.length > 0 && resultados[0] == PackageManager.PERMISSION_GRANTED) ligarAnimacao(true);
    }

    /** Nome, gênero e música de agora (da própria rádio), mais logotipo e local (Radio Browser). */
    JSONObject infoRadio() {
        JSONObject o = new JSONObject();
        try {
            JSONObject m = motor.meta();
            String nomeLista = "";
            if (filaLista != null) {
                Listas.Lista l = listas().porId(filaLista);
                if (l != null && filaIndice >= 0 && filaIndice < l.itens.size()) nomeLista = l.itens.get(filaIndice).nome;
            }
            final String url = motor.url() == null ? "" : motor.url();
            final String nomeBusca = m.optString("nome").length() > 0 ? m.optString("nome") : nomeLista;
            if (url.startsWith("http") && !url.equals(radioUrlBuscada)) {
                radioUrlBuscada = url;
                radioInfo = new JSONObject();
                radioIcy = new JSONObject();
                new Thread(new Runnable() { public void run() {
                    JSONObject i = Estacoes.icy(url);
                    if (url.equals(radioUrlBuscada)) radioIcy = i;
                    JSONObject r = Estacoes.buscar(url, nomeBusca.length() > 0 ? nomeBusca : i.optString("nome"));
                    if (url.equals(radioUrlBuscada)) radioInfo = r;
                } }, "tocador-estacao").start();
            }
            Listas.Lista lf = filaLista != null ? listas().porId(filaLista) : null;
            boolean musica = !url.startsWith("http") || (lf != null && "musica".equals(lf.tipo)) || motor.temDuracao();
            if (musica) return infoMusica(m, lf, nomeLista);
            JSONObject rb = radioInfo, ic = radioIcy;
            String nome = primeiro(m.optString("nome"), nomeLista, ic.optString("nome"), rb.optString("nome"), Listas.nomeDe(url));
            // estilos: da rádio, do catálogo; sem repetir, no máximo 4
            java.util.LinkedHashSet<String> tags = new java.util.LinkedHashSet<String>();
            for (String fonte : new String[] { m.optString("genero"), ic.optString("genero"), rb.optString("tags") })
                for (String t : fonte.split("[,;/|]")) {
                    t = t.trim();
                    // siglas curtas ficam em maiúsculas (MPB, POP→Pop só se for palavra comum)
                    if (t.length() > 1 && t.length() < 24 && tags.size() < 4)
                        tags.add(t.length() <= 3 && !t.equalsIgnoreCase("pop") && !t.equalsIgnoreCase("rap") ? t.toUpperCase(java.util.Locale.ROOT)
                                : Character.toUpperCase(t.charAt(0)) + t.substring(1));
                }
            JSONArray estilos = new JSONArray();
            for (String t : tags) estilos.put(t);
            int kbps = m.optInt("kbps") > 0 ? m.optInt("kbps") : ic.optInt("kbps") > 0 ? ic.optInt("kbps") : rb.optInt("bitrate");
            int hz = m.optInt("khz") > 0 ? m.optInt("khz") : ic.optInt("hz");
            String codec = primeiro(m.optString("codec"), rb.optString("codec"));
            StringBuilder q = new StringBuilder();
            if (codec.length() > 0) q.append(codec);
            if (kbps > 0) q.append(q.length() > 0 ? " · " : "").append(kbps).append(" kbps");
            if (hz > 0) q.append(q.length() > 0 ? " · " : "").append(String.format(java.util.Locale.ROOT, "%.1f", hz / 1000.0).replace(".0", "").replace('.', ',')).append(" kHz");
            int canais = m.optInt("canais");
            if (canais > 0) q.append(q.length() > 0 ? " · " : "").append(canais == 1 ? "mono" : canais == 2 ? "estéreo" : canais + " canais");
            String descricao = primeiro(m.optString("descricao"), ic.optString("descricao"));
            if (descricao.equalsIgnoreCase(nome)) descricao = "";
            String site = primeiro(ic.optString("site"), rb.optString("site"));
            o.put("nome", nome).put("agora", m.optString("agora")).put("estilos", estilos).put("genero", tags.isEmpty() ? "" : tags.iterator().next())
             .put("descricao", descricao)
             .put("logo", rb.optString("logo").length() > 0 ? rb.optString("logo") : m.optString("arte"))
             .put("local", rb.optString("local")).put("estado", rb.optString("estado")).put("paisNome", rb.optString("paisNome")).put("pais", rb.optString("pais")).put("idioma", rb.optString("idioma"))
             .put("qualidade", q.toString()).put("site", site.replaceAll("^https?://(www\\.)?", "").replaceAll("/$", ""))
             .put("viz", vizLigada).put("vizMotivo", vizMotivo);
        } catch (Exception e) { }
        return o;
    }

    @Override
    public void onBackPressed() {
        if (telaCheiaBox.getVisibility() == View.VISIBLE) { sairTelaCheiaDoSite(); return; }
        if (cheio) { telaCheia(false); return; }
        if (expandido) { expandido = false; ajustar(); js(painel, "window.recolhido&&recolhido()"); return; }
        if ("web".equals(Controle.modo) && nav != null && nav.canGoBack()) { nav.goBack(); return; }
        // Não fecha: vai para segundo plano e continua tocando.
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacks(ciclo);
        if ("web".equals(Controle.modo)) { Controle.parar(this); Controle.modo = "nada"; }
        if (nav != null) { ((ViewGroup) nav.getParent()).removeView(nav); nav.destroy(); nav = null; }
        painel.destroy();
        controles.destroy();
        super.onDestroy();
    }

    // ------------------------------------------------------------ legendas: baixar como texto

    private void js(WebView w, String codigo) { if (w != null) w.evaluateJavascript(codigo, null); }

    private void avisar(String msg) {
        String c = "window.avisar&&avisar(" + JSONObject.quote(msg) + ")";
        js(painel, c);
        js(controles, c);
    }

    /** Android 9 ou anterior precisa de permissão para gravar na pasta Download. */
    private boolean permissaoGravar() {
        if (Build.VERSION.SDK_INT >= 29) return true;
        if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED) return true;
        requestPermissions(new String[] { "android.permission.WRITE_EXTERNAL_STORAGE" }, 2);
        avisar("Permita o acesso aos arquivos e toque em baixar de novo.");
        return false;
    }

    private void baixarLegenda(String chave, final boolean traduzir) {
        if (!permissaoGravar()) return;
        if (chave.startsWith("tt:")) {
            avisar("Lendo a legenda…");
            lerFaixa(Integer.parseInt(chave.substring(3)), 0);
            return;
        }
        if (chave.startsWith("cap:") || chave.equals("jw")) {
            final String url;
            if (chave.equals("jw")) url = jw != null ? jw.legenda : null;
            else {
                java.util.ArrayList<String[]> l = Navegador.arquivosDeLegenda();
                int i = Integer.parseInt(chave.substring(4));
                url = i >= 0 && i < l.size() ? l.get(i)[0] : null;
            }
            if (url == null) { avisar("Esta legenda não está mais disponível. Abra o vídeo de novo."); return; }
            avisar("Baixando a legenda…");
            final String ua = nav != null ? nav.agente() : null;
            final String cookie = CookieManager.getInstance().getCookie(url);
            final String pagina = nav != null ? nav.getUrl() : null;
            final String titulo = chave.equals("jw") && jw != null ? jw.titulo : ("vlc".equals(Controle.modo) ? motor.titulo() : Navegador.tituloAgora);
            new Thread(new Runnable() { public void run() {
                String texto = "", erro = null;
                try { texto = Legendas.paraTexto(Legendas.baixar(url, ua, cookie, pagina)); } catch (Exception e) { erro = e.getMessage(); }
                salvarLegenda(titulo, "do site", texto, erro);
            } }, "tocador-legenda").start();
            return;
        }
        if (!chave.startsWith("yt:") || nav == null) { avisar("Esta legenda não pode ser baixada como texto."); return; }
        String[] p = chave.substring(3).split(":");
        final String idioma = p[0];
        final boolean auto = p.length > 1;
        final String vid = Navegador.estadoPagina.optString("vid");
        avisar("Preparando a legenda…");
        String u = Navegador.legendaPedida(vid, idioma, auto);
        if (u != null) { buscarLegendaYoutube(u, idioma, traduzir); return; }
        // O player só busca a legenda quando ela é ligada: liga e espera o pedido passar.
        js(nav, Navegador.jsLegendaYoutube(idioma, auto, ""));
        esperarLegenda(vid, idioma, auto, traduzir, 0);
    }

    private void esperarLegenda(final String vid, final String idioma, final boolean auto, final boolean traduzir, final int tentativa) {
        h.postDelayed(new Runnable() { public void run() {
            String u = Navegador.legendaPedida(vid, idioma, auto);
            if (u != null) { buscarLegendaYoutube(u, idioma, traduzir); return; }
            if (tentativa < 20) esperarLegenda(vid, idioma, auto, traduzir, tentativa + 1);
            else {
                Diagnostico.anotar("Legenda: o player não pediu o arquivo de " + idioma);
                avisar("O YouTube não entregou esta legenda. Ligue a legenda no vídeo, espere aparecer o texto e tente de novo.");
            }
        } }, 500);
    }

    private void buscarLegendaYoutube(final String url, final String idioma, final boolean traduzir) {
        final String ua = nav.agente();
        final String cookie = CookieManager.getInstance().getCookie("https://www.youtube.com");
        final String titulo = Navegador.tituloAgora.replaceAll("\\s*-\\s*YouTube\\s*$", "");
        new Thread(new Runnable() { public void run() {
            String texto = "";
            String erro = null;
            try {
                texto = Legendas.paraTexto(Legendas.baixar(Legendas.urlYoutube(url, traduzir ? "pt" : null), ua, cookie, "https://www.youtube.com/"));
                if (texto.length() == 0) {
                    // Formato json3 vazio: tenta no formato original que o player pediu.
                    String original = traduzir ? url.replaceAll("[&?]tlang=[^&]*", "") + "&tlang=pt" : url;
                    texto = Legendas.paraTexto(Legendas.baixar(original, ua, cookie, "https://www.youtube.com/"));
                }
            } catch (Exception e) { erro = e.getMessage(); }
            salvarLegenda(titulo, idioma + (traduzir ? " traduzida para pt" : ""), texto, erro);
        } }, "tocador-legenda").start();
    }

    // ------------------------------------------------------------ dublagem automática (YouTube → português)

    interface AoPedido { void pronto(String url); void falhou(); }

    /** Espera o player do YouTube pedir o arquivo da legenda (ele só pede quando a legenda é ligada). */
    private void esperarPedido(final String vid, final String idioma, final boolean auto, final int tentativa, final AoPedido cb) {
        h.postDelayed(new Runnable() { public void run() {
            String u = Navegador.legendaPedida(vid, idioma, auto);
            if (u != null) cb.pronto(u);
            else if (tentativa < 20) esperarPedido(vid, idioma, auto, tentativa + 1, cb);
            else cb.falhou();
        } }, 500);
    }

    /** Identificador do vídeo atual para guardar a dublagem preparada: o código do YouTube ou o endereço da página. */
    private String idVideoAtual() {
        String v = Navegador.estadoPagina.optString("vid");
        if (v.length() > 0) return v;
        String u = Navegador.enderecoAgora;
        return u == null || u.length() == 0 ? "" : "url:" + u;
    }

    /** modo: legenda, aovivo, preparar ou pronta. */
    void dublar(String modo) {
        final Dublador d = Dublador.de(this);
        if (!"web".equals(Controle.modo) || nav == null) { d.definirEstado("A dublagem funciona com vídeos abertos no modo site."); return; }
        if ("pronta".equals(modo)) {
            if (!d.iniciarPronta(nav, idVideoAtual())) d.definirEstado("Não encontrei a dublagem preparada deste vídeo.");
            return;
        }
        if ("aovivo".equals(modo) || "preparar".equals(modo)) { dublarWhisper("preparar".equals(modo)); return; }
        dublarLegenda();
    }

    /** O Whisper (ONNX Runtime) só vem para processadores de 64 bits. */
    static boolean whisperDisponivel() {
        for (String abi : Build.SUPPORTED_ABIS) if ("arm64-v8a".equals(abi)) return true;
        return false;
    }

    /** Baixa o modelo na primeira vez e começa. O som é lido dentro da página: nenhuma autorização é pedida. */
    private void dublarWhisper(boolean preparar) {
        final Dublador d = Dublador.de(this);
        if (!whisperDisponivel()) { d.definirEstado("O Whisper precisa de um celular de 64 bits."); return; }
        final String mod = d.modelo();
        if (!Modelos.pronto(this, mod)) {
            if (!Modelos.baixando()) Modelos.baixar(this, mod, new Modelos.Fim() { public void pronto(boolean ok, String erro) {
                Diagnostico.anotar("Whisper " + mod + (ok ? " baixado" : " não baixado: " + erro));
            } });
            d.definirEstado("Baixando o Whisper " + Modelos.apelido(mod) + " (só na primeira vez" + (Modelos.ehTurbo(mod) ? "; é grande, use o Wi-Fi" : "") + "). Toque de novo quando terminar.");
            return;
        }
        d.iniciarWhisper(nav, idVideoAtual(), preparar);
    }

    // ------------------------------------------------------------ arquivos do aparelho e listas

    static final int PEDIDO_ARQUIVO = 7002, PEDIDO_LISTA = 7003;
    private String tipoListaPedida = "";
    private Listas listas;
    private String filaLista;
    private int filaIndice = -1;
    private boolean abrindoDaFila;

    Listas listas() {
        if (listas == null) listas = new Listas(getFilesDir());
        return listas;
    }

    private void escolherArquivo(int pedido, String[] tipos) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, tipos)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(i, pedido); } catch (Exception e) { avisar("Nenhum gerenciador de arquivos encontrado."); }
    }

    @Override
    protected void onActivityResult(int pedido, int resultado, Intent dados) {
        super.onActivityResult(pedido, resultado, dados);
        if (resultado != RESULT_OK || dados == null || dados.getData() == null) return;
        final Uri u = dados.getData();
        try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception e) { }
        if (pedido == PEDIDO_ARQUIVO) {
            js(painel, "window.guardarRecente&&guardarRecente(" + JSONObject.quote(u.toString()) + ")");
            abrir(u.toString(), "vlc");
        } else if (pedido == PEDIDO_LISTA) {
            final String tipo = tipoListaPedida;
            avisar("Lendo a lista…");
            new Thread(new Runnable() { public void run() {
                try {
                    String texto = Listas.ler(getContentResolver().openInputStream(u), 30 << 20);
                    importarTexto(texto, Motor.nomeArquivo(Principal.this, u).replaceAll("\\.[A-Za-z0-9]{2,5}$", ""), u.toString(), tipo, null);
                } catch (Exception e) { avisarDeFora("Não consegui ler o arquivo: " + e.getMessage()); }
            } }).start();
        }
    }

    private void avisarDeFora(final String m) { runOnUiThread(new Runnable() { public void run() { avisar(m); } }); }

    /** Fora da linha da tela: transforma o texto numa lista guardada (ou toca direto, se for uma transmissão). */
    private void importarTexto(String texto, String nome, String origem, String tipo, final String urlTransmissao) {
        if (Listas.ehTransmissao(texto) && urlTransmissao != null) {
            runOnUiThread(new Runnable() { public void run() { avisar("Isto é uma transmissão, não uma lista: tocando."); abrir(urlTransmissao, "vlc"); } });
            return;
        }
        java.util.List<Listas.Item> itens = Listas.ler(texto, origem.startsWith("http") ? origem : null);
        if (itens.isEmpty()) { avisarDeFora("Não encontrei nenhum item nesta lista."); return; }
        if (tipo == null || tipo.length() == 0 || "auto".equals(tipo)) tipo = Listas.adivinharTipo(itens);
        final Listas.Lista l = listas().adicionar(nome == null || nome.trim().length() == 0 ? "Lista" : nome.trim(), origem, tipo, itens);
        Diagnostico.anotar("Lista importada: " + itens.size() + " itens, tipo " + tipo);
        runOnUiThread(new Runnable() { public void run() {
            avisar("Lista \"" + l.nome + "\" importada: " + l.itens.size() + " itens.");
            js(painel, "window.listaImportada&&listaImportada(" + JSONObject.quote(l.id) + ")");
        } });
    }

    private final Fila fila = new Fila();
    /** "Tocar todos" foi usado: segue para o próximo em qualquer estilo de lista (não só Músicas). */
    private boolean filaTodos;

    /** Toca o item i da lista escolhido pela pessoa (no aleatório, o resto da lista vem sorteado depois dele). */
    void tocarDaLista(String id, int i) {
        Listas.Lista l = listas().porId(id);
        if (l == null || i < 0 || i >= l.itens.size()) return;
        filaTodos = false;
        fila.definirRepetir(ajustes.getString("repetir", Fila.NAO));
        tocarItemDaFila(id, fila.iniciar(l.itens.size(), i, ajustes.getBoolean("aleatorio", false)));
    }

    /** Botões "Tocar todos" e "Aleatório" da lista. */
    void tocarTodos(String id, boolean aleatorio) {
        Listas.Lista l = listas().porId(id);
        if (l == null || l.itens.isEmpty()) return;
        ajustes.edit().putBoolean("aleatorio", aleatorio).apply();
        fila.definirRepetir(ajustes.getString("repetir", Fila.NAO));
        filaTodos = true;
        tocarItemDaFila(id, fila.iniciar(l.itens.size(), -1, aleatorio));
    }

    private void tocarItemDaFila(String id, int i) {
        Listas.Lista l = listas().porId(id);
        if (l == null || i < 0 || i >= l.itens.size()) return;
        Listas.Item it = l.itens.get(i);
        filaLista = id;
        filaIndice = i;
        abrindoDaFila = true;
        // Rádios, TVs, câmeras e músicas de lista são transmissões/arquivos: player VLC direto (com animação e rodapé).
        String modo = "outro".equals(l.tipo) ? "auto" : "vlc";
        try { abrir(it.url, modo); } finally { abrindoDaFila = false; }
        if ("vlc".equals(Controle.modo)) motor.definirTitulo(it.nome);
    }

    /** dir = +1 próximo, -1 anterior. "sozinho" = o item acabou (segue em listas de Músicas ou depois de "Tocar todos"). */
    void proximoDaFila(int dir, boolean sozinho) {
        if (filaLista == null) return;
        Listas.Lista l = listas().porId(filaLista);
        if (l == null) return;
        if (sozinho && !"musica".equals(l.tipo) && !filaTodos) return;
        if (fila.tamanho() != l.itens.size()) fila.iniciar(l.itens.size(), filaIndice, fila.aleatorio()); // a lista mudou
        // Item com erro não fica repetindo: segue para o próximo mesmo com "repetir faixa".
        boolean erro = sozinho && "erro".equals(motor.estado().optString("situacao"));
        int n = fila.avancar(dir, sozinho && !erro);
        if (n < 0) { if (sozinho) avisar("Fim da lista."); return; }
        tocarItemDaFila(filaLista, n);
    }

    void modoFila(boolean aleatorio, String repetir) {
        ajustes.edit().putBoolean("aleatorio", aleatorio).putString("repetir", repetir).apply();
        fila.definirAleatorio(aleatorio);
        fila.definirRepetir(repetir);
    }

    /** Quantos itens da lista são arquivos que o próprio app baixou (os únicos que ele pode apagar). */
    int contarDoApp(Listas.Lista l) {
        int n = 0;
        if (l.origem.startsWith("baixados:")) for (Listas.Item it : l.itens) if (Baixador.foiBaixadoPeloApp(this, it.url)) n++;
        return n;
    }

    /** Apaga a lista; nas listas de downloads, pode apagar também os arquivos que o app baixou (e só esses). */
    void apagarLista(String id, boolean arquivos) {
        Listas.Lista l = listas().porId(id);
        if (l == null) return;
        int apagados = 0;
        int protegidos = 0;
        if (arquivos && l.origem.startsWith("baixados:")) for (Listas.Item it : l.itens) {
            // Regra de segurança: só apaga o que o próprio Tocador baixou e registrou. Qualquer outro arquivo é ignorado.
            if (!Baixador.foiBaixadoPeloApp(this, it.url)) { protegidos++; continue; }
            try {
                boolean ok = false;
                if (it.url.startsWith("content://")) ok = getContentResolver().delete(Uri.parse(it.url), null, null) > 0;
                else if (it.url.startsWith("file://")) ok = new java.io.File(Uri.parse(it.url).getPath()).delete();
                if (ok) { apagados++; Baixador.esquecer(this, it.url); }
            } catch (Exception e) { Diagnostico.anotar("Não apaguei " + it.nome + ": " + e.getMessage()); }
        }
        if (protegidos > 0) Diagnostico.anotar("Apagar lista: " + protegidos + " arquivo(s) não baixados pelo app foram preservados");
        if (id.equals(filaLista)) { filaLista = null; filaIndice = -1; }
        listas().apagar(id);
        avisar("Lista \"" + l.nome + "\" apagada" + (arquivos ? " (" + apagados + " arquivo(s) removidos do aparelho)" : "") + ".");
    }

    // ------------------------------------------------------------ economia de bateria

    /** O Android não corta nem atrasa um app que o usuário liberou da economia de bateria (importante com a tela apagada). */
    private boolean semRestricaoBateria() {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    /** Abre a janela do sistema que pergunta se o Tocador pode ficar sem restrição de bateria; quem decide é o usuário. */
    private void pedirSemRestricaoBateria() {
        if (semRestricaoBateria()) return;
        try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); }
        catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Exception x) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception y) { } }
        }
    }

    /** Na primeira vez que um trabalho longo (download, legenda, Whisper) roda com o app à frente, pergunta uma vez só. */
    private void sugerirSemRestricaoBateria() {
        if (ajustes.getBoolean("bateriaPerguntada", false) || semRestricaoBateria()) return;
        ajustes.edit().putBoolean("bateriaPerguntada", true).apply();
        Diagnostico.anotar("Bateria: pedindo para o Tocador ficar sem restrição");
        pedirSemRestricaoBateria();
    }

    // ------------------------------------------------------------ downloads

    void baixar(String texto, String tipo, boolean playlist) {
        String url = extrairLink(texto == null ? "" : texto);
        if (url == null) { avisar("Cole o link do vídeo, da playlist ou do canal."); return; }
        if (!Baixador.disponivel()) { avisar("O download precisa de um celular de 64 bits."); return; }
        if (!permissaoGravar()) return;
        Baixador.de(this).baixar(url, tipo, playlist, new Baixador.Pronto() {
            public void terminou(final String nome, final String tipo, final String origem, final java.util.List<String[]> arquivos) {
                runOnUiThread(new Runnable() { public void run() { guardarNaBiblioteca(nome, tipo, origem, arquivos); } });
            }
        });
        avisar("Download iniciado. Acompanhe em Downloads.");
    }

    /** O que foi baixado vira uma lista na Biblioteca (playlist com o nome dela; vídeos avulsos em "Baixados"). */
    private void guardarNaBiblioteca(String nome, String tipo, String origem, java.util.List<String[]> arquivos) {
        boolean audio = "audio".equals(tipo);
        String chave = nome != null && nome.length() > 0 ? "baixados:" + origem : "baixados:" + (audio ? "musicas" : "videos");
        String titulo = nome != null && nome.length() > 0 ? nome : audio ? "Músicas baixadas" : "Vídeos baixados";
        java.util.List<Listas.Item> itens = new java.util.ArrayList<Listas.Item>();
        for (Listas.Lista l : listas().todas()) if (chave.equals(l.origem)) itens.addAll(l.itens);
        for (String[] a : arquivos) {
            boolean ja = false;
            for (Listas.Item i : itens) if (i.url.equals(a[1])) ja = true;
            if (!ja) itens.add(new Listas.Item(a[0], a[1], "", ""));
        }
        listas().adicionar(titulo, chave, audio ? "musica" : "outro", itens);
        js(painel, "window.listaImportada&&listaImportada('')");
        avisar(arquivos.size() + " arquivo(s) baixados. Estão na Biblioteca, em \"" + titulo + "\".");
    }

    // ------------------------------------------------------------ legendador

    void legendar(boolean traduzir) {
        final Legendador lg = Legendador.de(this);
        if (lg.trabalhando()) return;
        if (!whisperDisponivel()) { avisar("O legendador precisa de um celular de 64 bits."); return; }
        if (!"vlc".equals(Controle.modo) || motor.url() == null || motor.url().length() == 0) { avisar("Abra um arquivo ou link de vídeo/áudio para legendar."); return; }
        final String mod = Dublador.de(this).modelo();
        if (!Modelos.pronto(this, mod)) {
            if (!Modelos.baixando()) Modelos.baixar(this, mod, null);
            avisar("Baixando o Whisper " + Modelos.apelido(mod) + " (só na primeira vez" + (Modelos.ehTurbo(mod) ? "; é grande, use o Wi-Fi" : "") + "). Toque de novo quando terminar.");
            return;
        }
        final String url = motor.url();
        lg.iniciar(url, motor.estado().optString("titulo"), traduzir, mod, new Legendador.Pronto() {
            public void legenda(final java.io.File srt) {
                runOnUiThread(new Runnable() { public void run() {
                    if (url.equals(motor.url()) && motor.adicionarLegenda(srt.getPath())) avisar("Legenda pronta e ligada no vídeo.");
                    else avisar("Legenda pronta. Baixe na gaveta de legendas.");
                } });
            }
        });
    }

    private void baixarTextoGerado(final String nome, final String formato, final String texto) {
        if (!permissaoGravar()) return;
        if (texto == null || texto.trim().length() == 0) { avisar("Ainda não há legenda gerada."); return; }
        new Thread(new Runnable() { public void run() {
            try {
                String caminho = "srt".equals(formato)
                        ? Arquivos.salvar(Principal.this, nome, ".srt", "application/x-subrip", texto)
                        : Arquivos.salvarTexto(Principal.this, nome, texto);
                avisarDeFora("Salvo em " + caminho);
                runOnUiThread(new Runnable() { public void run() { js(painel, "window.legendaSalva&&legendaSalva(" + (Arquivos.ultimo != null) + ")"); } });
            } catch (Exception e) { avisarDeFora("Não consegui salvar: " + e.getMessage()); }
        } }).start();
    }

    private void dublarLegenda() {
        final Dublador d = Dublador.de(this);
        JSONObject p = Navegador.estadoPagina;
        if (!p.optBoolean("yt")) { d.definirEstado("A dublagem pela legenda funciona com vídeos do YouTube. Use o Whisper para outros sites."); return; }
        JSONArray legs = p.optJSONArray("legs");
        if (legs == null || legs.length() == 0) { d.definirEstado("Este vídeo não tem legendas. Use \"Ouvir com o Whisper\"."); return; }
        // Melhor origem: legenda em português feita por pessoas > legenda feita por pessoas em outro idioma
        // (traduzida pelo YouTube) > automática em português > automática em outro idioma (traduzida).
        JSONObject melhor = null; int nota = -1;
        for (int i = 0; i < legs.length(); i++) {
            JSONObject l = legs.optJSONObject(i);
            boolean pt = l.optString("idioma").startsWith("pt"), auto = l.optBoolean("auto");
            int n = !auto ? (pt ? 4 : 3) : (pt ? 2 : 1);
            if (n > nota) { nota = n; melhor = l; }
        }
        final String idioma = melhor.optString("idioma"), nome = melhor.optString("nome");
        final boolean auto = melhor.optBoolean("auto"), jaPt = idioma.startsWith("pt");
        final String vid = p.optString("vid");
        final boolean estavaLigada = p.optString("legAtual").length() > 0;
        d.definirEstado("Preparando a dublagem…");
        String u = Navegador.legendaPedida(vid, idioma, auto);
        if (u != null) { buscarRoteiro(u, jaPt, nome, vid); return; }
        js(nav, Navegador.jsLegendaYoutube(idioma, auto, ""));
        esperarPedido(vid, idioma, auto, 0, new AoPedido() {
            public void pronto(String url) {
                if (!estavaLigada) js(nav, Navegador.jsLegendaYoutube("", false, "")); // devolve a legenda como estava
                buscarRoteiro(url, jaPt, nome, vid);
            }
            public void falhou() {
                Diagnostico.anotar("Dublagem: o player não pediu a legenda " + idioma);
                d.definirEstado("O YouTube não entregou a legenda deste vídeo. Dê play, espere alguns segundos e tente de novo.");
            }
        });
    }

    private void buscarRoteiro(final String url, final boolean jaPt, final String origem, final String vid) {
        final String ua = nav.agente();
        final String cookie = CookieManager.getInstance().getCookie("https://www.youtube.com");
        new Thread(new Runnable() { public void run() {
            java.util.List<Roteiro.Fala> falas = null;
            String erro = null;
            try { falas = Roteiro.deJson3(Legendas.baixar(Legendas.urlYoutube(url, jaPt ? null : "pt"), ua, cookie, "https://www.youtube.com/")); }
            catch (Exception e) { erro = e.getMessage(); }
            final java.util.List<Roteiro.Fala> f = falas;
            final String motivo = erro;
            runOnUiThread(new Runnable() { public void run() {
                Dublador d = Dublador.de(Principal.this);
                if (f == null || f.isEmpty()) {
                    Diagnostico.anotar("Dublagem: roteiro vazio" + (motivo != null ? " (" + motivo + ")" : ""));
                    d.definirEstado("Não consegui a legenda traduzida" + (motivo != null ? " (" + motivo + ")" : "") + ". Tente de novo.");
                    return;
                }
                if (nav != null) d.iniciarLegenda(nav, f, origem + (jaPt ? "" : " → português"), vid);
            } });
        } }, "tocador-dublagem").start();
    }

    private void lerFaixa(final int i, final int tentativa) {
        if (nav == null) return;
        nav.evaluateJavascript(Navegador.jsTextoFaixa(i), new ValueCallback<String>() {
            public void onReceiveValue(String v) {
                try {
                    final JSONObject o = new JSONObject(v);
                    if (o.has("texto")) {
                        final String titulo = Navegador.tituloAgora;
                        new Thread(new Runnable() { public void run() {
                            salvarLegenda(titulo, o.optString("nome"), Legendas.limpar(o.optString("texto").split("\n")), null);
                        } }).start();
                    } else if (o.optBoolean("carregando") && tentativa < 12) {
                        h.postDelayed(new Runnable() { public void run() { lerFaixa(i, tentativa + 1); } }, 500);
                    } else avisar("Não consegui ler esta legenda.");
                } catch (Exception e) { avisar("Não consegui ler esta legenda."); }
            }
        });
    }

    /** Roda fora da tela principal: grava o arquivo e avisa. */
    private void salvarLegenda(String titulo, String idioma, String texto, String erro) {
        String msg;
        boolean ok = false;
        if (erro != null) msg = "Não consegui baixar a legenda: " + erro;
        else if (texto == null || texto.length() == 0) msg = "A legenda veio vazia. Ligue a legenda no vídeo e tente de novo.";
        else {
            try {
                String caminho = Arquivos.salvarTexto(this, (titulo.length() > 0 ? titulo : "Legenda") + " (legenda " + idioma + ")", texto + "\n");
                msg = "Legenda salva em " + caminho;
                ok = true;
            } catch (Exception e) { msg = "Não consegui salvar: " + e.getMessage(); }
        }
        Diagnostico.anotar("Legenda: " + msg);
        final String m = msg;
        final boolean salvo = ok;
        runOnUiThread(new Runnable() { public void run() {
            avisar(m);
            if (salvo) js(painel, "window.legendaSalva&&legendaSalva(" + (Arquivos.ultimo != null) + ")");
        } });
    }

    private static String nomeQualidade(String q) {
        if ("highres".equals(q)) return "Original (8K)";
        if ("hd2880".equals(q)) return "2880p (5K)";
        if ("hd2160".equals(q)) return "2160p (4K)";
        if ("hd1440".equals(q)) return "1440p";
        if ("hd1080".equals(q)) return "1080p";
        if ("hd720".equals(q)) return "720p";
        if ("large".equals(q)) return "480p";
        if ("medium".equals(q)) return "360p";
        if ("small".equals(q)) return "240p";
        if ("tiny".equals(q)) return "144p";
        return q;
    }

    // ------------------------------------------------------------ ponte com o painel e com a camada de controles

    private void naTela(Runnable r) { runOnUiThread(r); }

    final class Ponte {

        @JavascriptInterface
        public void abrir(final String url, final String modo) {
            naTela(new Runnable() { public void run() { Principal.this.abrir(url, modo == null ? "auto" : modo); } });
        }

        @JavascriptInterface
        public void tocarPausar() { naTela(new Runnable() { public void run() { Controle.tocarPausar(Principal.this); } }); }

        @JavascriptInterface
        public void parar() {
            naTela(new Runnable() { public void run() {
                Dublador dub = Dublador.seExistir();
                if (dub != null) dub.parar("");
                telaCheia(false); Controle.parar(Principal.this); Controle.modo = "nada"; ajustar();
            } });
        }

        /** Vai para um ponto (0 a 1) do vídeo. */
        @JavascriptInterface
        public void buscar(final float pos) {
            naTela(new Runnable() { public void run() {
                if ("vlc".equals(Controle.modo)) motor.buscar(pos); else js(nav, Navegador.jsIrPara(pos));
            } });
        }

        /** Avança (positivo) ou volta (negativo) alguns segundos: usado pelo toque duplo. */
        @JavascriptInterface
        public void pular(final int segundos) {
            naTela(new Runnable() { public void run() {
                if ("vlc".equals(Controle.modo)) motor.pular(segundos * 1000L); else js(nav, Navegador.jsPular(segundos));
            } });
        }

        @JavascriptInterface
        public void volume(final int v) { naTela(new Runnable() { public void run() { motor.volume(v); } }); }

        @JavascriptInterface
        public void telaCheia(final boolean sim) { naTela(new Runnable() { public void run() { Principal.this.telaCheia(sim); } }); }

        @JavascriptInterface
        public void expandir(final boolean e) { naTela(new Runnable() { public void run() { expandido = e; ajustar(); } }); }

        @JavascriptInterface
        public String estado() {
            JSONObject o;
            try {
                if ("vlc".equals(Controle.modo)) o = motor.estado();
                else {
                    o = new JSONObject();
                    if ("web".equals(Controle.modo)) {
                        JSONObject p = Navegador.estadoPagina;
                        long t = p.optLong("tempo"), d = p.optLong("dur");
                        o.put("situacao", Navegador.tocandoAgora ? "tocando" : "pausado");
                        o.put("tocando", Navegador.tocandoAgora);
                        o.put("titulo", Navegador.tituloAgora);
                        o.put("tempo", t).put("duracao", d).put("busca", d > 0).put("posicao", d > 0 ? (double) t / d : 0);
                        o.put("video", p.optInt("n") > 0);
                        // A legenda é sempre a do próprio site (na tela cheia ela continua visível por cima do vídeo);
                        // o app não desenha outra, para não aparecer em dobro.
                        o.put("legendaAgora", "");
                        if (p.optBoolean("yt")) o.put("qualidade", nomeQualidade(p.optString("q")));
                    } else o.put("situacao", "parado");
                }
                o.put("modo", Controle.modo);
                if (filaLista != null) {
                    Listas.Lista l = Principal.this.listas().porId(filaLista);
                    if (l != null) o.put("fila", new JSONObject().put("nome", l.nome).put("tipo", l.tipo).put("indice", filaIndice).put("total", l.itens.size())
                            .put("ordem", fila.posicao()).put("aleatorio", ajustes.getBoolean("aleatorio", false)).put("repetir", ajustes.getString("repetir", Fila.NAO)));
                }
                o.put("anuncios", Anuncios.ligado);
                Baixador bx = Baixador.seExistir();
                if (bx != null) o.put("preparando", bx.estadoCache());
                if ("vlc".equals(Controle.modo) && bx != null && bx.estaNoCache(motor.url())) o.put("temporario", true);
                o.put("expandido", expandido);
                o.put("cheio", cheio);
                o.put("podeCheio", "web".equals(Controle.modo) || ("vlc".equals(Controle.modo) && motor.temVideoAgora()));
            } catch (Exception e) { o = new JSONObject(); }
            return o.toString();
        }

        /** Faixas de legenda disponíveis agora. */
        @JavascriptInterface
        public String legendas() {
            try {
                if ("vlc".equals(Controle.modo)) return comArquivos(motor.legendas()).toString();
                JSONObject p = Navegador.estadoPagina, o = new JSONObject();
                JSONArray itens = new JSONArray(), legs = p.optJSONArray("legs");
                boolean yt = p.optBoolean("yt");
                if (legs != null) for (int i = 0; i < legs.length(); i++) {
                    JSONObject l = legs.optJSONObject(i);
                    if (l == null) continue;
                    if (yt) itens.put(new JSONObject().put("chave", "yt:" + l.optString("idioma") + (l.optBoolean("auto") ? ":asr" : ""))
                            .put("nome", l.optString("nome") + (l.optBoolean("auto") && !l.optString("nome").contains("(") ? " (automática)" : "")));
                    else itens.put(new JSONObject().put("chave", "tt:" + l.optInt("i")).put("nome", l.optString("nome")));
                }
                o.put("fonte", "web".equals(Controle.modo) ? (yt ? "youtube" : "site") : "").put("itens", itens)
                        .put("atual", p.optString("legAtual")).put("traducao", p.optString("legTrad"))
                        .put("podeBaixar", "web".equals(Controle.modo) && (yt || itens.length() > 0)).put("podeTraduzir", yt);
                return comArquivos(o).toString();
            } catch (Exception e) { return "{}"; }
        }

        /** Acrescenta os arquivos de legenda encontrados (pedidos pelo site ou informados pelo jw.org), que podem ser baixados. */
        private JSONObject comArquivos(JSONObject o) throws Exception {
            JSONArray a = new JSONArray();
            JwOrg.Midia m = jw;
            if (m != null && m.legenda != null) a.put(new JSONObject().put("chave", "jw").put("nome", "Legenda do jw.org" + (m.titulo.length() > 0 ? " — " + m.titulo : "")));
            if ("web".equals(Controle.modo)) {
                java.util.ArrayList<String[]> l = Navegador.arquivosDeLegenda();
                for (int i = 0; i < l.size(); i++) {
                    if (m != null && l.get(i)[0].equals(m.legenda)) continue;
                    a.put(new JSONObject().put("chave", "cap:" + i).put("nome", l.get(i)[1]));
                }
            }
            return o.put("arquivos", a);
        }

        /** chave: "off", "yt:idioma[:asr]", "tt:índice" ou "vlc:id". */
        @JavascriptInterface
        public void escolherLegenda(final String chave, final boolean traduzir) {
            naTela(new Runnable() { public void run() {
                if (chave.startsWith("vlc:")) { motor.escolherLegenda(Integer.parseInt(chave.substring(4))); return; }
                if ("off".equals(chave)) {
                    if ("vlc".equals(Controle.modo)) motor.escolherLegenda(-1);
                    else if (Navegador.estadoPagina.optBoolean("yt")) js(nav, Navegador.jsLegendaYoutube("", false, ""));
                    else js(nav, Navegador.jsLegendaFaixa(-1));
                    return;
                }
                if (chave.startsWith("tt:")) { js(nav, Navegador.jsLegendaFaixa(Integer.parseInt(chave.substring(3)))); return; }
                if (chave.startsWith("yt:")) {
                    String[] p = chave.substring(3).split(":");
                    final String c = Navegador.jsLegendaYoutube(p[0], p.length > 1, traduzir ? "pt" : "");
                    // Se o módulo de legendas ainda estava carregando, repete uma vez (só nesse caso, para não duplicar).
                    nav.evaluateJavascript(c, new ValueCallback<String>() { public void onReceiveValue(String r) {
                        if (r != null && r.contains("parcial")) h.postDelayed(new Runnable() { public void run() { js(nav, c); } }, 900);
                    } });
                }
            } });
        }

        /** Faixas de áudio do vídeo do YouTube (dublagens), se houver mais de uma. */
        @JavascriptInterface
        public String audios() {
            try {
                JSONObject p = Navegador.estadoPagina, o = new JSONObject();
                JSONArray itens = new JSONArray(), au = "web".equals(Controle.modo) ? p.optJSONArray("auds") : null;
                if (au != null && au.length() > 1) for (int i = 0; i < au.length(); i++) {
                    JSONObject a = au.optJSONObject(i);
                    itens.put(new JSONObject().put("chave", a.optString("id")).put("nome", a.optString("nome") + (a.optBoolean("padrao") ? " (original)" : "")));
                }
                String atual = p.optString("audAtual");
                if (atual.length() == 0 && au != null) for (int i = 0; i < au.length(); i++) if (au.optJSONObject(i).optBoolean("padrao")) atual = au.optJSONObject(i).optString("id");
                return o.put("itens", itens).put("atual", atual).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void escolherAudio(final String id) {
            naTela(new Runnable() { public void run() {
                if (nav == null) return;
                nav.evaluateJavascript(Navegador.jsAudioYoutube(id), new ValueCallback<String>() { public void onReceiveValue(String r) {
                    Diagnostico.anotar("Faixa de áudio " + id + ": " + r);
                    if (r == null || !r.contains("ok")) avisar("O YouTube não aceitou a troca de áudio por aqui. Tente pela engrenagem do próprio vídeo → Faixa de áudio.");
                } });
            } });
        }

        @JavascriptInterface
        public String dublagem() {
            try {
                Dublador d = Dublador.de(Principal.this);
                JSONObject p = Navegador.estadoPagina;
                boolean web = "web".equals(Controle.modo);
                JSONArray legs = p.optJSONArray("legs");
                JSONArray vozes = new JSONArray();
                for (String[] v : VozNeural.VOZES) vozes.put(new JSONObject().put("chave", v[0]).put("nome", v[1]));
                vozes.put(new JSONObject().put("chave", Dublador.VOZ_ANDROID).put("nome", "Voz do Android (sem internet)"));
                JSONArray modelos = new JSONArray();
                for (String[] m : Modelos.OPCOES) modelos.put(new JSONObject().put("chave", m[0]).put("nome", m[1]).put("pronto", Modelos.pronto(Principal.this, m[0])).put("cabe", Modelos.cabe(Principal.this, m[0])));
                return new JSONObject().put("ativo", d.ativo()).put("modo", d.modo()).put("estado", d.estado())
                        .put("volume", d.volumeOriginal()).put("disponivel", web).put("yt", web && p.optBoolean("yt"))
                        .put("temLegenda", web && legs != null && legs.length() > 0)
                        .put("pronta", web && d.temPronta(idVideoAtual()))
                        .put("whisper", whisperDisponivel())
                        .put("voz", d.voz()).put("vozes", vozes)
                        .put("modelo", d.modelo()).put("modelos", modelos)
                        .put("baixando", Modelos.baixando()).put("andamento", Modelos.andamento())
                        .put("velPreparo", d.velocidadePreparo()).put("idiomaFonte", d.idiomaFonte())
                        .put("nivel", d.nivelCaptura()).toString();
            } catch (Exception e) { return "{}"; }
        }

        /** modo: legenda, aovivo, preparar, pronta; vazio para parar. */
        @JavascriptInterface
        public void dublar(final String modo) {
            naTela(new Runnable() { public void run() {
                if (modo == null || modo.length() == 0) Dublador.de(Principal.this).parar("");
                else Principal.this.dublar(modo);
            } });
        }

        @JavascriptInterface
        public void volumeOriginal(final int v) { naTela(new Runnable() { public void run() { Dublador.de(Principal.this).definirVolumeOriginal(v); } }); }

        @JavascriptInterface
        public void escolherVozDublagem(final String v) { Dublador.de(Principal.this).definirVoz(v); }

        @JavascriptInterface
        public void ouvirVoz() { naTela(new Runnable() { public void run() { Dublador.de(Principal.this).ouvirExemplo(); } }); }

        @JavascriptInterface
        public void escolherModelo(final String m) { Dublador.de(Principal.this).definirModelo(m); }

        @JavascriptInterface
        public void baixarModelo() {
            final String mod = Dublador.de(Principal.this).modelo();
            if (!Modelos.pronto(Principal.this, mod)) Modelos.baixar(Principal.this, mod, null);
        }

        @JavascriptInterface
        public void apagarModelo(final String m) { if (!Modelos.baixando()) Modelos.apagar(Principal.this, m); }

        @JavascriptInterface
        public void velocidadePreparo(final double v) { Dublador.de(Principal.this).definirVelocidadePreparo(v); }

        @JavascriptInterface
        public void idiomaFonte(final String i) { Dublador.de(Principal.this).definirIdiomaFonte(i == null ? "" : i); }

        // ---- anúncios
        @JavascriptInterface
        public String anuncios() {
            try { return new JSONObject().put("ligado", ajustes.getBoolean("anuncios", true)).put("bloqueados", Anuncios.bloqueados.get()).toString(); }
            catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void bloquearAnuncios(final boolean sim) {
            naTela(new Runnable() { public void run() {
                ajustes.edit().putBoolean("anuncios", sim).apply();
                Anuncios.ligado = sim;
                if (nav != null) nav.definirAnuncios(sim);
            } });
        }

        // ---- arquivos do aparelho
        @JavascriptInterface
        public void abrirArquivo() {
            naTela(new Runnable() { public void run() { escolherArquivo(PEDIDO_ARQUIVO, new String[] { "video/*", "audio/*" }); } });
        }

        // ---- listas (m3u, m3u8, pls)
        @JavascriptInterface
        public String listas() {
            try {
                JSONArray a = new JSONArray();
                for (Listas.Lista l : Principal.this.listas().todas())
                    a.put(new JSONObject().put("id", l.id).put("nome", l.nome).put("tipo", l.tipo).put("n", l.itens.size())
                            .put("origem", l.origem.startsWith("content:") ? "arquivo do aparelho" : l.origem).put("web", l.origem.startsWith("http"))
                            .put("baixada", l.origem.startsWith("baixados:")).put("doApp", contarDoApp(l)));
                JSONArray tipos = new JSONArray();
                for (String[] t : Listas.TIPOS) tipos.put(new JSONObject().put("chave", t[0]).put("nome", t[1]));
                return new JSONObject().put("listas", a).put("tipos", tipos).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void importarLista(final String url, final String nome, final String tipo) {
            final String u = extrairLink(url == null ? "" : url);
            if (u == null) { avisarDeFora("Cole o endereço da lista (http…)."); return; }
            new Thread(new Runnable() { public void run() {
                try {
                    String texto = Listas.baixar(u);
                    String n = nome != null && nome.trim().length() > 0 ? nome : Listas.nomeDe(u).replaceAll("\\.[A-Za-z0-9]{2,5}$", "");
                    importarTexto(texto, n, u, tipo, u);
                } catch (Exception e) {
                    Diagnostico.anotar("Lista: falha ao baixar " + Diagnostico.curto(u) + ": " + e.getMessage());
                    avisarDeFora("Não consegui baixar a lista: " + e.getMessage());
                }
            } }).start();
        }

        @JavascriptInterface
        public void importarListaArquivo(final String tipo) {
            naTela(new Runnable() { public void run() {
                tipoListaPedida = tipo == null ? "" : tipo;
                escolherArquivo(PEDIDO_LISTA, new String[] { "audio/x-mpegurl", "audio/mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/x-scpls", "text/plain", "application/octet-stream" });
            } });
        }

        @JavascriptInterface
        public String itensLista(String id, String filtro, String grupo, int inicio) {
            try { return Principal.this.listas().itens(id, filtro, grupo, inicio, 120).toString(); } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void tocarItem(final String id, final int i) { naTela(new Runnable() { public void run() { tocarDaLista(id, i); } }); }

        @JavascriptInterface
        public void pularNaFila(final int dir) { naTela(new Runnable() { public void run() { proximoDaFila(dir, false); } }); }

        @JavascriptInterface
        public void editarLista(String id, String nome, String tipo) { Principal.this.listas().renomear(id, nome, tipo); }

        @JavascriptInterface
        public void apagarLista(final String id, final boolean arquivos) { naTela(new Runnable() { public void run() { Principal.this.apagarLista(id, arquivos); } }); }

        @JavascriptInterface
        public void tocarTodos(final String id, final boolean aleatorio) { naTela(new Runnable() { public void run() { Principal.this.tocarTodos(id, aleatorio); } }); }

        @JavascriptInterface
        public void modoFila(final boolean aleatorio, final String repetir) { naTela(new Runnable() { public void run() { Principal.this.modoFila(aleatorio, repetir); } }); }

        @JavascriptInterface
        public String modoFilaAtual() {
            try { return new JSONObject().put("aleatorio", ajustes.getBoolean("aleatorio", false)).put("repetir", ajustes.getString("repetir", Fila.NAO)).toString(); }
            catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void atualizarLista(String id) {
            Listas.Lista l = Principal.this.listas().porId(id);
            if (l == null) return;
            if (!l.origem.startsWith("http")) { avisarDeFora("Listas de arquivo: importe o arquivo de novo para atualizar."); return; }
            importarLista(l.origem, l.nome, l.tipo);
        }

        // ---- letra sincronizada
        @JavascriptInterface
        public String letraInfo() {
            try {
                return new JSONObject().put("ligada", letrasLigadas()).put("estado", letraEstado).put("titulo", letraTitulo).put("procurada", letraProcurada)
                        .put("iaAuto", letraIaAuto()).put("iaErro", letraIaErro).put("ia", whisperDisponivel())
                        .put("progresso", "criando".equals(letraEstado) ? Legendador.de(Principal.this).progresso() : -1)
                        .put("ajuste", letraChave.length() > 0 ? ajusteLetra() : 0)
                        .put("diferenca", letraDados != null ? letraDados.optInt("diferenca") : 0).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void letraLigar(final boolean sim) {
            naTela(new Runnable() { public void run() {
                ajustes.edit().putBoolean("letras", sim).apply();
                letraEstado = sim ? "" : "desligada";
                letraChave = "";
                letraDados = null;
                empurrarLetra();
            } });
        }

        @JavascriptInterface
        public String versao() { return VERSAO; }

        @JavascriptInterface
        public void letraCriar() { naTela(new Runnable() { public void run() { criarLetraComIA(true); } }); }

        @JavascriptInterface
        public void letraIaAutomatica(boolean sim) { ajustes.edit().putBoolean("letraIaAuto", sim).apply(); }

        /** Ajuste fino escolhido na gaveta (ms; + = letra mais cedo, - = mais tarde): guarda para esta música. */
        @JavascriptInterface
        public void letraDefinirAjuste(final int ms) {
            naTela(new Runnable() { public void run() {
                if (letraChave.length() == 0) return;
                int a = Math.max(-1000, Math.min(1000, Math.round(ms / 100f) * 100));
                ajustes.edit().putInt("letraAjuste:" + letraChave, a).apply();
                if (letraDados != null) try { letraDados.put("ajuste", a); } catch (Exception e) { }
                empurrarLetra();
            } });
        }

        // ---- animação da rádio
        @JavascriptInterface
        public String radio() { return infoRadio().toString(); }

        @JavascriptInterface
        public void animacao(final boolean sim) { naTela(new Runnable() { public void run() { ligarAnimacao(sim); } }); }

        @JavascriptInterface
        public void pedirPermissaoAnimacao() {
            naTela(new Runnable() { public void run() { ajustes.edit().putBoolean("vizPerguntou", false).apply(); ligarAnimacao(true); } });
        }

        /** Tela cheia só com a animação (some a barra do Android). */
        @JavascriptInterface
        public void telaCheiaAnimacao(final boolean sim) { naTela(new Runnable() { public void run() { imersivo(sim); } }); }

        // ---- downloads (yt-dlp)
        @JavascriptInterface
        public String downloads() {
            try {
                JSONObject o = Baixador.de(Principal.this).estado();
                String atual = "web".equals(Controle.modo) ? Navegador.enderecoAgora : "vlc".equals(Controle.modo) ? motor.url() : "";
                if (atual == null || !atual.startsWith("http")) atual = "";
                return o.put("endereco", atual).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void baixar(final String url, final String tipo, final boolean playlist) {
            naTela(new Runnable() { public void run() { Principal.this.baixar(url, tipo, playlist); } });
        }

        /** Salva no aparelho o vídeo que está tocando da pasta temporária (sem baixar de novo). */
        @JavascriptInterface
        public void salvarVideoAtual() {
            naTela(new Runnable() { public void run() {
                if (!permissaoGravar()) return;
                final String url = motor.url();
                final String titulo = motor.estado().optString("titulo");
                avisar("Salvando no aparelho…");
                new Thread(new Runnable() { public void run() {
                    final String uri = Baixador.de(Principal.this).salvarDoCache(url, titulo);
                    runOnUiThread(new Runnable() { public void run() {
                        if (uri == null) { avisar("Não consegui salvar o vídeo."); return; }
                        java.util.List<String[]> um = new java.util.ArrayList<String[]>();
                        um.add(new String[] { titulo.length() > 0 ? titulo : "Vídeo", uri });
                        guardarNaBiblioteca(null, "video", "", um);
                        avisar("Vídeo salvo em Filmes/Tocador e na Biblioteca, em \"Vídeos baixados\".");
                    } });
                } }).start();
            } });
        }

        @JavascriptInterface
        public void cancelarPreparo() { Baixador.de(Principal.this).cancelarCache(); }

        @JavascriptInterface
        public void cancelarDownload() { Baixador.de(Principal.this).cancelar(); }

        @JavascriptInterface
        public void atualizarMotor() { Baixador.de(Principal.this).atualizarMotor(); }

        // ---- legendador (Whisper)
        @JavascriptInterface
        public String legendador() {
            try {
                Legendador lg = Legendador.seExistir();
                boolean vlc = "vlc".equals(Controle.modo);
                Dublador d = Dublador.seExistir();
                boolean temDub = d != null && d.temFalas();
                JSONObject o = new JSONObject().put("disponivel", vlc && motor.temDuracao() && whisperDisponivel())
                        .put("vlc", vlc).put("whisper", whisperDisponivel()).put("dublagem", temDub);
                if (lg != null) {
                    boolean doAtual = lg.url().equals(motor.url());
                    o.put("trabalhando", lg.trabalhando()).put("estado", doAtual || lg.trabalhando() ? lg.estado() : "")
                            .put("progresso", lg.progresso()).put("pronta", doAtual && lg.temLegenda()).put("traduzida", lg.traduzida());
                }
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void legendar(final boolean traduzir) { naTela(new Runnable() { public void run() { Principal.this.legendar(traduzir); } }); }

        @JavascriptInterface
        public void cancelarLegenda() { Legendador lg = Legendador.seExistir(); if (lg != null) lg.cancelar(); }

        /** origem: "legendador" ou "dublagem"; formato: srt ou txt; original: no idioma do vídeo. */
        @JavascriptInterface
        public void baixarLegendaGerada(final String origem, final String formato, final boolean original) {
            naTela(new Runnable() { public void run() {
                String texto, nome;
                if ("dublagem".equals(origem)) {
                    Dublador d = Dublador.seExistir();
                    texto = d == null ? null : d.legenda(formato, original);
                    nome = Navegador.tituloAgora;
                } else {
                    Legendador lg = Legendador.seExistir();
                    texto = lg == null ? null : lg.texto(formato, original);
                    nome = lg == null ? "" : lg.titulo();
                }
                nome = (nome == null || nome.length() == 0 ? "Legenda" : nome) + (original ? " (original)" : " (português)");
                baixarTextoGerado(nome, formato, texto);
            } });
        }

        /** Abre o ajuste de voz do Android (escolher o motor e baixar a voz em português). */
        @JavascriptInterface
        public void escolherVoz() {
            naTela(new Runnable() { public void run() {
                try { startActivity(new Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception x) { } }
            } });
        }

        @JavascriptInterface
        public void baixarLegenda(final String chave, final boolean traduzir) {
            naTela(new Runnable() { public void run() { Principal.this.baixarLegenda(chave, traduzir); } });
        }

        @JavascriptInterface
        public void abrirLegendaSalva() {
            naTela(new Runnable() { public void run() {
                Uri u = Arquivos.ultimo;
                if (u == null) return;
                try {
                    startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW).setDataAndType(u, "text/plain")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Abrir a legenda com…"));
                } catch (Exception e) { avisar("Nenhum app para abrir texto foi encontrado."); }
            } });
        }

        @JavascriptInterface
        public String qualidades() {
            try {
                JSONObject o = new JSONObject();
                JSONArray itens = new JSONArray();
                if ("vlc".equals(Controle.modo)) {
                    itens.put(new JSONObject().put("chave", "max").put("nome", "Máxima"));
                    itens.put(new JSONObject().put("chave", "auto").put("nome", "Automática (conforme a conexão)"));
                    o.put("fonte", "vlc").put("atual", motor.qualidadeMaxima() ? "max" : "auto");
                } else {
                    JSONObject p = Navegador.estadoPagina;
                    o.put("fonte", p.optBoolean("yt") ? "youtube" : "site");
                    if (p.optBoolean("yt")) {
                        itens.put(new JSONObject().put("chave", "max").put("nome", "Máxima disponível"));
                        itens.put(new JSONObject().put("chave", "auto").put("nome", "Automática (conforme a conexão)"));
                        JSONArray qs = p.optJSONArray("qs");
                        if (qs != null) for (int i = 0; i < qs.length(); i++) {
                            String q = qs.optString(i);
                            if (!"auto".equals(q)) itens.put(new JSONObject().put("chave", q).put("nome", nomeQualidade(q)));
                        }
                        o.put("emUso", nomeQualidade(p.optString("q")));
                    }
                    o.put("atual", ajustes.getString("qualidade", "max"));
                    o.put("computador", nav != null && nav.computador());
                }
                o.put("relato", "web".equals(Controle.modo) ? Navegador.relatoQualidade : "");
                o.put("jwEstado", jwEstado);
                JwOrg.Midia m = jw;
                if (m != null && !m.arquivos.isEmpty()) {
                    JSONArray ji = new JSONArray();
                    for (int i = 0; i < m.arquivos.size(); i++) ji.put(new JSONObject().put("chave", "jw:" + i).put("nome", m.arquivos.get(i)[0]));
                    o.put("jw", new JSONObject().put("titulo", m.titulo).put("itens", ji).put("legenda", m.legenda != null));
                }
                return o.put("itens", itens).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void escolherQualidade(final String q) {
            naTela(new Runnable() { public void run() {
                if (q.startsWith("jw:")) { tocarJw(Integer.parseInt(q.substring(3))); return; }
                if ("vlc".equals(Controle.modo)) { motor.definirQualidadeMaxima("max".equals(q)); return; }
                ajustes.edit().putString("qualidade", q).apply();
                if (nav != null) nav.definirQualidade(q);
            } });
        }

        @JavascriptInterface
        public void modoComputador(final boolean sim) {
            naTela(new Runnable() { public void run() {
                ajustes.edit().putBoolean("computador", sim).apply();
                if (nav != null) nav.definirComputador(sim);
            } });
        }

        @JavascriptInterface
        public String eqInfo() {
            return ("web".equals(Controle.modo) ? EqSistema.de(Principal.this).info() : motor.eqInfo()).toString();
        }

        @JavascriptInterface
        public void eqBanda(final int i, final float db) {
            naTela(new Runnable() { public void run() {
                if ("web".equals(Controle.modo)) EqSistema.de(Principal.this).banda(i, db); else motor.eqBanda(i, db);
            } });
        }

        @JavascriptInterface
        public void eqPreamp(final float db) {
            naTela(new Runnable() { public void run() { if (!"web".equals(Controle.modo)) motor.eqPreamp(db); } });
        }

        @JavascriptInterface
        public void eqPreset(final int p) {
            naTela(new Runnable() { public void run() {
                if ("web".equals(Controle.modo)) EqSistema.de(Principal.this).preset(p); else motor.eqPreset(p);
            } });
        }

        @JavascriptInterface
        public void eqLigar(final boolean l) {
            naTela(new Runnable() { public void run() {
                if ("web".equals(Controle.modo)) EqSistema.de(Principal.this).ligar(l); else motor.eqLigar(l);
            } });
        }

        @JavascriptInterface
        public String colar() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData c = cm.getPrimaryClip();
                if (c == null || c.getItemCount() == 0) return "";
                CharSequence t = c.getItemAt(0).coerceToText(Principal.this);
                if (t == null) return "";
                String l = extrairLink(t.toString());
                return l != null ? l : t.toString();
            } catch (Exception e) { return ""; }
        }

        /** O Tocador já está liberado da economia de bateria? */
        @JavascriptInterface
        public boolean bateriaLiberada() { return semRestricaoBateria(); }

        /** Pede a liberação; se já estiver liberado, abre a lista do sistema (de onde dá para desfazer). */
        @JavascriptInterface
        public void bateria() {
            naTela(new Runnable() { public void run() {
                if (!semRestricaoBateria()) { pedirSemRestricaoBateria(); return; }
                try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception x) { } }
            } });
        }

        /** Copia o diagnóstico para a área de transferência (para colar na conversa de suporte). */
        @JavascriptInterface
        public void copiarDiagnostico() {
            naTela(new Runnable() { public void run() {
                String t = Diagnostico.texto() + "\nVersão: " + VERSAO + " · Android " + Build.VERSION.RELEASE + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · modo=" + Controle.modo;
                ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Diagnóstico do Tocador", t));
            } });
        }
    }
}
