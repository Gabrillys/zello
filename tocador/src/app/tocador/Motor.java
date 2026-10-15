package app.tocador;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;

/** Motor de reprodução com libVLC: toca links diretos (rádio, mp3, m3u8, mp4…), com equalizador. */
public final class Motor implements MediaPlayer.EventListener, AudioManager.OnAudioFocusChangeListener {

    private static Motor unico;

    public static synchronized Motor de(Context c) {
        if (unico == null) unico = new Motor(c.getApplicationContext());
        return unico;
    }

    public static synchronized Motor seExistir() { return unico; }

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final LibVLC vlc;
    private final MediaPlayer mp;
    private final MediaPlayer.Equalizer eq;
    private final SharedPreferences prefs;
    private final AudioManager am;
    private AudioFocusRequest pedidoFoco;

    private volatile String url = "";
    private String ua, referer;
    private volatile String titulo = "";
    private volatile float buffer = 0;
    private volatile String situacao = "parado"; // parado, abrindo, carregando, tocando, pausado, erro
    private volatile boolean temVideo;
    private boolean querTocando, pausadoPorFoco;
    private int tentativas;
    private boolean eqLigado;
    private int preset;
    private SurfaceView superficie;

    private Motor(Context c) {
        ctx = c;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        ArrayList<String> op = new ArrayList<String>();
        op.add("--network-caching=3000");
        op.add("--http-reconnect");
        op.add("--audio-time-stretch");
        op.add("--no-stats");
        vlc = new LibVLC(c, op);
        mp = new MediaPlayer(vlc);
        mp.setEventListener(this);
        prefs = c.getSharedPreferences("eq", Context.MODE_PRIVATE);
        preset = prefs.getInt("preset", -1);
        eq = preset >= 0 ? MediaPlayer.Equalizer.createFromPreset(preset) : MediaPlayer.Equalizer.create();
        eqLigado = prefs.getBoolean("ligado", true);
        String salvo = prefs.getString("bandas", null);
        if (salvo != null) {
            String[] p = salvo.split(",");
            for (int i = 0; i < p.length && i < MediaPlayer.Equalizer.getBandCount(); i++) {
                try { eq.setAmp(i, Float.parseFloat(p[i])); } catch (Exception e) { }
            }
            eq.setPreAmp(prefs.getFloat("preamp", eq.getPreAmp()));
        }
        aplicarEq();
    }

    // ------------------------------------------------------------ reprodução

    public void tocar(String u, String userAgent, String ref) {
        url = u; ua = userAgent; referer = ref;
        titulo = ""; temVideo = false; tentativas = 0;
        querTocando = true;
        iniciarMidia();
    }

    private void iniciarMidia() {
        Media m = new Media(vlc, Uri.parse(url));
        m.setHWDecoderEnabled(true, false);
        if (ua != null && ua.length() > 0) m.addOption(":http-user-agent=" + ua);
        if (referer != null && referer.length() > 0) m.addOption(":http-referrer=" + referer);
        mp.setMedia(m);
        m.release();
        aplicarEq();
        situacao = "abrindo";
        pedirFoco();
        mp.play();
        Servico.atualizar(ctx);
    }

    public void tocarPausar() {
        if (url.length() == 0) return;
        if (mp.isPlaying()) { querTocando = false; mp.pause(); }
        else continuar();
    }

    public void continuar() {
        if (url.length() == 0) return;
        querTocando = true;
        pedirFoco();
        if (situacao.equals("parado") || situacao.equals("erro")) iniciarMidia(); else mp.play();
    }

    public void pausar() { querTocando = false; if (mp.isPlaying()) mp.pause(); }

    public void parar() {
        querTocando = false;
        h.removeCallbacksAndMessages(null);
        mp.stop();
        situacao = "parado";
        largarFoco();
        Servico.atualizar(ctx);
    }

    public boolean ativo() { return url.length() > 0 && !situacao.equals("parado"); }
    public boolean tocando() { return mp.isPlaying(); }
    public String titulo() { return titulo; }
    public String url() { return url; }
    public String agente() { return ua; }
    public String referer() { return referer; }
    /** Tem duração conhecida (arquivo ou vídeo gravado), ou seja, não é ao vivo. */
    public boolean temDuracao() { return mp.getLength() > 0; }
    /** Há imagem neste link? (rádio = não; vídeo = sim). */
    public boolean temVideoAgora() {
        if (temVideo) return true;
        if (!ativo()) return false;
        try { return mp.getVideoTracksCount() > 0; } catch (Exception e) { return false; }
    }

    public void buscar(float pos) { if (mp.isSeekable()) mp.setPosition(pos); }
    public void volume(int v) { mp.setVolume(Math.max(0, Math.min(200, v))); }

    // ------------------------------------------------------------ vídeo

    public void anexar(SurfaceView sv, int largura, int altura) {
        superficie = sv;
        IVLCVout vo = mp.getVLCVout();
        if (vo.areViewsAttached()) vo.detachViews();
        vo.setVideoView(sv);
        vo.attachViews();
        if (largura > 0 && altura > 0) vo.setWindowSize(largura, altura);
        mp.setVideoTrackEnabled(true);
    }

    public void tamanho(int largura, int altura) {
        if (superficie != null && largura > 0 && altura > 0) mp.getVLCVout().setWindowSize(largura, altura);
    }

    /** Tela apagada ou app em segundo plano: tira o vídeo e mantém só o som. */
    public void desanexar() {
        superficie = null;
        mp.setVideoTrackEnabled(false);
        IVLCVout vo = mp.getVLCVout();
        if (vo.areViewsAttached()) vo.detachViews();
    }

    // ------------------------------------------------------------ eventos

    @Override
    public void onEvent(MediaPlayer.Event e) {
        switch (e.type) {
            case MediaPlayer.Event.Opening: situacao = "abrindo"; break;
            case MediaPlayer.Event.Buffering:
                buffer = e.getBuffering();
                if (buffer < 100f && !situacao.equals("pausado")) situacao = "carregando";
                else if (mp.isPlaying()) situacao = "tocando";
                break;
            case MediaPlayer.Event.Playing: situacao = "tocando"; tentativas = 0; lerTitulo(); Servico.atualizar(ctx); break;
            case MediaPlayer.Event.Paused: situacao = "pausado"; Servico.atualizar(ctx); break;
            case MediaPlayer.Event.Vout: temVideo = e.getVoutCount() > 0; break;
            case MediaPlayer.Event.ESAdded: case MediaPlayer.Event.MediaChanged: lerTitulo(); break;
            case MediaPlayer.Event.EndReached:
            case MediaPlayer.Event.EncounteredError:
                boolean aoVivo = mp.getLength() <= 0;
                if (querTocando && (aoVivo || e.type == MediaPlayer.Event.EncounteredError) && tentativas < 6) {
                    // Rádio/stream caiu: tenta reconectar sozinho.
                    tentativas++;
                    situacao = "carregando";
                    h.postDelayed(new Runnable() { public void run() { if (querTocando) iniciarMidia(); } }, 2000L * tentativas);
                } else {
                    situacao = e.type == MediaPlayer.Event.EncounteredError ? "erro" : "parado";
                    querTocando = false;
                    Servico.atualizar(ctx);
                }
                break;
            case MediaPlayer.Event.Stopped:
                if (!querTocando) situacao = "parado";
                break;
            default: break;
        }
    }

    private void lerTitulo() {
        IMedia m = mp.getMedia();
        if (m == null) return;
        try {
            String agora = m.getMeta(IMedia.Meta.NowPlaying);
            String t = m.getMeta(IMedia.Meta.Title);
            String novo = agora != null && agora.length() > 0 ? agora : (t != null ? t : "");
            if (!novo.equals(titulo)) { titulo = novo; Servico.atualizar(ctx); }
        } finally { m.release(); }
    }

    public JSONObject estado() {
        JSONObject o = new JSONObject();
        try {
            if (situacao.equals("tocando") || situacao.equals("carregando")) lerTituloSeguro();
            o.put("situacao", situacao);
            o.put("tocando", mp.isPlaying());
            o.put("buffer", (int) buffer);
            o.put("titulo", titulo);
            o.put("url", url);
            o.put("tempo", mp.getTime());
            o.put("duracao", mp.getLength());
            o.put("posicao", (double) mp.getPosition());
            o.put("busca", mp.isSeekable());
            o.put("video", temVideoAgora());
            o.put("volume", mp.getVolume());
        } catch (Exception e) { }
        return o;
    }

    private void lerTituloSeguro() { h.post(new Runnable() { public void run() { lerTitulo(); } }); }

    // ------------------------------------------------------------ foco de áudio

    private void pedirFoco() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (pedidoFoco == null) {
                pedidoFoco = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setOnAudioFocusChangeListener(this, h)
                        .setWillPauseWhenDucked(false)
                        .build();
            }
            am.requestAudioFocus(pedidoFoco);
        } else {
            am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void largarFoco() {
        if (Build.VERSION.SDK_INT >= 26) { if (pedidoFoco != null) am.abandonAudioFocusRequest(pedidoFoco); }
        else am.abandonAudioFocus(this);
    }

    @Override
    public void onAudioFocusChange(int f) {
        if (f == AudioManager.AUDIOFOCUS_LOSS) { pausar(); }
        else if (f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (mp.isPlaying()) { pausadoPorFoco = true; mp.pause(); }
        } else if (f == AudioManager.AUDIOFOCUS_GAIN) {
            if (pausadoPorFoco) { pausadoPorFoco = false; mp.play(); }
        }
    }

    // ------------------------------------------------------------ equalizador (10 bandas do VLC)

    private void aplicarEq() { mp.setEqualizer(eqLigado ? eq : null); }

    private void salvarEq() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MediaPlayer.Equalizer.getBandCount(); i++) { if (i > 0) sb.append(','); sb.append(eq.getAmp(i)); }
        prefs.edit().putString("bandas", sb.toString()).putFloat("preamp", eq.getPreAmp())
                .putBoolean("ligado", eqLigado).putInt("preset", preset).apply();
    }

    public JSONObject eqInfo() {
        JSONObject o = new JSONObject();
        try {
            JSONArray f = new JSONArray(), n = new JSONArray(), p = new JSONArray();
            for (int i = 0; i < MediaPlayer.Equalizer.getBandCount(); i++) { f.put((double) MediaPlayer.Equalizer.getBandFrequency(i)); n.put((double) eq.getAmp(i)); }
            for (int i = 0; i < MediaPlayer.Equalizer.getPresetCount(); i++) p.put(MediaPlayer.Equalizer.getPresetName(i));
            o.put("motor", "vlc");
            o.put("bandas", f); o.put("niveis", n); o.put("presets", p);
            o.put("preset", preset); o.put("preamp", (double) eq.getPreAmp());
            o.put("min", -20); o.put("max", 20); o.put("ligado", eqLigado); o.put("temPreamp", true);
        } catch (Exception e) { }
        return o;
    }

    public void eqBanda(int i, float db) { eq.setAmp(i, db); preset = -1; aplicarEq(); salvarEq(); }
    public void eqPreamp(float db) { eq.setPreAmp(db); aplicarEq(); salvarEq(); }
    public void eqLigar(boolean l) { eqLigado = l; aplicarEq(); salvarEq(); }

    public void eqPreset(int p) {
        MediaPlayer.Equalizer x = MediaPlayer.Equalizer.createFromPreset(p);
        for (int i = 0; i < MediaPlayer.Equalizer.getBandCount(); i++) eq.setAmp(i, x.getAmp(i));
        eq.setPreAmp(x.getPreAmp());
        preset = p; aplicarEq(); salvarEq();
    }
}
