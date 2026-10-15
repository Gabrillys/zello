package app.tocador;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

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
    private static final Pattern LINK = Pattern.compile("(?i)\\b(https?|rtsp|rtmp|mms)://[^\\s\"'<>]+");

    private final Handler h = new Handler(Looper.getMainLooper());
    private Motor motor;
    private FrameLayout raiz, area, telaCheiaBox;
    private LinearLayout coluna;
    private SurfaceView superficie;
    private Navegador nav;
    private WebView painel;
    private WebChromeClient.CustomViewCallback cbTelaCheia;
    private volatile boolean expandido;
    private String layoutAtual = "";

    // Vídeo do YouTube/Instagram descoberto pelo Extrator (para tocar no VLC e baixar).
    private volatile Extrator.Resultado extra;
    private volatile String extraEstado = "";   // "", buscando, pronto, erro
    private volatile String extraFonte = "";
    private volatile String extraChave = "";
    private volatile String aviso = "";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(0xFF0E1116);
        getWindow().setNavigationBarColor(0xFF0E1116);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);

        motor = Motor.de(this);

        raiz = new FrameLayout(this);
        raiz.setBackgroundColor(0xFF0E1116);
        coluna = new LinearLayout(this);
        coluna.setOrientation(LinearLayout.VERTICAL);
        raiz.addView(coluna, new FrameLayout.LayoutParams(-1, -1));

        area = new FrameLayout(this);
        area.setBackgroundColor(0xFF000000);
        superficie = new SurfaceView(this);
        area.addView(superficie, new FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER));
        superficie.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int bo, int ol, int ot, int or, int ob) {
                motor.tamanho(r - l, bo - t);
            }
        });
        coluna.addView(area, new LinearLayout.LayoutParams(-1, 0));

        painel = new WebView(this);
        painel.setBackgroundColor(0xFF0E1116);
        painel.getSettings().setJavaScriptEnabled(true);
        painel.getSettings().setDomStorageEnabled(true);
        painel.addJavascriptInterface(new Ponte(), "Tocador");
        painel.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        coluna.addView(painel, new LinearLayout.LayoutParams(-1, 0, 1f));
        painel.loadUrl("file:///android_asset/painel.html");

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
        aviso = "";
        // Instagram: o site pede login; busca o vídeo direto e toca no player VLC.
        if (!"web".equals(modo) && Extrator.ehInstagram(url)) { abrirInstagram(url); return; }
        if ("auto".equals(modo)) modo = decidirModo(url);
        if ("vlc".equals(modo)) {
            if (Controle.modo.equals("web")) Navegador.comandar("pausar");
            EqSistema.de(this).soltar();
            Controle.modo = "vlc";
            motor.anexar(superficie, superficie.getWidth(), superficie.getHeight());
            motor.tocar(url, null, null);
        } else {
            motor.parar();
            Controle.modo = "web";
            if (nav == null) criarNavegador();
            nav.loadUrl(url);
            verificarYoutube(url);
        }
        Servico.iniciar(this);
        ajustar();
    }

    private void abrirInstagram(final String url) {
        extra = null; extraFonte = "instagram"; extraEstado = "buscando"; extraChave = url;
        aviso = "Buscando o vídeo do Instagram…";
        final String cookies = CookieManager.getInstance().getCookie("https://www.instagram.com");
        new Thread(new Runnable() { public void run() {
            final Extrator.Resultado r = Extrator.instagram(url, cookies);
            runOnUiThread(new Runnable() { public void run() {
                if (!url.equals(extraChave)) return; // a pessoa já abriu outro link
                extra = r;
                if (r.ok()) {
                    extraEstado = "pronto"; aviso = "";
                    if (Controle.modo.equals("web")) Navegador.comandar("pausar");
                    EqSistema.de(Principal.this).soltar();
                    Controle.modo = "vlc";
                    motor.anexar(superficie, superficie.getWidth(), superficie.getHeight());
                    motor.tocar(r.video, r.agente, r.referer, r.audioSeparado);
                    motor.definirTitulo(r.titulo);
                    if (r.semSom) aviso = "Este post do Instagram não tem som (o próprio Instagram removeu o áudio ou ele foi publicado assim).";
                    Servico.iniciar(Principal.this);
                    ajustar();
                } else {
                    extraEstado = "erro";
                    // Abre a página normal: se for preciso, a pessoa entra na conta uma vez e o app passa a usar esse login.
                    abrir(url, "web");
                    aviso = r.erro + (r.precisaLogin ? " Entre na sua conta do Instagram aqui na página (só uma vez) e depois toque em Tentar de novo." : "");
                }
            } });
        } }, "tocador-instagram").start();
    }

    /** Ao abrir um vídeo do YouTube, procura em segundo plano os arquivos para baixar/tocar no VLC. */
    private void verificarYoutube(final String url) {
        final String id = Extrator.idYoutube(url);
        if (id == null) {
            if ("youtube".equals(extraFonte)) { extra = null; extraEstado = ""; extraFonte = ""; extraChave = ""; }
            return;
        }
        if (id.equals(extraChave) && !"erro".equals(extraEstado)) return;
        extra = null; extraFonte = "youtube"; extraEstado = "buscando"; extraChave = id;
        new Thread(new Runnable() { public void run() {
            final Extrator.Resultado r = Extrator.youtube(url);
            runOnUiThread(new Runnable() { public void run() {
                if (!id.equals(extraChave)) return;
                extra = r;
                extraEstado = r.ok() ? "pronto" : "erro";
            } });
        } }, "tocador-youtube").start();
    }

    private void criarNavegador() {
        nav = new Navegador(this);
        nav.definirAoMudar(new Navegador.AoMudar() {
            public void pagina(final String url) { runOnUiThread(new Runnable() { public void run() { verificarYoutube(url); } }); }
        });
        nav.definirTelaCheia(new Navegador.TelaCheia() {
            public void mostrar(View v, WebChromeClient.CustomViewCallback cb) {
                cbTelaCheia = cb;
                telaCheiaBox.addView(v, new FrameLayout.LayoutParams(-1, -1));
                telaCheiaBox.setVisibility(View.VISIBLE);
                telaCheiaBox.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
            public void esconder() { sairTelaCheia(); }
        });
        area.addView(nav, new FrameLayout.LayoutParams(-1, -1));
    }

    private void sairTelaCheia() {
        if (telaCheiaBox.getVisibility() != View.VISIBLE) return;
        telaCheiaBox.removeAllViews();
        telaCheiaBox.setVisibility(View.GONE);
        telaCheiaBox.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (cbTelaCheia != null) { cbTelaCheia.onCustomViewHidden(); cbTelaCheia = null; }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Distribui a tela entre vídeo/página e o painel de controle. */
    private void ajustar() {
        boolean deitado = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        String modo = Controle.modo;
        boolean video = "vlc".equals(modo) && motor.temVideoAgora();
        String chave = modo + video + expandido + deitado + raiz.getWidth();
        if (chave.equals(layoutAtual)) return;
        layoutAtual = chave;

        int largura = raiz.getWidth() > 0 ? raiz.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int altura = raiz.getHeight() > 0 ? raiz.getHeight() : getResources().getDisplayMetrics().heightPixels;
        LinearLayout.LayoutParams pa, pp;
        superficie.setVisibility("vlc".equals(modo) ? View.VISIBLE : View.GONE);
        if (nav != null) nav.setVisibility("web".equals(modo) ? View.VISIBLE : View.GONE);

        if ("web".equals(modo)) {
            area.setVisibility(View.VISIBLE);
            pa = new LinearLayout.LayoutParams(-1, 0, 1f);
            pp = new LinearLayout.LayoutParams(-1, expandido ? (int) (altura * 0.62) : dp(deitado ? 84 : 96));
        } else if (video) {
            area.setVisibility(View.VISIBLE);
            if (deitado && !expandido) {
                pa = new LinearLayout.LayoutParams(-1, 0, 1f);
                pp = new LinearLayout.LayoutParams(-1, dp(84));
            } else {
                pa = new LinearLayout.LayoutParams(-1, largura * 9 / 16);
                pp = new LinearLayout.LayoutParams(-1, 0, 1f);
            }
        } else {
            // Só áudio (rádio, música) ou nada aberto: o painel ocupa tudo.
            // A superfície fica com 1px para o VLC poder detectar vídeo quando ele aparecer.
            area.setVisibility(View.VISIBLE);
            pa = new LinearLayout.LayoutParams(-1, 1);
            pp = new LinearLayout.LayoutParams(-1, 0, 1f);
        }
        area.setLayoutParams(pa);
        painel.setLayoutParams(pp);
    }

    private final Runnable ciclo = new Runnable() {
        public void run() {
            ajustar();
            boolean telaLigada = "vlc".equals(Controle.modo) && motor.temVideoAgora() && motor.tocando();
            if (telaLigada) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            h.postDelayed(this, 1000);
        }
    };

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        layoutAtual = "";
        ajustar();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if ("vlc".equals(Controle.modo)) motor.anexar(superficie, superficie.getWidth(), superficie.getHeight());
    }

    @Override
    protected void onStop() {
        // Tela apagada / outro app na frente: o vídeo do VLC sai, o som continua.
        // O navegador NÃO é pausado de propósito, para o som do site continuar.
        if ("vlc".equals(Controle.modo)) motor.desanexar();
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (telaCheiaBox.getVisibility() == View.VISIBLE) { sairTelaCheia(); return; }
        if (expandido) { expandido = false; ajustar(); painel.evaluateJavascript("window.recolhido&&recolhido()", null); return; }
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
        super.onDestroy();
    }

    // ------------------------------------------------------------ ponte com o painel (assets/painel.html)

    private void naTela(Runnable r) { runOnUiThread(r); }

    /** Android 9 ou anterior precisa de permissão para gravar na pasta Download. */
    private boolean permissaoGravar() {
        if (Build.VERSION.SDK_INT >= 29) return true;
        if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED) return true;
        requestPermissions(new String[] { "android.permission.WRITE_EXTERNAL_STORAGE" }, 2);
        painel.evaluateJavascript("window.avisar&&avisar('Permita o acesso aos arquivos e toque em baixar de novo.')", null);
        return false;
    }

    final class Ponte {

        @JavascriptInterface
        public void abrir(final String url, final String modo) {
            naTela(new Runnable() { public void run() { Principal.this.abrir(url, modo == null ? "auto" : modo); } });
        }

        @JavascriptInterface
        public void tocarPausar() {
            naTela(new Runnable() { public void run() { Controle.tocarPausar(Principal.this); } });
        }

        @JavascriptInterface
        public void parar() {
            naTela(new Runnable() { public void run() { Controle.parar(Principal.this); } });
        }

        @JavascriptInterface
        public void buscar(final float pos) {
            naTela(new Runnable() { public void run() { motor.buscar(pos); } });
        }

        @JavascriptInterface
        public void volume(final int v) {
            naTela(new Runnable() { public void run() { motor.volume(v); } });
        }

        @JavascriptInterface
        public String estado() {
            JSONObject o;
            try {
                if ("vlc".equals(Controle.modo)) o = motor.estado();
                else {
                    o = new JSONObject();
                    if ("web".equals(Controle.modo)) {
                        o.put("situacao", Navegador.tocandoAgora ? "tocando" : "pausado");
                        o.put("tocando", Navegador.tocandoAgora);
                        o.put("titulo", Navegador.tituloAgora);
                    } else o.put("situacao", "parado");
                }
                o.put("modo", Controle.modo);
                o.put("expandido", expandido);
                o.put("podeBaixar", "vlc".equals(Controle.modo) && motor.temDuracao() && motor.url().matches("(?i)^https?://.*"));
                o.put("downloads", Baixador.lista());
                o.put("aviso", aviso);
                Extrator.Resultado x = extra;
                if (extraEstado.length() > 0) {
                    JSONObject e = new JSONObject();
                    e.put("fonte", extraFonte);
                    e.put("estado", extraEstado);
                    if ("instagram".equals(extraFonte)) e.put("url", extraChave);
                    if (x != null) {
                        e.put("titulo", x.titulo); e.put("erro", x.erro == null ? "" : x.erro);
                        e.put("video", x.video != null || x.hls != null); e.put("audio", x.audio != null);
                        e.put("obs", x.obs == null ? "" : x.obs);
                    }
                    o.put("extra", e);
                }
                o.put("temPagina", nav != null);
                Navegador n = nav;
                if (n != null) o.put("midias", n.listaMidias());
            } catch (Exception e) { o = new JSONObject(); }
            return o.toString();
        }

        @JavascriptInterface
        public void tocarMidia(final int i) {
            naTela(new Runnable() {
                public void run() {
                    if (nav == null) return;
                    String[] m = nav.midia(i);
                    if (m == null) return;
                    Navegador.comandar("pausar");
                    EqSistema.de(Principal.this).soltar();
                    Controle.modo = "vlc";
                    motor.anexar(superficie, superficie.getWidth(), superficie.getHeight());
                    motor.tocar(m[0], nav.agente(), m[1]);
                    Servico.iniciar(Principal.this);
                    ajustar();
                }
            });
        }

        /** Baixa o que está tocando no player VLC (só se não for ao vivo). */
        @JavascriptInterface
        public void baixarAtual() {
            naTela(new Runnable() { public void run() {
                if (!"vlc".equals(Controle.modo) || !motor.temDuracao()) return;
                if (!permissaoGravar()) return;
                String u = motor.url();
                Baixador.baixar(Principal.this, u, motor.titulo(), motor.agente(), motor.referer(), CookieManager.getInstance().getCookie(u));
            } });
        }

        /** Baixa a mídia número i encontrada na página aberta. */
        @JavascriptInterface
        public void baixarMidia(final int i) {
            naTela(new Runnable() { public void run() {
                if (nav == null) return;
                String[] m = nav.midia(i);
                if (m == null || !permissaoGravar()) return;
                Baixador.baixar(Principal.this, m[0], Navegador.tituloAgora, nav.agente(), m[1], CookieManager.getInstance().getCookie(m[0]));
            } });
        }

        /** Baixa o vídeo (com som) ou só o áudio encontrado pelo Extrator. */
        @JavascriptInterface
        public void extraBaixar(final String tipo) {
            naTela(new Runnable() { public void run() {
                Extrator.Resultado x = extra;
                if (x == null || !permissaoGravar()) return;
                String u = "audio".equals(tipo) ? x.audio : (x.video != null ? x.video : x.hls);
                if (u == null) return;
                boolean soAudio = "audio".equals(tipo);
                String ua = soAudio && x.agenteAudio != null ? x.agenteAudio : u.equals(x.hls) && x.agenteHls != null ? x.agenteHls : x.agente;
                Diagnostico.anotar("Download pedido: " + (soAudio ? "áudio " : "vídeo ") + Diagnostico.curto(u));
                Baixador.baixar(Principal.this, u, x.titulo + (soAudio ? " (áudio)" : ""), ua, x.referer, null,
                        soAudio || u.equals(x.hls) ? null : x.audioSeparado);
            } });
        }

        /** Toca o áudio do vídeo do YouTube no player VLC (equalizador completo). */
        @JavascriptInterface
        public void extraTocar() {
            naTela(new Runnable() { public void run() {
                Extrator.Resultado x = extra;
                if (x == null) return;
                // Instagram (imagem e som separados): toca os dois; YouTube: só o som, na melhor qualidade.
                boolean junto = x.audioSeparado != null && x.video != null;
                String u = junto ? x.video : x.audio != null ? x.audio : x.video != null ? x.video : x.hls;
                if (u == null) return;
                if (Controle.modo.equals("web")) Navegador.comandar("pausar");
                EqSistema.de(Principal.this).soltar();
                Controle.modo = "vlc";
                motor.anexar(superficie, superficie.getWidth(), superficie.getHeight());
                String ua = u.equals(x.audio) && x.agenteAudio != null ? x.agenteAudio : u.equals(x.hls) && x.agenteHls != null ? x.agenteHls : x.agente;
                motor.tocar(u, ua, x.referer, junto ? x.audioSeparado : null);
                motor.definirTitulo(x.titulo);
                Servico.iniciar(Principal.this);
                ajustar();
            } });
        }

        /** Copia o diagnóstico para a área de transferência (para colar na conversa de suporte). */
        @JavascriptInterface
        public void copiarDiagnostico() {
            naTela(new Runnable() { public void run() {
                String t = Diagnostico.texto() + "\nVersão: 1.4 · Android " + Build.VERSION.RELEASE + " · " + Build.MANUFACTURER + " " + Build.MODEL + " · modo=" + Controle.modo;
                ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Diagnóstico do Tocador", t));
            } });
        }

        /** Manda o link atual para outro app (ex.: NewPipe ou Seal, para baixar do YouTube). */
        @JavascriptInterface
        public void compartilharLink() {
            naTela(new Runnable() { public void run() {
                String u = "web".equals(Controle.modo) && nav != null ? nav.getUrl() : motor.url();
                if (u == null || u.length() == 0) return;
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u);
                startActivity(Intent.createChooser(i, "Enviar o link para…"));
            } });
        }

        @JavascriptInterface
        public void cancelarDownload(int id) { Baixador.cancelar(id); }

        @JavascriptInterface
        public void limparDownloads() { Baixador.limparConcluidos(); }

        @JavascriptInterface
        public void abrirDownloads() {
            naTela(new Runnable() { public void run() {
                try { startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { }
            } });
        }

        @JavascriptInterface
        public void voltarPagina() {
            naTela(new Runnable() {
                public void run() {
                    if (nav == null) return;
                    motor.parar();
                    Controle.modo = "web";
                    Servico.iniciar(Principal.this);
                    ajustar();
                }
            });
        }

        @JavascriptInterface
        public void expandir(final boolean e) {
            naTela(new Runnable() { public void run() { expandido = e; ajustar(); } });
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

        @JavascriptInterface
        public void bateria() {
            naTela(new Runnable() { public void run() {
                try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception x) { } }
            } });
        }
    }
}
