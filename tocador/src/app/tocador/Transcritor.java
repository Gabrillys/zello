package app.tocador;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recebe o áudio capturado do vídeo em pedaços pequenos (16 kHz, mono), junto com o tempo do vídeo
 * em que cada pedaço tocou, e o entrega ao Whisper em janelas de até 30 s.
 * Devolve as falas já com o horário do vídeo (não o da gravação), mesmo com pausas, avanços e velocidade 1,5x/2x.
 * Java puro: testado no computador com o Whisper de verdade.
 */
public final class Transcritor {

    public interface Ouvido { Whisper.Resultado transcrever(float[] audio, String idioma) throws Exception; }

    public interface Saida {
        void falas(List<Roteiro.Fala> novas, String idioma);
        void aviso(String texto);
    }

    static final int TAXA = Whisper.TAXA;
    static final int JANELA = 30 * TAXA;
    static final int MAXIMO = 150 * TAXA;   // se o Whisper ficar muito para trás, descarta o áudio mais antigo

    private final Ouvido ouvido;
    private final Saida saida;
    private final int alvo;                  // tamanho preferido da janela, em amostras
    private volatile String idioma;          // null = detectar (e fixar na primeira janela com fala)
    private float[] buf = new float[40 * TAXA];
    private int n;
    /** Âncoras: amostra do buffer → tempo do vídeo (ms) e velocidade (x1000). */
    private final ArrayList<long[]> ancoras = new ArrayList<long[]>();
    /** Pontos onde o vídeo pulou (avanço/volta): a janela não atravessa um corte. */
    private final ArrayList<Integer> cortes = new ArrayList<Integer>();
    private boolean descarregar, ativo;
    private Thread linha;
    private long processadoMs, gastoMs;

    /**
     * @param alvoMs janela preferida: ~9 s ao vivo (resposta rápida) ou ~28 s na preparação (mais contexto, mais preciso)
     * @param idioma idioma do vídeo ("en", "es"…) ou null para detectar
     */
    public Transcritor(Ouvido ouvido, Saida saida, int alvoMs, String idioma) {
        this.ouvido = ouvido;
        this.saida = saida;
        this.alvo = Math.min(JANELA, alvoMs * (TAXA / 1000));
        this.idioma = idioma;
    }

    public String idioma() { return idioma; }

    /** Quanto tempo de áudio já foi transcrito e quanto tempo o Whisper levou (para mostrar o ritmo). */
    public synchronized String ritmo() {
        if (processadoMs == 0) return "";
        return String.format(Locale.ROOT, "%.1fx", processadoMs / (double) Math.max(1, gastoMs));
    }

    /** Segundos de áudio esperando o Whisper. */
    public synchronized int pendenteSeg() { return n / TAXA; }

    public synchronized void iniciar() {
        if (ativo) return;
        ativo = true;
        linha = new Thread(new Runnable() { public void run() { laco(); } }, "tocador-whisper");
        linha.setPriority(Thread.NORM_PRIORITY - 1);
        linha.start();
    }

    public synchronized void parar() {
        ativo = false;
        notifyAll();
    }

    /** Pede para transcrever o que sobrou (fim do vídeo, ou fim da preparação). */
    public synchronized void descarregar() {
        descarregar = true;
        notifyAll();
    }

    /** Ainda há áudio esperando ou sendo transcrito? */
    public synchronized boolean ocupado() { return n > 0 || trabalhando; }

    private boolean trabalhando;

    /**
     * Mais um pedaço de áudio.
     * @param tempoVideo tempo do vídeo (ms) no início deste pedaço
     * @param ritmo velocidade do vídeo (1,0 normal; 1,5; 2,0)
     */
    public synchronized void bloco(short[] pcm, int qtd, long tempoVideo, double ritmo) {
        if (!ativo || qtd <= 0) return;
        if (n > 0) {
            long previsto = tempoDe(n);
            if (Math.abs(previsto - tempoVideo) > 1500) { if (cortes.isEmpty() || cortes.get(cortes.size() - 1) != n) cortes.add(n); }
        }
        long[] ult = ancoras.isEmpty() ? null : ancoras.get(ancoras.size() - 1);
        if (ult == null || Math.abs(tempoDe(n) - tempoVideo) > 250 || ult[2] != Math.round(ritmo * 1000))
            ancoras.add(new long[] { n, tempoVideo, Math.round(ritmo * 1000) });
        if (n + qtd > buf.length) {
            if (n + qtd > MAXIMO) {
                int jogar = n + qtd - MAXIMO;
                remover(jogar);
                saida.aviso("O Whisper ficou para trás e pulou " + (jogar / TAXA) + " s de áudio.");
            }
            if (n + qtd > buf.length) {
                float[] b = new float[Math.min(MAXIMO, Math.max(buf.length * 2, n + qtd))];
                System.arraycopy(buf, 0, b, 0, n);
                buf = b;
            }
        }
        for (int i = 0; i < qtd; i++) buf[n + i] = pcm[i] / 32768f;
        n += qtd;
        notifyAll();
    }

    /** Tempo do vídeo (ms) que corresponde à amostra i do buffer. */
    synchronized long tempoDe(int i) {
        long[] a = null;
        for (long[] x : ancoras) { if (x[0] <= i) a = x; else break; }
        if (a == null) return ancoras.isEmpty() ? 0 : ancoras.get(0)[1];
        return a[1] + Math.round((i - a[0]) * 1000.0 / TAXA * (a[2] / 1000.0));
    }

    private void remover(int k) {
        k = Math.min(k, n);
        if (k <= 0) return;
        // a âncora em vigor na amostra k passa a valer na posição 0
        long t0 = tempoDe(k);
        long r0 = 1000;
        for (long[] x : ancoras) if (x[0] <= k) r0 = x[2];
        ArrayList<long[]> novas = new ArrayList<long[]>();
        novas.add(new long[] { 0, t0, r0 });
        for (long[] x : ancoras) if (x[0] > k) novas.add(new long[] { x[0] - k, x[1], x[2] });
        ancoras.clear();
        ancoras.addAll(novas);
        ArrayList<Integer> c = new ArrayList<Integer>();
        for (int x : cortes) if (x > k) c.add(x - k);
        cortes.clear();
        cortes.addAll(c);
        System.arraycopy(buf, k, buf, 0, n - k);
        n -= k;
    }

    private void laco() {
        while (true) {
            float[] janela;
            int fim;
            boolean fechar;
            long[] tempos;
            synchronized (this) {
                while (ativo && !pronto()) { try { wait(); } catch (InterruptedException e) { return; } }
                if (!ativo) return;
                int corte = cortes.isEmpty() ? Integer.MAX_VALUE : cortes.get(0);
                fim = Math.min(Math.min(n, JANELA), corte);
                // Fecha a janela inteira (sem deixar sobra) quando o vídeo pulou ou acabou.
                fechar = corte <= JANELA && fim == corte || descarregar && fim == n;
                janela = new float[fim];
                System.arraycopy(buf, 0, janela, 0, fim);
                tempos = new long[fim / 1600 + 2];
                for (int i = 0; i < tempos.length; i++) tempos[i] = tempoDe(Math.min(fim, i * 1600));
                trabalhando = true;
            }
            int consumir = fim;
            List<Roteiro.Fala> falas = new ArrayList<Roteiro.Fala>();
            try {
                if (fim >= TAXA / 2 && normalizar(janela)) {
                    long t0 = System.currentTimeMillis();
                    Whisper.Resultado r = ouvido.transcrever(janela, idioma);
                    long gasto = System.currentTimeMillis() - t0;
                    synchronized (this) { gastoMs += gasto; processadoMs += fim * 1000L / TAXA; }
                    if (!r.silencio()) {
                        if (idioma == null && !r.segmentos.isEmpty()) idioma = r.idioma;
                        if (!fechar && fim >= alvo) {
                            int c = (int) Math.min(fim, r.consumidoMs * (TAXA / 1000));
                            // Sobra pequena demais para avançar: aceita tudo para não travar.
                            consumir = c >= TAXA ? c : fim;
                        }
                        for (Whisper.Segmento s : r.segmentos) {
                            if (s.ini * (TAXA / 1000) >= consumir) continue; // fica para a próxima janela
                            String t = s.texto.trim();
                            if (inventado(t)) continue;
                            long ini = mapear(tempos, s.ini), f = mapear(tempos, Math.max(s.ini + 200, s.fim));
                            falas.add(new Roteiro.Fala(ini, f, t, t));
                        }
                    }
                }
            } catch (Throwable e) {
                saida.aviso("Whisper: " + e.getMessage());
            }
            synchronized (this) {
                remover(consumir);
                if (descarregar && n == 0) descarregar = false;
                trabalhando = false;
            }
            if (!falas.isEmpty()) saida.falas(falas, idioma == null ? "" : idioma);
        }
    }

    private boolean pronto() {
        if (n == 0) return false;
        if (!cortes.isEmpty()) return true;
        if (descarregar) return true;
        return n >= alvo;
    }

    private static long mapear(long[] tempos, long ms) {
        int i = (int) Math.min(tempos.length - 2, ms / 100);
        double frac = (ms - i * 100L) / 100.0;
        return Math.round(tempos[i] + (tempos[i + 1] - tempos[i]) * frac);
    }

    /** Ajusta o volume da janela (o original pode estar baixinho por causa da dublagem). Devolve false se for só silêncio. */
    static boolean normalizar(float[] x) {
        float pico = 0;
        for (float v : x) pico = Math.max(pico, Math.abs(v));
        if (pico < 0.002f) return false;
        float g = Math.min(40f, 0.9f / pico);
        if (g > 1.05f) for (int i = 0; i < x.length; i++) x[i] *= g;
        return true;
    }

    /** Frases que o Whisper costuma inventar em trechos de música ou silêncio. */
    static boolean inventado(String t) {
        String s = t.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", "").trim();
        if (s.length() == 0) return true;
        return s.contains("amaraorg") || s.equals("legendas pela comunidade") || s.contains("subtitles by")
                || s.equals("thanks for watching") || s.equals("obrigado por assistir")
                || s.startsWith("legenda adriana zanotto") || s.equals("music") || s.equals("música");
    }
}
