package app.tocador;

import android.media.audiofx.Visualizer;

/**
 * Ouve o som que o player VLC está tocando (pela sessão de áudio dele) e entrega, várias vezes por segundo,
 * as faixas de frequência e a forma de onda para a animação da rádio, no estilo do antigo Windows Media Player.
 * O Android exige a permissão de áudio para isso; sem ela, a animação segue num modo "ambiente", sem reagir ao som.
 */
final class Visualizador {

    interface Saida { void quadro(String bandasJson, String ondaJson); }

    static final int BANDAS = 48, PONTOS = 96;

    private Visualizer v;
    private final float[] suave = new float[BANDAS];
    /** Quadros recebidos e quadros com som de verdade (para saber se a sessão escolhida está entregando dados). */
    volatile int quadros, comSom;
    volatile int sessaoAtual = -1;

    /** Liga na sessão de áudio informada. Devolve "ok" ou o motivo de não ter ligado. */
    synchronized String ligar(int sessao, final Saida saida) {
        desligar();
        quadros = 0; comSom = 0;
        sessaoAtual = sessao;
        try {
            v = new Visualizer(sessao);
            v.setEnabled(false);
            int[] faixa = Visualizer.getCaptureSizeRange();
            v.setCaptureSize(Math.min(faixa[1], 1024));
            v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            int taxa = Math.min(Visualizer.getMaxCaptureRate(), 20000); // até 20 quadros por segundo; a página suaviza entre eles
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                byte[] onda;
                @Override public void onWaveFormDataCapture(Visualizer x, byte[] w, int t) { onda = w; }
                @Override public void onFftDataCapture(Visualizer x, byte[] fft, int t) {
                    quadros++;
                    boolean som = false;
                    for (int i = 2; i < fft.length && !som; i++) if (fft[i] != 0) som = true;
                    if (!som) return; // só zeros: a sessão não está recebendo o som; a página segue no modo ambiente
                    comSom++;
                    saida.quadro(bandas(fft), onda == null ? "[]" : onda(onda));
                }
            }, taxa, true, true);
            v.setEnabled(true);
            return "ok";
        } catch (Throwable e) {
            desligar();
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    synchronized void desligar() {
        if (v != null) {
            try { v.setEnabled(false); } catch (Throwable e) { }
            try { v.release(); } catch (Throwable e) { }
            v = null;
        }
    }

    /** FFT do Android (pares real/imaginário em bytes) → 48 faixas em escala logarítmica, de 0 a 1, com queda suave. */
    String bandas(byte[] fft) {
        int n = fft.length / 2;
        StringBuilder s = new StringBuilder("[");
        for (int b = 0; b < BANDAS; b++) {
            int ini = Math.max(1, (int) Math.floor(Math.pow(n, (double) b / BANDAS)));
            int fim = Math.max(ini + 1, (int) Math.floor(Math.pow(n, (double) (b + 1) / BANDAS)));
            double soma = 0;
            for (int k = ini; k < fim && k < n; k++) {
                double re = fft[2 * k], im = 2 * k + 1 < fft.length ? fft[2 * k + 1] : 0;
                soma = Math.max(soma, Math.hypot(re, im));
            }
            double db = soma > 0 ? 20 * Math.log10(soma / 128.0) : -60;
            float v = (float) Math.max(0, Math.min(1, (db + 48) / 48));
            suave[b] = v > suave[b] ? v : suave[b] * 0.82f + v * 0.18f;
            if (b > 0) s.append(',');
            s.append(Math.round(suave[b] * 1000) / 1000f);
        }
        return s.append(']').toString();
    }

    /** Forma de onda (8 bits sem sinal) → 96 pontos de -1 a 1. */
    static String onda(byte[] w) {
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < PONTOS; i++) {
            int k = (int) ((long) i * w.length / PONTOS);
            float v = ((w[k] & 0xff) - 128) / 128f;
            if (i > 0) s.append(',');
            s.append(Math.round(v * 1000) / 1000f);
        }
        return s.append(']').toString();
    }
}
