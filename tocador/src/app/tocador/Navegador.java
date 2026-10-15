package app.tocador;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Navegador para links de redes sociais (YouTube, Instagram, TikTok, Facebook, X…).
 * O vídeo é tocado pelo player do próprio site; o app só impede que o site perceba
 * que a tela apagou ou que o app foi para segundo plano, para o som continuar.
 * Também anota links diretos de mídia que a página carregar, para tocar no VLC.
 */
public class Navegador extends WebView {

    public interface TelaCheia { void mostrar(View v, WebChromeClient.CustomViewCallback cb); void esconder(); }

    static volatile boolean tocandoAgora;
    static volatile String tituloAgora = "";
    static volatile int midiasNaPagina;
    private static Navegador atual;

    private static final Pattern MIDIA = Pattern.compile(
            "(?i)^[^?#]+\\.(m3u8|mp4|m4a|m4v|mp3|aac|ogg|oga|opus|webm|mpd|flac)([?#].*)?$");
    private static final Pattern IGNORAR = Pattern.compile(
            "(?i)(doubleclick|googlesyndication|googleadservices|adservice|/ads?/|imasdk|adsystem|moatads|scorecardresearch)");

    /** Faz o site acreditar que está sempre visível e em foco. */
    static final String JS_SEMPRE_VISIVEL =
            "(function(){if(window.__tocador)return;window.__tocador=1;" +
            "function def(o,k,v){try{Object.defineProperty(o,k,{get:function(){return v},configurable:true})}catch(e){}}" +
            "def(document,'hidden',false);def(document,'webkitHidden',false);" +
            "def(document,'visibilityState','visible');def(document,'webkitVisibilityState','visible');" +
            "try{document.hasFocus=function(){return true}}catch(e){}" +
            "['visibilitychange','webkitvisibilitychange','pagehide','blur','freeze'].forEach(function(t){" +
            "window.addEventListener(t,function(e){e.stopImmediatePropagation()},true);" +
            "document.addEventListener(t,function(e){e.stopImmediatePropagation()},true)});" +
            "})();";

    private static final String JS_ESTADO =
            "(function(){var m=[].slice.call(document.querySelectorAll('video,audio'));var t=false;" +
            "for(var i=0;i<m.length;i++){if(!m[i].paused&&!m[i].ended&&m[i].readyState>1)t=true}" +
            "return {t:t,n:m.length,titulo:document.title||''}})()";

    private static String jsComando(String acao) {
        return "(function(a){var m=[].slice.call(document.querySelectorAll('video,audio'));" +
                "var p=m.filter(function(x){return !x.paused&&!x.ended});" +
                "if(a==='pausar'||(a==='alternar'&&p.length)){p.forEach(function(x){x.__tocadorPausou=1;x.pause()});return 'pausado'}" +
                "var alvo=m.filter(function(x){return x.__tocadorPausou})[0]||p[0]||m[0];" +
                "if(alvo){var r=alvo.play();if(r&&r.catch)r.catch(function(){});return 'tocando'}return ''})('" + acao + "')";
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private final ArrayList<String[]> midias = new ArrayList<String[]>(); // {url, referer}
    private final String agente;
    private String paginaAtual = "";
    private TelaCheia telaCheia;

    @SuppressLint("SetJavaScriptEnabled")
    public Navegador(Context c) {
        super(c);
        atual = this;
        WebSettings s = getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Sem a marca "wv", vários sites tratam o app como o Chrome comum (login, player completo).
        agente = s.getUserAgentString().replace("; wv", "").replaceAll("Version/[0-9.]+ ", "");
        s.setUserAgentString(agente);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true);
        if (Build.VERSION.SDK_INT >= 26) setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false);
        setBackgroundColor(0xFF000000);

        setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, Bitmap f) {
                paginaAtual = url;
                synchronized (midias) { midias.clear(); }
                midiasNaPagina = 0;
                v.evaluateJavascript(JS_SEMPRE_VISIVEL, null);
            }
            @Override public void onPageFinished(WebView v, String url) {
                paginaAtual = url;
                v.evaluateJavascript(JS_SEMPRE_VISIVEL, null);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String esq = r.getUrl().getScheme();
                // Links "intent:", "vnd.youtube:" etc. abririam o app oficial; ficamos no navegador.
                return esq == null || !(esq.equals("http") || esq.equals("https"));
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                anotar(r.getUrl());
                return null;
            }
        });

        setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback cb) { if (telaCheia != null) telaCheia.mostrar(view, cb); }
            @Override public void onHideCustomView() { if (telaCheia != null) telaCheia.esconder(); }
            @Override public void onReceivedTitle(WebView v, String t) { tituloAgora = t == null ? "" : t; }
        });

        h.postDelayed(consulta, 1500);
    }

    public void definirTelaCheia(TelaCheia t) { telaCheia = t; }
    public String agente() { return agente; }

    /** O site nunca fica sabendo que a janela sumiu (tela apagada, outro app na frente). */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(View.VISIBLE);
    }

    private void anotar(Uri u) {
        String s = u.toString();
        if (!MIDIA.matcher(s).matches() || IGNORAR.matcher(s).find()) return;
        String chave = s.indexOf('?') > 0 ? s.substring(0, s.indexOf('?')) : s;
        synchronized (midias) {
            for (String[] m : midias) {
                String k = m[0].indexOf('?') > 0 ? m[0].substring(0, m[0].indexOf('?')) : m[0];
                if (k.equals(chave)) return;
            }
            midias.add(new String[] { s, paginaAtual });
            if (midias.size() > 15) midias.remove(0);
            midiasNaPagina = midias.size();
        }
    }

    public JSONArray listaMidias() {
        JSONArray a = new JSONArray();
        synchronized (midias) {
            for (String[] m : midias) {
                JSONObject o = new JSONObject();
                try {
                    Uri u = Uri.parse(m[0]);
                    String p = u.getLastPathSegment();
                    o.put("host", u.getHost());
                    o.put("arquivo", p == null ? "" : p);
                } catch (Exception e) { }
                a.put(o);
            }
        }
        return a;
    }

    /** Devolve {url, referer} da mídia número i, ou null. */
    public String[] midia(int i) {
        synchronized (midias) { return i >= 0 && i < midias.size() ? midias.get(i) : null; }
    }

    private final Runnable consulta = new Runnable() {
        public void run() {
            evaluateJavascript(JS_SEMPRE_VISIVEL, null);
            evaluateJavascript(JS_ESTADO, new ValueCallback<String>() {
                public void onReceiveValue(String v) {
                    try {
                        JSONObject o = new JSONObject(v);
                        boolean antes = tocandoAgora;
                        tocandoAgora = o.optBoolean("t");
                        String t = o.optString("titulo");
                        if (t.length() > 0) tituloAgora = t;
                        if (antes != tocandoAgora) Servico.atualizar(getContext());
                    } catch (Exception e) { }
                }
            });
            h.postDelayed(this, 1500);
        }
    };

    /** Executa tocar / pausar / alternar no player da página aberta. */
    static void comandar(final String acao) {
        final Navegador n = atual;
        if (n == null) return;
        n.h.post(new Runnable() {
            public void run() {
                n.evaluateJavascript(jsComando(acao), new ValueCallback<String>() {
                    public void onReceiveValue(String v) {
                        if (v != null && v.contains("tocando")) tocandoAgora = true;
                        else if (v != null && v.contains("pausado")) tocandoAgora = false;
                        Servico.atualizar(n.getContext());
                    }
                });
            }
        });
    }

    @Override
    public void destroy() {
        h.removeCallbacks(consulta);
        if (atual == this) atual = null;
        tocandoAgora = false;
        super.destroy();
    }
}
