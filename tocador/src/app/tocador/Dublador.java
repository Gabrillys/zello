package app.tocador;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.webkit.ValueCallback;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * Dublagem automática: diz em voz alta, no momento certo, cada fala da legenda traduzida para português,
 * com o áudio original abaixado. Usa o mecanismo de voz do Android, que pode ser qualquer motor instalado
 * (Google, ou os de código aberto RHVoice e eSpeak NG, do F-Droid).
 */
final class Dublador implements TextToSpeech.OnInitListener {

    private static Dublador unico;

    static synchronized Dublador de(Context c) {
        if (unico == null) unico = new Dublador(c.getApplicationContext());
        return unico;
    }

    static synchronized Dublador seExistir() { return unico; }

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private volatile boolean pronto, ativo;
    private volatile String estado = "";
    private volatile int volumeOriginal;
    private Roteiro roteiro;
    private Navegador nav;
    private String video = "";
    private int voltas;

    private Dublador(Context c) {
        ctx = c;
        volumeOriginal = c.getSharedPreferences("ajustes", Context.MODE_PRIVATE).getInt("volumeOriginal", 15);
    }

    boolean ativo() { return ativo; }
    String estado() { return estado; }
    int volumeOriginal() { return volumeOriginal; }
    void definirEstado(String e) { estado = e; }

    void iniciar(Navegador n, List<Roteiro.Fala> falas, String origem, String idVideo) {
        nav = n;
        roteiro = new Roteiro(falas);
        video = idVideo == null ? "" : idVideo;
        ativo = true;
        estado = "Dublando em português (" + falas.size() + " falas, a partir de: " + origem + ")";
        if (tts == null) tts = new TextToSpeech(ctx, this);
        else if (!pronto) estado = "Preparando a voz…";
        h.removeCallbacks(ciclo);
        h.post(ciclo);
        Diagnostico.anotar("Dublagem iniciada: " + falas.size() + " falas, origem " + origem);
    }

    void parar(String motivo) {
        ativo = false;
        h.removeCallbacks(ciclo);
        if (tts != null) tts.stop();
        aplicarVolume(1.0);
        estado = motivo == null ? "" : motivo;
    }

    void definirVolumeOriginal(int v) {
        volumeOriginal = Math.max(0, Math.min(100, v));
        ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE).edit().putInt("volumeOriginal", volumeOriginal).apply();
        if (ativo) aplicarVolume(volumeOriginal / 100.0);
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) { estado = "Não encontrei um mecanismo de voz no aparelho. Instale um (Google, RHVoice ou eSpeak NG)."; return; }
        tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        int r = tts.setLanguage(new Locale("pt", "BR"));
        if (r < 0) r = tts.setLanguage(new Locale("pt"));
        if (r < 0) {
            estado = "O mecanismo de voz não tem português instalado. Em Escolher voz, baixe a voz em português.";
            Diagnostico.anotar("Dublagem: voz em português indisponível (" + r + "), motor " + tts.getDefaultEngine());
            return;
        }
        pronto = true;
        Diagnostico.anotar("Dublagem: voz pronta, motor " + tts.getDefaultEngine());
        if (ativo && estado.startsWith("Preparando")) estado = "Dublando em português";
    }

    private void aplicarVolume(double v) {
        if (nav != null) nav.evaluateJavascript("(function(v){[].slice.call(document.querySelectorAll('video,audio')).forEach(function(m){m.volume=v})})(" + v + ")", null);
    }

    /** Acompanha o vídeo várias vezes por segundo e solta cada fala na hora dela. */
    private final Runnable ciclo = new Runnable() {
        public void run() {
            if (!ativo || nav == null) return;
            // O YouTube às vezes devolve o volume ao trocar de qualidade/anúncio: reaplica de tempos em tempos.
            if (voltas++ % 10 == 0) aplicarVolume(volumeOriginal / 100.0);
            nav.evaluateJavascript(Navegador.JS_TEMPO, new ValueCallback<String>() {
                public void onReceiveValue(String v) {
                    if (!ativo) return;
                    try {
                        JSONObject o = new JSONObject(v);
                        String vid = o.optString("vid");
                        if (video.length() > 0 && vid.length() > 0 && !vid.equals(video)) {
                            parar("O vídeo mudou. Toque em Dublar de novo para dublar este.");
                            return;
                        }
                        if (o.optBoolean("p")) { if (tts != null && tts.isSpeaking()) tts.stop(); roteiro.reposicionar(o.optLong("t")); return; }
                        Roteiro.Fala f = roteiro.falaPara(o.optLong("t"));
                        if (f != null && pronto) {
                            tts.setSpeechRate(Roteiro.velocidade(f) * (float) Math.max(1.0, o.optDouble("r", 1.0)));
                            tts.speak(f.texto, TextToSpeech.QUEUE_FLUSH, new Bundle(), "f" + f.ini);
                        }
                    } catch (Exception e) { }
                }
            });
            h.postDelayed(this, 200);
        }
    };
}
