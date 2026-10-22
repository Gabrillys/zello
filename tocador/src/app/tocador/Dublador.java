package app.tocador;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.ValueCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Dublagem em português.
 *
 * De onde vem o texto:
 *  - "legenda": legenda do YouTube traduzida pelo próprio YouTube (instantâneo, sincronizado);
 *  - "aovivo": o Whisper ouve o vídeo enquanto ele toca (a voz vem alguns segundos depois da fala original);
 *  - "preparar": o Whisper ouve o vídeo inteiro uma vez; depois ele recomeça dublado, com sincronia perfeita ("pronta").
 * A voz: vozes neurais da Microsoft (naturais) ou, sem internet para elas, a voz do Android.
 * Cada frase toca inteira, sem ser cortada pela seguinte; se precisar, a voz acelera um pouco para caber.
 */
final class Dublador implements TextToSpeech.OnInitListener {

    private static Dublador unico;

    static synchronized Dublador de(Context c) {
        if (unico == null) unico = new Dublador(c.getApplicationContext());
        return unico;
    }

    static synchronized Dublador seExistir() { return unico; }

    static final String VOZ_ANDROID = "android";

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final VozNeural neural = new VozNeural();
    private final File pastaClipes;

    private volatile boolean ativo;
    private volatile String estado = "", modo = "";
    private volatile int volumeOriginal;

    /**
     * O que o serviço em primeiro plano mostra enquanto o Whisper trabalha na dublagem. Importa porque, quando o
     * Whisper fica para trás, o vídeo é pausado para ele alcançar: sem música tocando, a CPU poderia dormir.
     */
    private final ServicoTrabalho.Tarefa tarefaWhisper = new ServicoTrabalho.Tarefa() {
        public String nome() { return "dublagem"; }
        public String titulo() { return "preparar".equals(modo) ? "Preparando a dublagem" : "Dublando com o Whisper"; }
        public String texto() { return estado; }
        public int andamento() { long d = duracao, t = tempoLido; return "preparar".equals(modo) && d > 0 ? (int) Math.max(0, Math.min(100, t * 100 / d)) : -1; }
        public void cancelar() { h.post(new Runnable() { public void run() { parar("Dublagem cancelada."); } }); }
    };

    private Roteiro roteiro;
    private Navegador nav;
    private String video = "";
    private int voltas;

    // Relógio do vídeo (lido do site várias vezes por segundo)
    private volatile long tempoLido, lidoEm, duracao;
    private volatile boolean pausado = true;
    private volatile double ritmo = 1;

    // Whisper
    private volatile Transcritor transcritor;
    private volatile boolean ouvindo;          // o vídeo está mandando o som para o Whisper
    private volatile float nivel = -1, picoAtual;
    private long picoDesde;
    private int falhasOuvir;
    private Whisper whisper;
    private volatile String idiomaVideo = "";
    private volatile boolean terminandoPreparo;
    private final java.util.concurrent.atomic.AtomicInteger falasPendentes = new java.util.concurrent.atomic.AtomicInteger();

    // Voz
    private final Map<Roteiro.Fala, File> clipes = new ConcurrentHashMap<Roteiro.Fala, File>();
    private ExecutorService traducao;
    private Thread sintese;
    private volatile int falhasNeural;
    private volatile String avisoVoz = "";
    private TextToSpeech tts;
    private volatile boolean ttsPronto;

    // Reprodução
    private MediaPlayer mp;
    private boolean pausouClipe;
    private final LinkedList<Roteiro.Fala> fila = new LinkedList<Roteiro.Fala>();
    private volatile long ultimoIniTocado = Long.MIN_VALUE;
    private boolean pausouParaWhisper;
    private String estadoAntesDaPausa = "";
    private long tempoAnterior = -1;

    private Dublador(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("ajustes", Context.MODE_PRIVATE);
        volumeOriginal = prefs.getInt("volumeOriginal", 15);
        pastaClipes = new File(c.getCacheDir(), "dublagem");
    }

    // ------------------------------------------------------------ configurações

    String voz() { return prefs.getString("voz", VozNeural.VOZES[0][0]); }
    void definirVoz(String v) { prefs.edit().putString("voz", v).apply(); }
    /** O modelo escolhido (padrão: Golfinho). Se o celular não tem memória para ele (Baleia), usa o Golfinho. */
    String modelo() {
        String m = prefs.getString("modelo", "small");
        return Modelos.cabe(ctx, m) ? m : "small";
    }
    void definirModelo(String m) { prefs.edit().putString("modelo", m).apply(); }
    String idiomaFonte() { return prefs.getString("idiomaFonte", ""); }
    void definirIdiomaFonte(String i) { prefs.edit().putString("idiomaFonte", i).apply(); }
    double velocidadePreparo() { return prefs.getFloat("velPreparo", 1.0f); }
    void definirVelocidadePreparo(double v) { prefs.edit().putFloat("velPreparo", (float) v).apply(); }

    boolean ativo() { return ativo; }
    String modo() { return modo; }
    int volumeOriginal() { return volumeOriginal; }
    float nivelCaptura() { return ouvindo ? Math.max(0, nivel) : -1; }
    void definirEstado(String e) { estado = e; }

    String estado() {
        if (!ativo) return estado;
        StringBuilder s = new StringBuilder(estado);
        if ("preparar".equals(modo) && !terminandoPreparo) {
            long t = tempoAgora();
            if (duracao > 0) s.append(String.format(Locale.ROOT, " %d%% (%s de %s)", Math.min(100, t * 100 / duracao), hora(t), hora(duracao)));
        }
        Roteiro r = roteiro;
        if (r != null && !"legenda".equals(modo)) s.append(" · ").append(r.tamanho()).append(" falas");
        Transcritor tr = transcritor;
        if (tr != null && tr.ritmo().length() > 0) s.append(" · Whisper ").append(tr.ritmo()).append(" do tempo real");
        if (tr != null && tr.pendenteSeg() > 20) s.append(" · ").append(tr.pendenteSeg()).append(" s na fila");
        if (idiomaVideo.length() > 0) s.append(" · idioma: ").append(idiomaVideo);
        if (avisoVoz.length() > 0) s.append("\n").append(avisoVoz);
        return s.toString();
    }

    private static String hora(long ms) { long s = ms / 1000; return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60); }

    // ------------------------------------------------------------ início e fim

    /** Dublagem a partir da legenda do vídeo (já em português). */
    void iniciarLegenda(Navegador n, List<Roteiro.Fala> falas, String origem, String idVideo) {
        parar(null);
        comecar(n, idVideo, "legenda", new Roteiro(falas));
        estado = "Dublando a partir da legenda (" + origem + ").";
        Diagnostico.anotar("Dublagem (legenda): " + falas.size() + " falas, origem " + origem + ", voz " + voz());
    }

    /** Dublagem já preparada antes para este vídeo (sincronia perfeita). */
    boolean iniciarPronta(Navegador n, String idVideo) {
        List<Roteiro.Fala> falas = carregar(idVideo);
        if (falas == null || falas.isEmpty()) return false;
        parar(null);
        comecar(n, idVideo, "pronta", new Roteiro(falas));
        estado = "Dublagem preparada: sincronizada com o vídeo.";
        return true;
    }

    /**
     * Dublagem com o Whisper ouvindo o vídeo.
     * @param preparar true: ouve o vídeo inteiro antes (do começo, na velocidade escolhida) e depois toca sincronizado.
     */
    void iniciarWhisper(Navegador n, String idVideo, final boolean preparar) {
        parar(null);
        comecar(n, idVideo, preparar ? "preparar" : "aovivo", new Roteiro(new ArrayList<Roteiro.Fala>()));
        estado = "Carregando o Whisper…";
        ServicoTrabalho.iniciar(ctx, tarefaWhisper); // mantém o Whisper andando com a tela apagada
        String fixo = idiomaFonte();
        idiomaVideo = fixo;
        final String mod = modelo();
        transcritor = new Transcritor(new Transcritor.Ouvido() {
            public Whisper.Resultado transcrever(float[] audio, String idioma) throws Exception {
                if (!ativo) throw new IllegalStateException("dublagem parada");
                return carregarWhisper(mod).transcrever(audio, idioma);
            }
        }, new Transcritor.Saida() {
            public void falas(List<Roteiro.Fala> novas, String idioma) { recebidas(novas, idioma); }
            public void aviso(String texto) { Diagnostico.anotar("Whisper: " + texto); avisoVoz = texto; }
        }, preparar ? 28000 : 8000, fixo.length() > 0 ? fixo : null);
        final Transcritor tr = transcritor;
        new Thread(new Runnable() { public void run() {
            try {
                carregarWhisper(mod);
                if (!ativo || transcritor != tr) return;
                tr.iniciar();
                h.post(new Runnable() { public void run() {
                    if (!ativo || transcritor != tr) return;
                    ouvir();
                    if (preparar) {
                        estado = "Preparando: o Whisper está ouvindo o vídeo do começo ao fim.";
                        nav.evaluateJavascript(jsMidia("m.currentTime=0;m.playbackRate=" + velocidadePreparo() + ";" + JS_VOLUME_1 + "var r=m.play();if(r&&r.catch)r.catch(function(){});"), null);
                    } else estado = "Dublando ao vivo: a voz vem alguns segundos depois da fala original.";
                } });
                Diagnostico.anotar("Whisper " + mod + " pronto; modo " + modo);
            } catch (Throwable e) {
                Diagnostico.anotar("Whisper falhou ao iniciar: " + e);
                final String msg = e instanceof UnsatisfiedLinkError
                        ? "O Whisper não roda neste celular (precisa de processador de 64 bits)."
                        : "Não consegui iniciar o Whisper: " + e.getMessage();
                h.post(new Runnable() { public void run() { parar(msg); } });
            }
        } }, "tocador-whisper-carga").start();
    }

    private void comecar(Navegador n, String idVideo, String m, Roteiro r) {
        nav = n;
        video = idVideo == null ? "" : idVideo;
        modo = m;
        roteiro = r;
        ativo = true;
        avisoVoz = "";
        falhasNeural = 0;
        terminandoPreparo = false;
        pausouParaWhisper = false;
        fila.clear();
        ultimoIniTocado = Long.MIN_VALUE;
        tempoAnterior = -1;
        clipes.clear();
        limparCache();
        traducao = Executors.newSingleThreadExecutor();
        if (VOZ_ANDROID.equals(voz())) prepararTts();
        sintese = new Thread(new Runnable() { public void run() { lacoSintese(); } }, "tocador-voz");
        sintese.start();
        h.removeCallbacks(ciclo);
        h.post(ciclo);
    }

    void parar(String motivo) {
        boolean eraPreparo = "preparar".equals(modo);
        ativo = false;
        h.removeCallbacks(ciclo);
        pararClipe();
        fila.clear();
        pararDeOuvir();
        if (transcritor != null) { transcritor.parar(); transcritor = null; }
        if (traducao != null) { traducao.shutdownNow(); traducao = null; }
        if (sintese != null) { sintese.interrupt(); sintese = null; }
        if (tts != null) tts.stop();
        aplicarVolume(1.0);
        if (eraPreparo && nav != null) nav.evaluateJavascript(jsMidia("m.playbackRate=1;"), null);
        liberarWhisper();
        modo = "";
        estado = motivo == null ? "" : motivo;
        ServicoTrabalho.encerrar(ctx, tarefaWhisper, motivo);
    }

    void definirVolumeOriginal(int v) {
        volumeOriginal = Math.max(0, Math.min(100, v));
        prefs.edit().putInt("volumeOriginal", volumeOriginal).apply();
        if (ativo && !"preparar".equals(modo)) aplicarVolume(volumeOriginal / 100.0);
    }

    // ------------------------------------------------------------ Whisper

    private synchronized Whisper carregarWhisper(String mod) throws Exception {
        if (whisper != null) return whisper;
        if (!Modelos.pronto(ctx, mod)) throw new Exception("o modelo " + mod + " não foi baixado");
        int nucleos = Runtime.getRuntime().availableProcessors();
        whisper = new Whisper(Modelos.encoder(ctx, mod).getPath(), Modelos.decoder(ctx, mod).getPath(),
                ctx.getAssets().open(Modelos.filtroMel(mod)), ctx.getAssets().open("whisper/multilingual.tiktoken"),
                Modelos.vocab(mod), Math.max(2, Math.min(4, nucleos / 2)));
        return whisper;
    }

    private synchronized void liberarWhisper() {
        final Whisper w = whisper;
        whisper = null;
        // fecha numa linha separada: o close espera a janela em andamento terminar
        if (w != null) new Thread(new Runnable() { public void run() { w.close(); } }).start();
    }

    /** Falas novas do Whisper: traduz para português (se preciso) e coloca no roteiro. */
    private void recebidas(final List<Roteiro.Fala> novas, final String idioma) {
        if (idioma.length() > 0) idiomaVideo = idioma;
        final ExecutorService ex = traducao;
        final Roteiro r = roteiro;
        if (ex == null || r == null) return;
        falasPendentes.addAndGet(novas.size());
        try {
            ex.submit(new Runnable() { public void run() {
                List<Roteiro.Fala> prontas = new ArrayList<Roteiro.Fala>();
                try {
                    if (idioma.startsWith("pt")) prontas = novas;
                    else {
                        List<String> orig = new ArrayList<String>();
                        for (Roteiro.Fala f : novas) orig.add(f.original);
                        List<String> trad = null;
                        for (int t = 0; t < 3 && trad == null; t++) {
                            try { trad = Tradutor.traduzir(orig, t == 0 ? idioma : "auto", "pt"); }
                            catch (Exception e) { avisoVoz = "Tradução falhou (" + e.getMessage() + "), tentando de novo…"; Thread.sleep(2000L * (t + 1)); }
                        }
                        if (trad == null) { Diagnostico.anotar("Tradução falhou para " + novas.size() + " falas"); return; }
                        if (avisoVoz.startsWith("Tradução")) avisoVoz = "";
                        for (int i = 0; i < novas.size(); i++) {
                            Roteiro.Fala f = novas.get(i);
                            String t = trad.get(i).trim();
                            if (t.length() > 0) prontas.add(new Roteiro.Fala(f.ini, f.fim, t, f.original));
                        }
                    }
                    r.acrescentar(prontas);
                } catch (InterruptedException e) {
                    // parou
                } finally { falasPendentes.addAndGet(-novas.size()); }
            } });
        } catch (Exception e) { falasPendentes.addAndGet(-novas.size()); }
    }

    // ------------------------------------------------------------ preparo concluído

    private void terminarPreparo() {
        if (terminandoPreparo) return;
        terminandoPreparo = true;
        estado = "Terminando a transcrição…";
        if (transcritor != null) transcritor.descarregar();
        pararDeOuvir();
        h.postDelayed(new Runnable() { public void run() {
            if (!ativo) return;
            Transcritor tr = transcritor;
            if ((tr != null && tr.ocupado()) || falasPendentes.get() > 0) { h.postDelayed(this, 500); return; }
            if (tr != null) tr.parar();
            transcritor = null;
            liberarWhisper();
            List<Roteiro.Fala> falas = roteiro.copia();
            salvar(video, falas, idiomaVideo);
            Diagnostico.anotar("Preparo concluído: " + falas.size() + " falas");
            if (falas.isEmpty()) { parar("O Whisper não encontrou falas neste vídeo (o som chegou? veja o nível do áudio)."); return; }
            modo = "pronta";
            roteiro.reposicionar(0);
            fila.clear();
            estado = "Pronto! Assistindo dublado, com sincronia.";
            ServicoTrabalho.encerrar(ctx, tarefaWhisper, "Dublagem preparada. Volte ao Tocador para assistir dublado.");
            nav.evaluateJavascript(jsMidia("m.playbackRate=1;m.currentTime=0;var r=m.play();if(r&&r.catch)r.catch(function(){});"), null);
            aplicarVolume(volumeOriginal / 100.0);
        } }, 500);
    }

    // ------------------------------------------------------------ dublagens guardadas

    private File arquivo(String id) {
        String nome = id.replaceAll("[^A-Za-z0-9_-]", "_");
        if (nome.length() > 80) nome = hash(id);
        return new File(ctx.getFilesDir(), "dublagens/" + nome + ".json");
    }

    /** Há falas (transcritas/traduzidas) para baixar como legenda? */
    boolean temFalas() { Roteiro r = roteiro; return r != null && r.tamanho() > 0 && !"legenda".equals(modo); }

    /** Legenda gerada pela dublagem do Whisper (srt ou txt; original ou em português). */
    String legenda(String formato, boolean original) {
        Roteiro r = roteiro;
        if (r == null) return null;
        List<Roteiro.Fala> f = r.copia();
        if ("txt".equals(formato)) {
            StringBuilder s = new StringBuilder();
            for (Roteiro.Fala x : f) s.append(original && x.original != null && x.original.length() > 0 ? x.original : x.texto).append('\n');
            return s.toString();
        }
        return Legendador.srt(f, original);
    }

    boolean temPronta(String id) { return id != null && id.length() > 0 && arquivo(id).exists(); }

    private void salvar(String id, List<Roteiro.Fala> falas, String idioma) {
        try {
            JSONArray a = new JSONArray();
            for (Roteiro.Fala f : falas) a.put(new JSONObject().put("i", f.ini).put("f", f.fim).put("t", f.texto).put("o", f.original == null ? "" : f.original));
            File arq = arquivo(id);
            arq.getParentFile().mkdirs();
            FileOutputStream o = new FileOutputStream(arq);
            o.write(new JSONObject().put("idioma", idioma).put("falas", a).toString().getBytes("UTF-8"));
            o.close();
        } catch (Exception e) { Diagnostico.anotar("Não salvei a dublagem: " + e); }
    }

    private List<Roteiro.Fala> carregar(String id) {
        try {
            FileInputStream in = new FileInputStream(arquivo(id));
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            JSONObject o = new JSONObject(new String(b.toByteArray(), "UTF-8"));
            idiomaVideo = o.optString("idioma");
            JSONArray a = o.getJSONArray("falas");
            List<Roteiro.Fala> r = new ArrayList<Roteiro.Fala>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject f = a.getJSONObject(i);
                r.add(new Roteiro.Fala(f.getLong("i"), f.getLong("f"), f.getString("t"), f.optString("o")));
            }
            return r;
        } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------ voz

    /** Prepara as vozes das próximas falas com antecedência. */
    private void lacoSintese() {
        while (ativo && !Thread.currentThread().isInterrupted()) {
            Roteiro r = roteiro;
            Roteiro.Fala alvo = null;
            if (r != null) {
                List<Roteiro.Fala> candidatas;
                if ("aovivo".equals(modo)) {
                    // ao vivo as falas chegam atrasadas: prepara as que ainda não foram ditas
                    candidatas = new ArrayList<Roteiro.Fala>();
                    for (Roteiro.Fala f : r.copia()) if (f.ini > ultimoIniTocado && candidatas.size() < 6) candidatas.add(f);
                } else candidatas = r.aPartirDe("preparar".equals(modo) ? 0 : tempoAgora() - 1000, "preparar".equals(modo) ? Integer.MAX_VALUE : 8);
                for (Roteiro.Fala f : candidatas) if (!clipes.containsKey(f)) { alvo = f; break; }
            }
            if (alvo == null) { try { Thread.sleep(250); } catch (InterruptedException e) { return; } continue; }
            File arq = sintetizar(alvo.texto);
            if (arq != null) clipes.put(alvo, arq);
            else try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
    }

    /** Gera o áudio de uma frase (com cache em disco). */
    File sintetizar(String texto) {
        String v = voz();
        boolean android = VOZ_ANDROID.equals(v) || falhasNeural >= 3;
        File arq = new File(pastaClipes, hash((android ? VOZ_ANDROID : v) + "|" + texto) + (android ? ".wav" : ".mp3"));
        if (arq.length() > 0) return arq;
        pastaClipes.mkdirs();
        if (!android) {
            try {
                byte[] mp3 = neural.falar(texto, v, 0);
                FileOutputStream o = new FileOutputStream(arq);
                o.write(mp3);
                o.close();
                falhasNeural = 0;
                if (avisoVoz.startsWith("Voz neural")) avisoVoz = "";
                return arq;
            } catch (Exception e) {
                falhasNeural++;
                Diagnostico.anotar("Voz neural falhou: " + e.getMessage());
                avisoVoz = "Voz neural indisponível agora (" + e.getMessage() + ")" + (falhasNeural >= 3 ? "; usando a voz do Android." : "; tentando de novo…");
                if (falhasNeural < 3) return null;
                h.post(new Runnable() { public void run() { prepararTts(); } });
                arq = new File(pastaClipes, hash(VOZ_ANDROID + "|" + texto) + ".wav");
            }
        }
        return sintetizarAndroid(texto, arq);
    }

    private File sintetizarAndroid(String texto, final File arq) {
        if (tts == null) h.post(new Runnable() { public void run() { prepararTts(); } });
        for (int i = 0; i < 50 && !ttsPronto; i++) try { Thread.sleep(100); } catch (InterruptedException e) { return null; }
        if (!ttsPronto) return null;
        final CountDownLatch fim = new CountDownLatch(1);
        final String id = "d" + System.nanoTime();
        synchronized (this) {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                public void onStart(String u) { }
                public void onDone(String u) { if (id.equals(u)) fim.countDown(); }
                public void onError(String u) { if (id.equals(u)) fim.countDown(); }
            });
            if (tts.synthesizeToFile(texto, new Bundle(), arq, id) != TextToSpeech.SUCCESS) return null;
        }
        try { fim.await(20, TimeUnit.SECONDS); } catch (InterruptedException e) { return null; }
        return arq.length() > 44 ? arq : null;
    }

    private void prepararTts() {
        if (tts == null) tts = new TextToSpeech(ctx, this);
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) { avisoVoz = "Não encontrei um mecanismo de voz no aparelho."; return; }
        int r = tts.setLanguage(new Locale("pt", "BR"));
        if (r < 0) r = tts.setLanguage(new Locale("pt"));
        if (r < 0) { avisoVoz = "O mecanismo de voz do Android não tem português instalado."; return; }
        ttsPronto = true;
    }

    /** Toca um exemplo da voz escolhida. */
    void ouvirExemplo() {
        new Thread(new Runnable() { public void run() {
            final File f = sintetizar("Olá! Esta é a voz que vai dublar os seus vídeos em português.");
            h.post(new Runnable() { public void run() {
                if (f == null) { estado = "Não consegui gerar o exemplo: " + avisoVoz; return; }
                tocarArquivo(f, 1.0f);
            } });
        } }).start();
    }

    private void limparCache() {
        File[] fs = pastaClipes.listFiles();
        if (fs == null) return;
        long total = 0;
        for (File f : fs) total += f.length();
        if (total > 150L << 20) for (File f : fs) f.delete();
    }

    // ------------------------------------------------------------ reprodução

    private long tempoAgora() {
        if (pausado) return tempoLido;
        return tempoLido + Math.round((System.currentTimeMillis() - lidoEm) * ritmo);
    }

    private void aplicarVolume(double v) {
        if (nav != null) nav.evaluateJavascript(jsMidia(jsVolume(v)), null);
    }

    /** Volume do original: se o som passa pelo Web Audio (para o Whisper ouvir), abaixa depois do ponto de escuta. */
    static String jsVolume(double v) {
        return "if(m.__tocGanho){m.volume=1;m.__tocGanho.gain.value=" + v + ";}else m.volume=" + v + ";";
    }

    static final String JS_VOLUME_1 = jsVolume(1.0);

    // ------------------------------------------------------------ ouvir o vídeo (sem gravar a tela)

    /**
     * O som do vídeo é lido dentro da própria página (Web Audio), convertido para 16 kHz e enviado ao app
     * pela ponte "TocadorAudio", com o tempo do vídeo de cada pedaço. Não precisa de nenhuma autorização.
     * Sites que protegem o vídeo (arquivo de outro domínio sem permissão) não deixam: aí não mexemos no som.
     */
    static final String JS_OUVIR = "var A=window.AudioContext||window.webkitAudioContext;if(!A)return 'sem-webaudio';" +
            "var C=window.__tocCtx||(window.__tocCtx=new A());if(C.state==='suspended')C.resume();" +
            "if(!m.__tocSrc){var s=m.currentSrc||m.src||'';" +
            " if(s&&s.indexOf('blob:')!==0&&!m.crossOrigin){try{if(new URL(s,location.href).origin!==location.origin)return 'protegido';}catch(e){}}" +
            " try{m.__tocSrc=C.createMediaElementSource(m);}catch(e){return 'erro:'+e.message;}" +
            " m.__tocGanho=C.createGain();m.__tocSrc.connect(m.__tocGanho);m.__tocGanho.connect(C.destination);}" +
            "if(m.__tocCap)return 'ok';" +
            "var sp=C.createScriptProcessor(4096,1,1),taxa=C.sampleRate,passo=taxa/16000,buf=new Int16Array(8000),n=0,pos=0,t0=-1;" +
            "m.__tocSrc.connect(sp);sp.connect(C.destination);" +
            "sp.onaudioprocess=function(e){var pl=document.getElementById('movie_player');" +
            " if(m.paused||m.ended||m.readyState<3||(pl&&pl.classList.contains('ad-showing'))){n=0;t0=-1;pos=0;return;}" +
            " var x=e.inputBuffer.getChannelData(0),L=x.length,r=m.playbackRate||1;" +
            " if(t0<0)t0=m.currentTime*1000-L/taxa*1000*r;" +
            " while(pos<L){var a=Math.floor(pos),b=Math.min(L,Math.floor(pos+passo)),sm=0,c=0;for(var i=a;i<b;i++){sm+=x[i];c++;}" +
            "  var v=c?sm/c:0;v=v<-1?-1:v>1?1:v;buf[n++]=v*32767;pos+=passo;" +
            "  if(n===buf.length){var by=new Uint8Array(buf.buffer),st='';for(var k=0;k<by.length;k+=8192)st+=String.fromCharCode.apply(null,by.subarray(k,k+8192));" +
            "   try{TocadorAudio.pcm(btoa(st),Math.round(t0),r);}catch(_){}" +
            "   n=0;t0=m.currentTime*1000-(L-pos)/taxa*1000*r;}}" +
            " pos-=L;};" +
            "m.__tocCap=sp;return 'ok';";

    static final String JS_PARAR_OUVIR = "if(m.__tocCap){m.__tocCap.onaudioprocess=null;try{m.__tocSrc.disconnect(m.__tocCap);m.__tocCap.disconnect();}catch(e){}m.__tocCap=null;}";

    private void ouvir() {
        ouvindo = true;
        if (nav == null) return;
        nav.evaluateJavascript("(function(){" + Navegador.PRINCIPAL + "var m=principal();if(!m)return 'sem-video';" + JS_OUVIR + "})()", new ValueCallback<String>() {
            public void onReceiveValue(String r) {
                if (!ativo || !ouvindo) return;
                String v = r == null ? "" : r.replace("\"", "");
                if (v.equals("ok")) { falhasOuvir = 0; return; }
                Diagnostico.anotar("Ouvir o vídeo: " + v);
                if (v.equals("protegido")) parar("Este site protege o som do vídeo, então o Whisper não consegue ouvi-lo aqui. No YouTube funciona.");
                else if (!v.equals("sem-video") && ++falhasOuvir >= 5) parar("Não consegui ouvir o som deste vídeo (" + v + ").");
            }
        });
    }

    private void pararDeOuvir() {
        if (!ouvindo) return;
        ouvindo = false;
        nivel = -1;
        if (nav != null) nav.evaluateJavascript(jsMidia(JS_PARAR_OUVIR), null);
    }

    /** Chamado pela página (ponte "TocadorAudio"): meio segundo de som do vídeo, 16 kHz mono, em base64. */
    void receberAudio(String base64, double tempoMs, double ritmoVideo) {
        Transcritor tr = transcritor;
        if (!ativo || !ouvindo || tr == null || base64 == null) return;
        byte[] b;
        try { b = android.util.Base64.decode(base64, android.util.Base64.DEFAULT); } catch (Exception e) { return; }
        short[] pcm = new short[b.length / 2];
        float pico = 0;
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
            pico = Math.max(pico, Math.abs(pcm[i]) / 32768f);
        }
        picoAtual = Math.max(picoAtual, pico);
        long agora = System.currentTimeMillis();
        if (agora - picoDesde > 1000) { nivel = picoAtual; picoAtual = 0; picoDesde = agora; }
        tr.bloco(pcm, pcm.length, Math.max(0, Math.round(tempoMs)), ritmoVideo > 0 ? ritmoVideo : 1);
    }

    /** Executa um comando no vídeo principal da página (variável m). */
    static String jsMidia(String comando) {
        return "(function(){" + Navegador.PRINCIPAL + "var m=principal();if(!m)return;" + comando + "})()";
    }

    private final Runnable ciclo = new Runnable() {
        public void run() {
            if (!ativo || nav == null) return;
            if (voltas++ % 10 == 0) {
                // O YouTube às vezes devolve o volume (e a velocidade) ao trocar de qualidade ou depois de anúncio.
                if ("preparar".equals(modo)) { if (!terminandoPreparo) nav.evaluateJavascript(jsMidia(JS_VOLUME_1 + "if(m.playbackRate!=" + velocidadePreparo() + ")m.playbackRate=" + velocidadePreparo() + ";"), null); }
                else aplicarVolume(volumeOriginal / 100.0);
                if (ouvindo) ouvir(); // o site pode ter trocado o elemento de vídeo
            }
            nav.evaluateJavascript(Navegador.JS_TEMPO, new ValueCallback<String>() {
                public void onReceiveValue(String v) {
                    if (!ativo) return;
                    try { passo(new JSONObject(v)); } catch (Exception e) { }
                }
            });
            h.postDelayed(this, 150);
        }
    };

    private void passo(JSONObject o) {
        String vid = o.optString("vid");
        if (video.length() > 0 && vid.length() > 0 && !vid.equals(video) && !video.startsWith("url:")) {
            parar("O vídeo mudou. Toque em dublar de novo para dublar este.");
            return;
        }
        long t = o.optLong("t");
        tempoLido = t;
        lidoEm = System.currentTimeMillis();
        pausado = o.optBoolean("p") || o.optBoolean("ad"); // anúncio: nem ouve, nem dubla
        ritmo = Math.max(0.25, o.optDouble("r", 1.0));
        if (o.optLong("d") > 0) duracao = o.optLong("d");

        if ("preparar".equals(modo)) {
            if (o.optBoolean("ad")) return;
            boolean acabou = o.optBoolean("fim") || (duracao > 0 && t >= duracao - 400);
            if (acabou && whisper != null && !pausouParaWhisper) { terminarPreparo(); return; }
            // O Whisper ficou para trás: pausa o vídeo até ele alcançar (nada é perdido).
            Transcritor tr = transcritor;
            int fila = tr == null ? 0 : tr.pendenteSeg();
            if (!terminandoPreparo && fila > 45 && !pausouParaWhisper) {
                pausouParaWhisper = true;
                estadoAntesDaPausa = estado;
                estado = "Esperando o Whisper alcançar o vídeo…";
                nav.evaluateJavascript(jsMidia("m.pause();"), null);
            } else if (pausouParaWhisper && fila < 10) {
                pausouParaWhisper = false;
                estado = estadoAntesDaPausa;
                nav.evaluateJavascript(jsMidia("var r=m.play();if(r&&r.catch)r.catch(function(){});"), null);
            }
            return;
        }
        if (pausado) {
            if (mp != null && mp.isPlaying()) { mp.pause(); pausouClipe = true; }
            return;
        }
        if (pausouClipe && mp != null) { mp.start(); pausouClipe = false; }

        boolean pulou = tempoAnterior >= 0 && (t < tempoAnterior - 1500 || t > tempoAnterior + 3000);
        tempoAnterior = t;
        if (pulou) { pararClipe(); fila.clear(); ultimoIniTocado = t - 1; }

        if ("aovivo".equals(modo)) { passoAoVivo(t); return; }

        Roteiro.Fala f = roteiro.falaPara(t);
        while (f != null) { fila.add(f); f = roteiro.falaPara(t); }
        if (mp != null) return; // a frase atual termina inteira antes da próxima
        while (!fila.isEmpty()) {
            Roteiro.Fala prox = fila.peek();
            File c = clipes.get(prox);
            long atraso = t - prox.ini;
            if (atraso > 4000 || (c == null && atraso > 2500)) { fila.poll(); continue; } // passou da hora
            if (c == null) return; // a voz ainda está sendo gerada
            fila.poll();
            long disponivel = Math.max(600, roteiro.limite(prox) - Math.max(t, prox.ini)) ;
            tocar(prox, c, disponivel, fila.size());
            return;
        }
    }

    /** Ao vivo: as falas chegam depois do momento delas; toca em ordem, sem pular nenhuma (a não ser que fique muito atrasado). */
    private void passoAoVivo(long t) {
        if (mp != null) return;
        List<Roteiro.Fala> todas = roteiro.copia();
        Roteiro.Fala prox = null;
        int restantes = 0;
        for (Roteiro.Fala f : todas) if (f.ini > ultimoIniTocado) { if (prox == null) prox = f; restantes++; }
        if (prox == null) return;
        if (t - prox.ini > 40000 && restantes > 1) { ultimoIniTocado = prox.ini; return; } // muito atrasado: alcança o vídeo
        File c = clipes.get(prox);
        if (c == null) return;
        ultimoIniTocado = prox.ini;
        long disponivel = Math.max(800, prox.fim - prox.ini + 400);
        tocar(prox, c, disponivel, restantes - 1);
    }

    private void tocar(Roteiro.Fala f, File clipe, long disponivelMs, int esperando) {
        float vel = 1f;
        long dur = duracaoClipe(clipe);
        if (dur > 0) vel = (float) Math.max(1.0, Math.min(1.3, dur / (double) disponivelMs));
        if (esperando >= 2) vel = Math.max(vel, 1.15f); // fila crescendo: fala um pouco mais rápido
        vel *= (float) Math.min(2.0, ritmo);
        tocarArquivo(clipe, vel);
    }

    private static long duracaoClipe(File f) {
        String n = f.getName();
        if (n.endsWith(".mp3")) return f.length() * 8 / 48;           // 48 kbit/s constante
        if (n.endsWith(".wav")) return Math.max(0, (f.length() - 44)) * 1000 / (2 * 22050); // aproximado
        return 0;
    }

    private void tocarArquivo(File f, float vel) {
        pararClipe();
        try {
            MediaPlayer p = new MediaPlayer();
            AudioAttributes.Builder at = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH);
            if (Build.VERSION.SDK_INT >= 29) at.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE); // o Whisper não ouve a própria dublagem
            p.setAudioAttributes(at.build());
            p.setDataSource(f.getPath());
            p.prepare();
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer x) { if (x == mp) { x.release(); mp = null; } }
            });
            mp = p;
            pausouClipe = false;
            p.start();
            if (Build.VERSION.SDK_INT >= 23 && Math.abs(vel - 1f) > 0.01f) {
                try { p.setPlaybackParams(new PlaybackParams().setSpeed(vel)); } catch (Exception e) { }
            }
        } catch (Exception e) {
            Diagnostico.anotar("Dublagem: não toquei o clipe: " + e);
            pararClipe();
        }
    }

    private void pararClipe() {
        if (mp != null) {
            try { mp.stop(); } catch (Exception e) { }
            mp.release();
            mp = null;
        }
        pausouClipe = false;
    }

    static String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder r = new StringBuilder();
            for (int i = 0; i < 12; i++) r.append(String.format(Locale.ROOT, "%02x", d[i] & 0xff));
            return r.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }
}
