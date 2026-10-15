package app.tocador;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.HashMap;

/**
 * Navegador para o YouTube e outros sites de vídeo. O vídeo é tocado pelo player do próprio site;
 * o app impede que o site perceba que a tela apagou (o som continua), aplica a qualidade escolhida,
 * isola o vídeo em tela cheia e cuida das legendas.
 */
public class Navegador extends WebView {

    public interface TelaCheia { void mostrar(View v, WebChromeClient.CustomViewCallback cb); void esconder(); }

    static volatile boolean tocandoAgora;
    static volatile String tituloAgora = "";
    /** Último estado lido da página (tempo, duração, legendas, qualidade…). */
    static volatile JSONObject estadoPagina = new JSONObject();
    private static Navegador atual;

    // ------------------------------------------------------------ scripts injetados na página

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

    /** O vídeo principal da página: o que está tocando, ou o maior. */
    private static final String PRINCIPAL =
            "function principal(){var vs=[].slice.call(document.querySelectorAll('video'));var b=null,ba=-1;" +
            "vs.forEach(function(v){var r=v.getBoundingClientRect();var a=r.width*r.height+(v.paused?0:1e9);if(a>ba){ba=a;b=v;}});return b;}";

    private static final String JS_ESTADO = "(function(){" + PRINCIPAL +
            "var v=principal();var ms=[].slice.call(document.querySelectorAll('video,audio'));" +
            "var o={t:false,n:ms.length,titulo:document.title||''};" +
            "ms.forEach(function(m){if(!m.paused&&!m.ended&&m.readyState>1)o.t=true;});" +
            "if(v){o.tempo=Math.round(v.currentTime*1000);o.dur=isFinite(v.duration)?Math.round(v.duration*1000):0;o.vw=v.videoWidth;o.vh=v.videoHeight;}" +
            "var p=document.getElementById('movie_player');o.yt=!!(p&&p.getAvailableQualityLevels);" +
            "if(o.yt){try{o.q=p.getPlaybackQuality();o.qs=p.getAvailableQualityLevels();var vd=p.getVideoData&&p.getVideoData();o.vid=vd&&vd.video_id;if(vd&&vd.title)o.titulo=vd.title;}catch(e){}" +
            " try{var l=[];var r=p.getPlayerResponse&&p.getPlayerResponse();" +
            "  var ct=(r&&r.captions&&r.captions.playerCaptionsTracklistRenderer&&r.captions.playerCaptionsTracklistRenderer.captionTracks)||[];" +
            "  ct.forEach(function(c){var n=c.name&&(c.name.simpleText||(c.name.runs||[]).map(function(x){return x.text}).join(''))||c.languageCode;l.push({idioma:c.languageCode,nome:n,auto:c.kind==='asr'});});" +
            "  if(!l.length){var tl=p.getOption&&p.getOption('captions','tracklist')||[];tl.forEach(function(c){l.push({idioma:c.languageCode,nome:c.displayName||c.languageName||c.languageCode,auto:c.kind==='asr'});});}" +
            "  o.legs=l;var at=p.getOption&&p.getOption('captions','track');" +
            "  o.legAtual=at&&at.languageCode?('yt:'+at.languageCode+(at.kind==='asr'?':asr':'')):'';o.legTrad=at&&at.translationLanguage?at.translationLanguage.languageCode:'';" +
            " }catch(e){}" +
            " o.cap=[].map.call(document.querySelectorAll('.ytp-caption-segment,.captions-text .caption-visual-line'),function(s){return s.textContent}).join('\\n');" +
            "}else if(v&&v.textTracks){var l2=[],cap='';for(var i=0;i<v.textTracks.length;i++){var tt=v.textTracks[i];" +
            " if(tt.kind!=='subtitles'&&tt.kind!=='captions')continue;l2.push({i:i,nome:tt.label||tt.language||('Faixa '+(i+1)),idioma:tt.language||''});" +
            " if(tt.mode==='showing'){o.legAtual='tt:'+i;if(tt.activeCues)for(var k=0;k<tt.activeCues.length;k++)cap+=(cap?'\\n':'')+String(tt.activeCues[k].text||'').replace(/<[^>]+>/g,'');}}" +
            " o.legs=l2;o.cap=cap;}" +
            "if(document.documentElement.classList.contains('tocador-cheio')&&(!v||!v.classList.contains('tocador-alvo')))o.reisolar=true;" +
            "return o;})()";

    private static String jsComando(String acao) {
        return "(function(a){var m=[].slice.call(document.querySelectorAll('video,audio'));" +
                "var p=m.filter(function(x){return !x.paused&&!x.ended});" +
                "if(a==='pausar'||(a==='alternar'&&p.length)){p.forEach(function(x){x.__tocadorPausou=1;x.pause()});return 'pausado'}" +
                "var alvo=m.filter(function(x){return x.__tocadorPausou})[0]||p[0]||m[0];" +
                "if(alvo){var r=alvo.play();if(r&&r.catch)r.catch(function(){});return 'tocando'}return ''})('" + acao + "')";
    }

    /**
     * Tela cheia "só o vídeo": o vídeo principal ocupa a tela inteira e todo o resto da página
     * (menus, comentários, botões do site) fica invisível.
     */
    private static String jsIsolar(boolean ligar) {
        return "(function(on){" + PRINCIPAL + "var ID='__tocadorCheio';var st=document.getElementById(ID);" +
                "[].slice.call(document.querySelectorAll('.tocador-anc,.tocador-alvo')).forEach(function(e){e.classList.remove('tocador-anc');e.classList.remove('tocador-alvo');});" +
                "if(!on){if(st)st.remove();document.documentElement.classList.remove('tocador-cheio');return 'off';}" +
                "var v=principal();if(!v)return 'sem';" +
                "if(!st){st=document.createElement('style');st.id=ID;st.textContent=" +
                "'html.tocador-cheio,html.tocador-cheio body{overflow:hidden!important;background:#000!important}'+" +
                "'html.tocador-cheio body *:not(.tocador-anc):not(.tocador-alvo){visibility:hidden!important}'+" +
                "'html.tocador-cheio .tocador-anc{visibility:visible!important;transform:none!important;filter:none!important;contain:none!important;will-change:auto!important;clip-path:none!important;opacity:1!important;background:transparent!important}'+" +
                "'html.tocador-cheio .tocador-alvo{visibility:visible!important;position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;padding:0!important;z-index:2147483647!important;object-fit:contain!important;background:#000!important;transform:none!important;opacity:1!important}';" +
                "(document.head||document.documentElement).appendChild(st);}" +
                "document.documentElement.classList.add('tocador-cheio');window.scrollTo(0,0);" +
                "v.classList.add('tocador-alvo');for(var e=v.parentElement;e&&e!==document.documentElement;e=e.parentElement)e.classList.add('tocador-anc');" +
                "return 'on';})(" + ligar + ")";
    }

    /** Qualidade no player do YouTube: "max", "auto" ou um nível (hd1080, hd720…). */
    private static String jsQualidade(String q) {
        return "(function(q){var p=document.getElementById('movie_player');if(!p||!p.getAvailableQualityLevels)return '';" +
                "var l=(p.getAvailableQualityLevels()||[]).filter(function(x){return x!=='auto'});if(!l.length)return '';" +
                "if(q==='auto'){if(p.setPlaybackQualityRange)p.setPlaybackQualityRange('auto','auto');return 'auto';}" +
                "var ordem=['highres','hd2880','hd2160','hd1440','hd1080','hd720','large','medium','small','tiny'];var alvo=null;" +
                "if(q==='max')alvo=l[0];else{var qi=ordem.indexOf(q);for(var i=0;i<l.length;i++){if(ordem.indexOf(l[i])>=qi){alvo=l[i];break;}}alvo=alvo||l[l.length-1];}" +
                "if(p.setPlaybackQualityRange)p.setPlaybackQualityRange(alvo,alvo);if(p.setPlaybackQuality)p.setPlaybackQuality(alvo);return alvo;})('" + q + "')";
    }

    /** Liga uma legenda do YouTube (idioma vazio = desliga). traduzir = código do idioma de tradução, ou "". */
    static String jsLegendaYoutube(String idioma, boolean auto, String traduzir) {
        return "(function(lang,asr,trad){var p=document.getElementById('movie_player');if(!p)return 'sem';" +
                "if(!lang){try{p.setOption('captions','track',{});}catch(e){}try{p.unloadModule('captions');}catch(e){}return 'off';}" +
                "try{p.loadModule('captions');}catch(e){}var alvo=null;" +
                "try{var tl=p.getOption('captions','tracklist')||[];for(var i=0;i<tl.length;i++){if(tl[i].languageCode===lang&&((tl[i].kind==='asr')===asr)){alvo=tl[i];break;}}}catch(e){}" +
                "var t=alvo?JSON.parse(JSON.stringify(alvo)):{languageCode:lang};if(asr&&!alvo)t.kind='asr';if(trad)t.translationLanguage={languageCode:trad};" +
                "try{p.setOption('captions','track',t);}catch(e){return 'erro';}return alvo?'ok':'parcial';})(" +
                JSONObject.quote(idioma) + "," + auto + "," + JSONObject.quote(traduzir == null ? "" : traduzir) + ")";
    }

    /** Liga a faixa i do vídeo (legendas padrão da web); -1 desliga todas. */
    static String jsLegendaFaixa(int i) {
        return "(function(i){" + PRINCIPAL + "var v=principal();if(!v||!v.textTracks)return 'sem';" +
                "for(var j=0;j<v.textTracks.length;j++)v.textTracks[j].mode=(j===i?'showing':'disabled');return 'ok';})(" + i + ")";
    }

    /** Lê o texto inteiro da faixa i (WebVTT carregado pelo próprio vídeo). */
    static String jsTextoFaixa(int i) {
        return "(function(i){" + PRINCIPAL + "var v=principal();var t=v&&v.textTracks&&v.textTracks[i];if(!t)return {erro:'sem'};" +
                "if(t.mode==='disabled')t.mode='hidden';if(!t.cues||!t.cues.length)return {carregando:true};" +
                "var out=[];for(var k=0;k<t.cues.length;k++){var x=String(t.cues[k].text||'').replace(/<[^>]+>/g,'').trim();if(x&&out[out.length-1]!==x)out.push(x);}" +
                "return {texto:out.join('\\n'),nome:t.label||t.language||''};})(" + i + ")";
    }

    static String jsPular(double segundos) {
        return "(function(s){" + PRINCIPAL + "var v=principal();if(!v)return;v.currentTime=Math.max(0,v.currentTime+s);})(" + segundos + ")";
    }

    static String jsIrPara(double fracao) {
        return "(function(f){" + PRINCIPAL + "var v=principal();if(v&&isFinite(v.duration))v.currentTime=f*v.duration;})(" + fracao + ")";
    }

    // ------------------------------------------------------------ estado

    private final Handler h = new Handler(Looper.getMainLooper());
    private final String agenteCelular;
    private TelaCheia telaCheia;
    private volatile boolean cheio;
    private volatile String qualidade = "max";
    private String ultimoVideo = "", ultimosNiveis = "";
    private long ultimaQualidade;
    /** Endereços de legenda pedidos pelo player do YouTube: "vídeo|idioma|asr" -> endereço (com a autorização do player). */
    private static final HashMap<String, String> legendasPedidas = new HashMap<String, String>();

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
        agenteCelular = s.getUserAgentString().replace("; wv", "").replaceAll("Version/[0-9.]+ ", "");
        s.setUserAgentString(agenteCelular);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true);
        if (Build.VERSION.SDK_INT >= 26) setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false);
        setBackgroundColor(0xFF000000);

        setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, Bitmap f) { v.evaluateJavascript(JS_SEMPRE_VISIVEL, null); }
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(JS_SEMPRE_VISIVEL, null);
                if (cheio) v.evaluateJavascript(jsIsolar(true), null);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String esq = r.getUrl().getScheme();
                // Links "intent:", "vnd.youtube:" etc. abririam o app oficial; ficamos no navegador.
                return esq == null || !(esq.equals("http") || esq.equals("https"));
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                anotarLegenda(r.getUrl().toString());
                return null;
            }
        });
        // O YouTube às vezes busca a legenda por um "service worker": esses pedidos também são vistos.
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                    @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest r) { anotarLegenda(r.getUrl().toString()); return null; }
                });
            } catch (Throwable t) { }
        }

        setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback cb) { if (telaCheia != null) telaCheia.mostrar(view, cb); }
            @Override public void onHideCustomView() { if (telaCheia != null) telaCheia.esconder(); }
            @Override public void onReceivedTitle(WebView v, String t) { tituloAgora = t == null ? "" : t; }
        });

        h.postDelayed(consulta, 1000);
    }

    public void definirTelaCheia(TelaCheia t) { telaCheia = t; }
    public String agente() { return getSettings().getUserAgentString(); }

    /** O site nunca fica sabendo que a janela sumiu (tela apagada, outro app na frente). */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(View.VISIBLE);
    }

    // ------------------------------------------------------------ tela cheia, qualidade, versão para computador

    public void isolar(boolean ligar) {
        cheio = ligar;
        evaluateJavascript(jsIsolar(ligar), null);
    }

    public void definirQualidade(String q) {
        qualidade = q;
        ultimosNiveis = "";
        evaluateJavascript(jsQualidade(q), null);
    }

    /** Versão para computador: alguns sites só liberam a qualidade máxima nela. */
    public void definirComputador(boolean ligar) {
        String ua = ligar ? "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36" : agenteCelular;
        if (ua.equals(getSettings().getUserAgentString())) return;
        getSettings().setUserAgentString(ua);
        String u = getUrl();
        if (u != null && u.length() > 0) {
            // O YouTube manda o celular para m.youtube.com; na versão para computador usa www.
            if (ligar) u = u.replaceFirst("^https://m\\.youtube\\.com/", "https://www.youtube.com/");
            loadUrl(u);
        }
    }

    public boolean computador() { return !agenteCelular.equals(getSettings().getUserAgentString()); }

    // ------------------------------------------------------------ legendas

    private void anotarLegenda(String u) {
        if (!u.contains("/api/timedtext")) return;
        String v = Legendas.parametro(u, "v"), lang = Legendas.parametro(u, "lang");
        if (v == null || lang == null) return;
        String kind = Legendas.parametro(u, "kind");
        synchronized (legendasPedidas) { legendasPedidas.put(v + "|" + lang + "|" + ("asr".equals(kind)), u); }
        Diagnostico.anotar("Legenda pedida pelo player: " + lang + ("asr".equals(kind) ? " (automática)" : ""));
    }

    /** Endereço da legenda que o próprio player do YouTube já pediu (com autorização), ou null. */
    public static String legendaPedida(String video, String idioma, boolean auto) {
        synchronized (legendasPedidas) {
            String u = legendasPedidas.get(video + "|" + idioma + "|" + auto);
            if (u == null && idioma.contains("-")) u = legendasPedidas.get(video + "|" + idioma.substring(0, idioma.indexOf('-')) + "|" + auto);
            return u;
        }
    }

    // ------------------------------------------------------------ consulta periódica

    private final Runnable consulta = new Runnable() {
        public void run() {
            evaluateJavascript(JS_SEMPRE_VISIVEL, null);
            evaluateJavascript(JS_ESTADO, new ValueCallback<String>() {
                public void onReceiveValue(String v) {
                    try {
                        JSONObject o = new JSONObject(v);
                        estadoPagina = o;
                        boolean antes = tocandoAgora;
                        tocandoAgora = o.optBoolean("t");
                        String t = o.optString("titulo");
                        if (t.length() > 0) tituloAgora = t;
                        if (antes != tocandoAgora) Servico.atualizar(getContext());
                        if (o.optBoolean("reisolar")) evaluateJavascript(jsIsolar(true), null);
                        aplicarQualidadeSePreciso(o);
                    } catch (Exception e) { }
                }
            });
            // Em tela cheia a legenda é desenhada pelo app: consulta mais vezes para acompanhar a fala.
            h.postDelayed(this, cheio ? 400 : 1000);
        }
    };

    /** O YouTube volta para a qualidade automática a cada vídeo novo: reaplica a escolhida. */
    private void aplicarQualidadeSePreciso(JSONObject o) {
        if (!o.optBoolean("yt") || "auto".equals(qualidade)) return;
        String vid = o.optString("vid"), niveis = String.valueOf(o.optJSONArray("qs"));
        long agora = System.currentTimeMillis();
        boolean mudou = !vid.equals(ultimoVideo) || !niveis.equals(ultimosNiveis);
        if (!mudou && agora - ultimaQualidade < 5000) return;
        String primeiro = o.optJSONArray("qs") != null && o.optJSONArray("qs").length() > 0 ? o.optJSONArray("qs").optString(0) : "";
        if (!mudou && "max".equals(qualidade) && primeiro.equals(o.optString("q"))) return;
        if (!mudou && !"max".equals(qualidade) && qualidade.equals(o.optString("q"))) return;
        ultimoVideo = vid; ultimosNiveis = niveis; ultimaQualidade = agora;
        evaluateJavascript(jsQualidade(qualidade), null);
    }

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
        estadoPagina = new JSONObject();
        super.destroy();
    }
}
