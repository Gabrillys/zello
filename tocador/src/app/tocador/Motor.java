package app.tocador;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
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
    private String ua, referer, audioExtra, legendaExtra;
    private volatile String titulo = "";
    private volatile String tituloFixo;
    private volatile float buffer = 0;
    private volatile String situacao = "parado"; // parado, abrindo, carregando, tocando, pausado, erro
    private volatile boolean temVideo;
    private boolean querTocando, pausadoPorFoco, jaTocou;
    private int tentativas;
    private boolean eqLigado;
    /** Arquivo do aparelho (content://) aberto pelo seletor: o VLC lê pelo descritor. */
    private ParcelFileDescriptor arquivo;
    /** Tamanho do vídeo informado pelo VLC, para a tela não esticar (vídeo em pé, 4:3, 21:9…). */
    private volatile int vLarg, vAlt, visLarg, visAlt, sarNum = 1, sarDen = 1;
    private Runnable aoMudarLayout, aoTerminar;
    private final int sessaoAudio;
    /** Informações que a rádio manda junto com o som (cabeçalhos ICY): nome, gênero, descrição, música de agora. */
    private volatile String metaNome = "", metaGenero = "", metaDescricao = "", metaAgora = "", metaArte = "";

    public int sessaoAudio() { return sessaoAudio; }
    private int preset;
    private SurfaceView superficie;
    /** Streams adaptativos (m3u8/DASH): sempre a maior qualidade, em vez de variar com a conexão. */
    private boolean qualidadeMaxima;

    private Motor(Context c) {
        ctx = c;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        ArrayList<String> op = new ArrayList<String>();
        op.add("--network-caching=3000");
        op.add("--http-reconnect");
        op.add("--audio-time-stretch");
        op.add("--no-stats");
        // Saída de som com uma "sessão" conhecida, para a animação da rádio (Visualizer do Android) ouvir o que o VLC toca.
        sessaoAudio = am.generateAudioSessionId();
        if (sessaoAudio > 0) { op.add("--aout=android_audiotrack"); op.add("--audiotrack-session-id=" + sessaoAudio); }
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
        qualidadeMaxima = prefs.getBoolean("qualidadeMaxima", true);
        aplicarEq();
    }

    // ------------------------------------------------------------ reprodução

    public void tocar(String u, String userAgent, String ref) { tocar(u, userAgent, ref, null); }

    /** @param audio som que vem num arquivo separado do vídeo (Instagram); o VLC toca os dois juntos. */
    public void tocar(String u, String userAgent, String ref, String audio) { tocar(u, userAgent, ref, audio, null); }

    /** @param legenda arquivo de legenda externo (.vtt/.srt) para mostrar junto com o vídeo. */
    public void tocar(String u, String userAgent, String ref, String audio, String legenda) {
        url = u; ua = userAgent; referer = ref; audioExtra = audio; legendaExtra = legenda;
        titulo = ""; tituloFixo = null; jaTocou = false;
        metaNome = ""; metaGenero = ""; metaDescricao = ""; metaAgora = ""; metaArte = "";
        Diagnostico.anotar("VLC: abrir " + Diagnostico.curto(u) + (audio != null ? " + som separado " + Diagnostico.curto(audio) : "")); temVideo = false; tentativas = 0;
        querTocando = true;
        iniciarMidia();
    }

    private void iniciarMidia() {
        Media m;
        if (url.startsWith("content://")) {
            try {
                if (arquivo != null) arquivo.close();
                Uri u = Uri.parse(url);
                arquivo = ctx.getContentResolver().openFileDescriptor(u, "r");
                m = new Media(vlc, arquivo.getFileDescriptor());
                if (tituloFixo == null) tituloFixo = nomeArquivo(ctx, u);
            } catch (Exception e) {
                Diagnostico.anotar("VLC: não abri o arquivo: " + e);
                situacao = "erro";
                querTocando = false;
                return;
            }
        } else m = new Media(vlc, Uri.parse(url));
        m.setHWDecoderEnabled(true, false);
        if (ua != null && ua.length() > 0) m.addOption(":http-user-agent=" + ua);
        if (referer != null && referer.length() > 0) m.addOption(":http-referrer=" + referer);
        if (audioExtra != null) m.addSlave(new IMedia.Slave(IMedia.Slave.Type.Audio, 4, audioExtra));
        if (legendaExtra != null) m.addSlave(new IMedia.Slave(IMedia.Slave.Type.Subtitle, 4, legendaExtra));
        if (qualidadeMaxima) m.addOption(":adaptive-logic=highest");
        mp.setMedia(m);
        m.release();
        aplicarEq();
        situacao = "abrindo";
        pedirFoco();
        mp.play();
        Servico.atualizar(ctx);
    }

    /** Nome do arquivo escolhido no aparelho (para o título). */
    static String nomeArquivo(Context c, Uri u) {
        Cursor k = null;
        try {
            k = c.getContentResolver().query(u, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
            if (k != null && k.moveToFirst()) return k.getString(0);
        } catch (Exception e) { } finally { if (k != null) k.close(); }
        String s = u.getLastPathSegment();
        return s == null ? "Arquivo" : s;
    }

    public void aoMudarLayout(Runnable r) { aoMudarLayout = r; }
    /** Chamado quando um arquivo/música chega ao fim (para tocar o próximo da lista). */
    public void aoTerminar(Runnable r) { aoTerminar = r; }

    /** Mostra uma legenda gerada (arquivo .srt) junto com o vídeo que está tocando. */
    public boolean adicionarLegenda(String arquivoSrt) {
        try { return mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(new java.io.File(arquivoSrt)), true); }
        catch (Exception e) { return false; }
    }

    /**
     * Tamanho da superfície para o vídeo caber inteiro na área, sem esticar (barras pretas onde sobrar).
     * Devolve null enquanto o VLC ainda não informou o tamanho do vídeo.
     */
    public int[] encaixar(int areaLarg, int areaAlt) {
        if (vLarg * vAlt == 0 || visLarg * visAlt == 0 || areaLarg * areaAlt == 0) return null;
        double larguraVisivel = visLarg * (sarDen > 0 ? (double) sarNum / sarDen : 1.0);
        double prop = larguraVisivel / visAlt;
        double w = areaLarg, hh = areaAlt;
        if (w / hh < prop) hh = w / prop; else w = hh * prop;
        return new int[] { (int) Math.ceil(w * vLarg / visLarg), (int) Math.ceil(hh * vAlt / visAlt) };
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
    /** Título conhecido de antemão (ex.: "@perfil — legenda" do Instagram). */
    public void definirTitulo(String t) { tituloFixo = t; titulo = t == null ? "" : t; Servico.atualizar(ctx); }
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

    /** Avança (positivo) ou volta (negativo) alguns milissegundos. */
    public void pular(long ms) {
        if (!mp.isSeekable()) return;
        long t = Math.max(0, mp.getTime() + ms);
        if (mp.getLength() > 0) t = Math.min(t, mp.getLength() - 500);
        mp.setTime(t);
    }

    public boolean qualidadeMaxima() { return qualidadeMaxima; }

    /** Muda a política de qualidade; num vídeo gravado, reabre do mesmo ponto para aplicar. */
    public void definirQualidadeMaxima(boolean max) {
        if (max == qualidadeMaxima) return;
        qualidadeMaxima = max;
        prefs.edit().putBoolean("qualidadeMaxima", max).apply();
        if (ativo() && querTocando) {
            final long t = mp.getTime();
            final boolean gravado = mp.getLength() > 0;
            iniciarMidia();
            if (gravado && t > 0) h.postDelayed(new Runnable() { public void run() { mp.setTime(t); } }, 800);
        }
    }

    /** Proporção largura/altura do vídeo (0 se ainda não se sabe). */
    public double proporcao() {
        try {
            IMedia.VideoTrack v = mp.getCurrentVideoTrack();
            if (v == null || v.height == 0) return 0;
            boolean deitado = v.orientation == 5 || v.orientation == 6; // vídeo gravado com o celular de pé, girado
            return deitado ? (double) v.height / v.width : (double) v.width / v.height;
        } catch (Exception e) { return 0; }
    }

    // ------------------------------------------------------------ legendas embutidas no arquivo/stream

    public JSONObject legendas() {
        JSONObject o = new JSONObject();
        try {
            JSONArray itens = new JSONArray();
            MediaPlayer.TrackDescription[] ts = mp.getSpuTracks();
            if (ts != null) for (MediaPlayer.TrackDescription t : ts) {
                if (t.id < 0) continue; // "Desativar"
                itens.put(new JSONObject().put("chave", "vlc:" + t.id).put("nome", t.name == null ? "Legenda " + t.id : t.name));
            }
            int atual = mp.getSpuTrack();
            o.put("fonte", "vlc").put("itens", itens).put("atual", atual >= 0 ? "vlc:" + atual : "")
                    .put("podeBaixar", false).put("podeTraduzir", false);
        } catch (Exception e) { }
        return o;
    }

    public void escolherLegenda(int id) { mp.setSpuTrack(id); }
    public void volume(int v) { mp.setVolume(Math.max(0, Math.min(200, v))); }

    // ------------------------------------------------------------ vídeo

    public void anexar(SurfaceView sv, SurfaceView legendas, int largura, int altura) {
        superficie = sv;
        IVLCVout vo = mp.getVLCVout();
        if (vo.areViewsAttached()) vo.detachViews();
        vo.setVideoView(sv);
        if (legendas != null) vo.setSubtitlesView(legendas);
        vo.attachViews(new IVLCVout.OnNewVideoLayoutListener() {
            @Override public void onNewVideoLayout(IVLCVout v, int w, int hh, int vw, int vh, int sn, int sd) {
                vLarg = w; vAlt = hh; visLarg = vw; visAlt = vh; sarNum = sn; sarDen = sd;
                if (aoMudarLayout != null) h.post(aoMudarLayout);
            }
        });
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
            case MediaPlayer.Event.Playing:
                situacao = "tocando"; tentativas = 0; lerTitulo(); Servico.atualizar(ctx);
                if (!jaTocou) {
                    jaTocou = true;
                    // Mostra no diagnóstico se o som foi carregado (0 faixas de áudio = vídeo mudo).
                    h.postDelayed(new Runnable() { public void run() {
                        try { Diagnostico.anotar("VLC: tocando; faixas de áudio=" + Math.max(0, mp.getAudioTracksCount() - 1) + " faixas de vídeo=" + Math.max(0, mp.getVideoTracksCount() - 1)); } catch (Exception x) { }
                    } }, 1500);
                }
                break;
            case MediaPlayer.Event.Paused: situacao = "pausado"; Servico.atualizar(ctx); break;
            case MediaPlayer.Event.Vout: temVideo = e.getVoutCount() > 0; break;
            case MediaPlayer.Event.ESAdded: case MediaPlayer.Event.MediaChanged: lerTitulo(); break;
            case MediaPlayer.Event.EndReached:
            case MediaPlayer.Event.EncounteredError:
                if (e.type == MediaPlayer.Event.EncounteredError) Diagnostico.anotar("VLC: erro ao tocar " + Diagnostico.curto(url) + (audioExtra != null ? " (com som separado)" : ""));
                if (e.type == MediaPlayer.Event.EncounteredError && audioExtra != null && !jaTocou) {
                    // A faixa de som extra pode ter impedido a abertura: tenta só com o vídeo.
                    Diagnostico.anotar("VLC: tentando de novo sem o som separado");
                    audioExtra = null;
                    h.post(new Runnable() { public void run() { if (querTocando) iniciarMidia(); } });
                    break;
                }
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
                    if (aoTerminar != null) h.post(aoTerminar);
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
            metaAgora = limpo(agora);
            metaNome = limpo(t);
            metaGenero = limpo(m.getMeta(IMedia.Meta.Genre));
            metaDescricao = limpo(m.getMeta(IMedia.Meta.Description));
            metaArte = limpo(m.getMeta(IMedia.Meta.ArtworkURL));
            String novo = agora != null && agora.length() > 0 ? agora : tituloFixo != null ? tituloFixo : (t != null ? t : "");
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
            o.put("proporcao", proporcao());
        } catch (Exception e) { }
        return o;
    }

    private static String limpo(String s) {
        if (s == null) return "";
        s = s.trim();
        // O VLC usa o nome do arquivo/endereço como título quando a rádio não manda um.
        if (s.startsWith("http") || s.matches("(?i).*\\.(m3u8?|pls|mp3|aac|ogg|opus)$") || s.matches("(?i)stream|listen|live|;|-")) return "";
        return s;
    }

    /** Dados da transmissão para o rodapé da animação. */
    public JSONObject meta() {
        JSONObject o = new JSONObject();
        try { o.put("nome", metaNome).put("genero", metaGenero).put("descricao", metaDescricao).put("agora", metaAgora).put("arte", metaArte); } catch (Exception e) { }
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
