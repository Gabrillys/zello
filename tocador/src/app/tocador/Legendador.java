package app.tocador;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Legendador: cria a legenda de um vídeo ou áudio (arquivo do aparelho ou link direto) com o Whisper.
 * O som é decodificado direto do arquivo, bem mais rápido que tocar o vídeo, e transcrito em janelas de 30 s.
 * A legenda aparece no player e pode ser baixada (.srt com horários ou .txt só com o texto),
 * no idioma original ou traduzida para português.
 */
final class Legendador {

    private static Legendador unico;

    static synchronized Legendador de(Context c) {
        if (unico == null) unico = new Legendador(c.getApplicationContext());
        return unico;
    }

    static synchronized Legendador seExistir() { return unico; }

    private final Context ctx;
    private volatile boolean trabalhando, cancelar;
    private volatile String estado = "", url = "", titulo = "", idioma = "";
    private volatile int progresso = -1;
    private volatile List<Roteiro.Fala> falas = Collections.emptyList();
    private volatile boolean traduzida;
    private volatile File srt;

    private Legendador(Context c) { ctx = c; }

    boolean trabalhando() { return trabalhando; }
    String estado() { return estado; }
    int progresso() { return progresso; }
    String url() { return url; }
    String titulo() { return titulo; }
    boolean temLegenda() { return !falas.isEmpty(); }
    boolean traduzida() { return traduzida; }
    File arquivoSrt() { return srt; }
    void cancelar() { cancelar = true; }

    interface Pronto { void legenda(File srt); }

    /** Começa a legendar. "url" pode ser http(s) (arquivo, não transmissão ao vivo) ou content:// (arquivo do aparelho). */
    synchronized void iniciar(final String endereco, final String nome, final boolean traduzir, final String modelo, final Pronto pronto) {
        if (trabalhando) return;
        trabalhando = true;
        cancelar = false;
        url = endereco;
        titulo = nome == null ? "" : nome;
        falas = Collections.emptyList();
        srt = null;
        idioma = "";
        progresso = 0;
        estado = "Carregando o Whisper…";
        new Thread(new Runnable() { public void run() {
            Whisper w = null;
            try {
                w = new Whisper(Modelos.encoder(ctx, modelo).getPath(), Modelos.decoder(ctx, modelo).getPath(),
                        ctx.getAssets().open("whisper/mel80.bin"), ctx.getAssets().open("whisper/multilingual.tiktoken"),
                        51865, Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
                List<Roteiro.Fala> r = transcrever(w, endereco);
                if (cancelar) { estado = "Cancelado."; return; }
                if (r.isEmpty()) { estado = "O Whisper não encontrou falas neste arquivo."; return; }
                if (traduzir && !idioma.startsWith("pt")) {
                    estado = "Traduzindo para português…";
                    List<String> orig = new ArrayList<String>();
                    for (Roteiro.Fala f : r) orig.add(f.original != null ? f.original : f.texto);
                    List<String> t = Tradutor.traduzir(orig, idioma.length() > 0 ? idioma : "auto", "pt");
                    List<Roteiro.Fala> tr = new ArrayList<Roteiro.Fala>();
                    for (int i = 0; i < r.size(); i++) tr.add(new Roteiro.Fala(r.get(i).ini, r.get(i).fim, t.get(i), r.get(i).original));
                    r = tr;
                    traduzida = true;
                } else traduzida = false;
                falas = r;
                File pasta = new File(ctx.getFilesDir(), "legendas");
                pasta.mkdirs();
                File f = new File(pasta, Dublador.hash(endereco) + (traduzida ? "-pt" : "") + ".srt");
                FileOutputStream o = new FileOutputStream(f);
                o.write(srt(r, false).getBytes("UTF-8"));
                o.close();
                srt = f;
                progresso = 100;
                estado = "Legenda pronta: " + r.size() + " falas" + (idioma.length() > 0 ? " (idioma: " + idioma + (traduzida ? " → português" : "") + ")" : "") + ".";
                Diagnostico.anotar("Legendador: " + r.size() + " falas, idioma " + idioma);
                if (pronto != null) pronto.legenda(f);
            } catch (Throwable e) {
                Diagnostico.anotar("Legendador falhou: " + e);
                estado = e instanceof UnsatisfiedLinkError ? "O Whisper precisa de um celular de 64 bits."
                        : "Não consegui legendar: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                progresso = -1;
            } finally {
                if (w != null) w.close();
                trabalhando = false;
            }
        } }, "tocador-legendador").start();
    }

    /** Decodifica o som e passa pelo Whisper (mesmo recorte em janelas da dublagem). */
    private List<Roteiro.Fala> transcrever(final Whisper w, String endereco) throws Exception {
        final List<Roteiro.Fala> saida = Collections.synchronizedList(new ArrayList<Roteiro.Fala>());
        final String[] aviso = { null };
        Transcritor t = new Transcritor(new Transcritor.Ouvido() {
            public Whisper.Resultado transcrever(float[] a, String i) throws Exception { if (cancelar) throw new Exception("cancelado"); return w.transcrever(a, i); }
        }, new Transcritor.Saida() {
            public void falas(List<Roteiro.Fala> novas, String i) { saida.addAll(novas); if (i.length() > 0) idioma = i; }
            public void aviso(String s) { aviso[0] = s; }
        }, 28000, null);
        t.iniciar();

        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            if (endereco.startsWith("content://") || endereco.startsWith("file://")) ex.setDataSource(ctx, Uri.parse(endereco), null);
            else ex.setDataSource(endereco, new HashMap<String, String>());
            int faixa = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) { faixa = i; fmt = f; break; }
            }
            if (faixa < 0) throw new Exception("o arquivo não tem som");
            long duracaoUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            if (duracaoUs <= 0) throw new Exception("transmissão ao vivo não pode ser legendada; use um arquivo ou vídeo gravado");
            ex.selectTrack(faixa);
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start();
            int taxa = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), canais = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            boolean pcmFloat = false;
            Reamostrador rs = new Reamostrador(taxa, Whisper.TAXA);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean fimEntrada = false, fimSaida = false;
            long amostras = 0;
            while (!fimSaida && !cancelar) {
                if (!fimEntrada) {
                    int i = dec.dequeueInputBuffer(10000);
                    if (i >= 0) {
                        ByteBuffer b = dec.getInputBuffer(i);
                        int n = ex.readSampleData(b, 0);
                        if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); fimEntrada = true; }
                        else { dec.queueInputBuffer(i, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int o = dec.dequeueOutputBuffer(info, 10000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat nf = dec.getOutputFormat();
                    taxa = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    canais = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    pcmFloat = nf.containsKey("pcm-encoding") && nf.getInteger("pcm-encoding") == 4;
                    rs = new Reamostrador(taxa, Whisper.TAXA);
                } else if (o >= 0) {
                    if (info.size > 0) {
                        ByteBuffer b = dec.getOutputBuffer(o);
                        b.position(info.offset);
                        b.limit(info.offset + info.size);
                        b.order(ByteOrder.nativeOrder());
                        int quadros = info.size / ((pcmFloat ? 4 : 2) * canais);
                        float[] mono = new float[quadros];
                        for (int q = 0; q < quadros; q++) {
                            float s = 0;
                            for (int c = 0; c < canais; c++) s += pcmFloat ? b.getFloat() : b.getShort() / 32768f;
                            mono[q] = s / canais;
                        }
                        short[] r = rs.processar(mono);
                        if (r.length > 0) {
                            t.bloco(r, r.length, amostras * 1000 / Whisper.TAXA, 1.0);
                            amostras += r.length;
                        }
                        progresso = (int) Math.min(99, info.presentationTimeUs * 100 / duracaoUs);
                        estado = "Legendando: " + progresso + "% (o Whisper vai " + (t.ritmo().length() > 0 ? t.ritmo() + " " : "") + "do tempo real)";
                    }
                    dec.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) fimSaida = true;
                    // O Whisper é mais lento que a decodificação: espera ele alcançar, sem perder áudio.
                    while (!cancelar && t.pendenteSeg() > 60) Thread.sleep(200);
                }
            }
        } finally {
            if (dec != null) try { dec.stop(); dec.release(); } catch (Exception e) { }
            ex.release();
        }
        t.descarregar();
        while (!cancelar && t.ocupado()) Thread.sleep(200);
        t.parar();
        List<Roteiro.Fala> r = new ArrayList<Roteiro.Fala>(saida);
        Collections.sort(r, new java.util.Comparator<Roteiro.Fala>() { public int compare(Roteiro.Fala a, Roteiro.Fala b) { return a.ini < b.ini ? -1 : a.ini > b.ini ? 1 : 0; } });
        return r;
    }

    /** Texto da legenda para baixar: "srt" (com horários) ou "txt" (só o texto); original ou em português. */
    String texto(String formato, boolean original) {
        List<Roteiro.Fala> f = falas;
        if ("txt".equals(formato)) {
            StringBuilder s = new StringBuilder();
            for (Roteiro.Fala x : f) s.append(original && x.original != null ? x.original : x.texto).append('\n');
            return s.toString();
        }
        return srt(f, original);
    }

    // ------------------------------------------------------------ partes sem Android (testadas fora do celular)

    /** Legenda SubRip (.srt), com linhas de até ~42 caracteres. */
    static String srt(List<Roteiro.Fala> falas, boolean original) {
        StringBuilder s = new StringBuilder();
        int n = 1;
        for (Roteiro.Fala f : falas) {
            String t = (original && f.original != null && f.original.length() > 0 ? f.original : f.texto).trim();
            if (t.length() == 0) continue;
            s.append(n++).append('\n').append(hora(f.ini)).append(" --> ").append(hora(Math.max(f.fim, f.ini + 500))).append('\n')
             .append(quebrar(t, 42)).append("\n\n");
        }
        return s.toString();
    }

    static String hora(long ms) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000);
    }

    /** Quebra em duas linhas equilibradas quando a frase é longa. */
    static String quebrar(String t, int max) {
        if (t.length() <= max) return t;
        int meio = t.length() / 2, melhor = -1;
        for (int d = 0; d < meio; d++) {
            if (meio - d > 0 && t.charAt(meio - d) == ' ') { melhor = meio - d; break; }
            if (meio + d < t.length() && t.charAt(meio + d) == ' ') { melhor = meio + d; break; }
        }
        return melhor < 0 ? t : t.substring(0, melhor) + "\n" + t.substring(melhor + 1);
    }

    /** Converte a taxa de amostragem (ex.: 44 100 ou 48 000 Hz → 16 000 Hz), com filtro simples contra chiado. */
    static final class Reamostrador {
        private final double passo;
        private double pos;
        private float anterior;
        private final float[] janela = new float[8];
        private int nj;

        Reamostrador(int de, int para) { passo = (double) de / para; }

        short[] processar(float[] x) {
            // média móvel curta (passa-baixa) antes de reduzir a taxa
            int k = Math.max(1, Math.min(janela.length, (int) Math.round(passo)));
            float[] f = new float[x.length];
            for (int i = 0; i < x.length; i++) {
                janela[nj++ % k] = x[i];
                float s = 0;
                int c = Math.min(nj, k);
                for (int j = 0; j < c; j++) s += janela[j];
                f[i] = s / c;
            }
            int n = (int) Math.max(0, Math.ceil((x.length - pos) / passo));
            short[] r = new short[n];
            int m = 0;
            while (pos < x.length) {
                int i = (int) pos;
                double fr = pos - i;
                float a = i == 0 && fr < 0 ? anterior : f[i];
                float b = i + 1 < x.length ? f[i + 1] : f[i];
                float v = (float) (a + (b - a) * fr);
                v = Math.max(-1f, Math.min(1f, v));
                if (m < r.length) r[m++] = (short) (v * 32767);
                pos += passo;
            }
            pos -= x.length;
            anterior = f.length > 0 ? f[f.length - 1] : anterior;
            return m == r.length ? r : java.util.Arrays.copyOf(r, m);
        }
    }
}
