package app.tocador;

import android.net.Uri;
import android.os.Build;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bloqueio de anúncios no modo site (principalmente YouTube), em três camadas:
 *  1. pedidos para servidores de anúncio são recusados;
 *  2. a lista de anúncios que o YouTube manda junto com o vídeo é apagada antes de o player ler;
 *  3. se ainda assim um anúncio começar, ele fica mudo, é adiantado até o fim e o "Pular" é tocado.
 * O YouTube muda isso com frequência; a camada 3 segura a maior parte dos casos.
 */
final class Anuncios {

    private Anuncios() { }

    static volatile boolean ligado = true;
    static final AtomicInteger bloqueados = new AtomicInteger();

    private static final String[] DOMINIOS = {
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
        "adservice.google.com", "imasdk.googleapis.com", "pubads.g.doubleclick.net", "securepubads.g.doubleclick.net",
        "2mdn.net", "adnxs.com", "amazon-adsystem.com", "taboola.com", "outbrain.com", "criteo.com", "criteo.net",
        "adsrvr.org", "pubmatic.com", "rubiconproject.com", "openx.net", "moatads.com", "scorecardresearch.com",
        "advertising.com", "smartadserver.com", "teads.tv", "popads.net", "propellerads.com", "exoclick.com",
    };

    private static final String[] CAMINHOS_YOUTUBE = {
        "/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_info", "/youtubei/v1/player/ad_break", "/pcs/activeview",
        "/generate_204?ad", "/ad_data_204",
    };

    /** O pedido é de anúncio? (só olha o endereço; nada é baixado) */
    static boolean ehAnuncio(Uri u) {
        if (!ligado || u == null) return false;
        String host = u.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String d : DOMINIOS) if (host.equals(d) || host.endsWith("." + d)) return true;
        if (host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com")) {
            String p = u.getPath() == null ? "" : u.getPath();
            String q = u.getEncodedQuery() == null ? "" : u.getEncodedQuery();
            for (String c : CAMINHOS_YOUTUBE) if ((p + "?" + q).contains(c)) return true;
        }
        return false;
    }

    /** Resposta vazia no lugar do anúncio. */
    static WebResourceResponse vazio() {
        bloqueados.incrementAndGet();
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        if (Build.VERSION.SDK_INT >= 21) try { r.setStatusCodeAndReasonPhrase(204, "No Content"); } catch (Exception e) { }
        return r;
    }

    /** Camadas 2 e 3, rodando dentro da página. Desliga sozinho quando window.__tocAdOff = true. */
    static final String JS = "(function(){if(window.__tocAd)return;window.__tocAd=1;" +
            "var K=['adPlacements','adSlots','playerAds','adBreakHeartbeatParams','adBreakParams'];" +
            "function limpar(o,d){try{if(!o||typeof o!=='object'||window.__tocAdOff||(d|0)>6)return o;" +
            " for(var i=0;i<K.length;i++)if(K[i] in o){delete o[K[i]];window.__tocAdN=(window.__tocAdN|0)+1;}" +
            " if(o.playerResponse)limpar(o.playerResponse,(d|0)+1);if(o.response)limpar(o.response,(d|0)+1);" +
            " if(Array.isArray(o))for(var j=0;j<o.length&&j<20;j++)limpar(o[j],(d|0)+1);}catch(e){}return o;}" +
            "var P=JSON.parse;JSON.parse=function(){return limpar(P.apply(this,arguments));};" +
            "try{var RJ=Response.prototype.json;Response.prototype.json=function(){return RJ.apply(this,arguments).then(function(x){return limpar(x);});};}catch(e){}" +
            "['ytInitialPlayerResponse','ytInitialData'].forEach(function(n){var v=window[n];try{Object.defineProperty(window,n,{configurable:true," +
            " get:function(){return v},set:function(x){v=limpar(x)}});if(v)limpar(v);}catch(e){}});" +
            "var mutou=false;setInterval(function(){if(window.__tocAdOff)return;" +
            " var p=document.getElementById('movie_player')||document.querySelector('.html5-video-player');" +
            " var v=p&&p.querySelector('video');" +
            " if(p&&p.classList.contains('ad-showing')&&v){if(!v.muted){v.muted=true;mutou=true;}" +
            "  if(isFinite(v.duration)&&v.duration>0&&v.currentTime<v.duration-0.3){try{v.currentTime=v.duration-0.1;}catch(e){}window.__tocAdN=(window.__tocAdN|0)+1;}" +
            "  var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button,.ytm-skip-ad-button');if(b)b.click();}" +
            " else if(mutou&&v){v.muted=false;mutou=false;}" +
            " var o=document.querySelector('.ytp-ad-overlay-close-button');if(o)o.click();},250);" +
            "var css='ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-banner-promo-renderer,ytd-promoted-video-renderer,ytd-display-ad-renderer," +
            "ytd-promoted-sparkles-web-renderer,ytd-companion-slot-renderer,ytd-player-legacy-desktop-watch-ads-renderer,#masthead-ad,#player-ads," +
            "ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,ytm-promoted-video-renderer,ad-slot-renderer,ytm-ad-slot-renderer," +
            ".ytp-ad-overlay-container,.ytp-ad-image-overlay,ytd-merch-shelf-renderer{display:none!important}';" +
            "function estilo(){if(window.__tocAdOff||document.getElementById('toc-ad-css')||!document.documentElement)return;" +
            " var s=document.createElement('style');s.id='toc-ad-css';s.textContent=css;(document.head||document.documentElement).appendChild(s);}" +
            "estilo();document.addEventListener('DOMContentLoaded',estilo);})();";

    static final String JS_DESLIGAR = "window.__tocAdOff=true;var s=document.getElementById('toc-ad-css');if(s)s.remove();";

    /** Quantos anúncios a página já pulou ou limpou (camadas 2 e 3). */
    static final String JS_CONTAGEM = "(window.__tocAdN|0)";

    // ------------------------------------------------------------ script no início de cada página

    interface Fabrica { InvocationHandler createWebView(WebView w); String[] getSupportedFeatures(); }
    interface ProvedorWebView { InvocationHandler addDocumentStartJavaScript(String script, String[] origens); }
    interface Registro { void remove(); }

    private static Registro registro;

    /** Liga ou desliga o script de início na página (efeito a partir da próxima página carregada). */
    static String aplicar(WebView w) {
        if (registro != null) { try { registro.remove(); } catch (Throwable t) { } registro = null; }
        return ligado ? registrarNoInicio(w, JS) : "desligado";
    }

    static boolean temScriptDeInicio() { return registro != null; }

    @SuppressWarnings("unchecked")
    private static <T> T como(Class<T> c, InvocationHandler h) {
        return (T) Proxy.newProxyInstance(Anuncios.class.getClassLoader(), new Class<?>[] { c }, h);
    }

    /**
     * Registra o script para rodar antes de qualquer script da página (a mesma interface interna que a
     * biblioteca androidx.webkit usa). Devolve "ok" ou o motivo; sem isso, o script entra no início do carregamento.
     */
    static String registrarNoInicio(WebView w, String script) {
        if (Build.VERSION.SDK_INT < 24) return "Android antigo";
        try {
            ClassLoader cl = WebView.getWebViewClassLoader();
            Class<?> util = Class.forName("org.chromium.support_lib_glue.SupportLibReflectionUtil", false, cl);
            Method criar = util.getDeclaredMethod("createWebViewProviderFactory");
            Fabrica f = como(Fabrica.class, (InvocationHandler) criar.invoke(null));
            boolean ok = false;
            for (String r : f.getSupportedFeatures()) if ("DOCUMENT_START_SCRIPT".equals(r)) ok = true;
            if (!ok) return "este WebView não tem script de início (atualize o 'Android System WebView')";
            registro = como(Registro.class, como(ProvedorWebView.class, f.createWebView(w)).addDocumentStartJavaScript(script, new String[] { "*" }));
            return "ok";
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            return c.getClass().getSimpleName() + ": " + c.getMessage();
        }
    }
}
