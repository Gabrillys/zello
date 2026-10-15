package app.tocador;

import android.annotation.TargetApi;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;

/**
 * Grava o som que está tocando no celular (o vídeo), em 16 kHz mono, para o Whisper ouvir.
 * Usa a captura de reprodução do Android 10+ (precisa da autorização "gravar/transmitir a tela" e do microfone).
 * A voz da dublagem não entra na gravação: ela toca com captura proibida.
 */
@TargetApi(29)
final class Escuta {

    interface Relogio {
        /** Tempo do vídeo agora (ms), ou -1 se pausado. */
        long tempoVideo();
        double ritmo();
    }

    private final MediaProjection projecao;
    private final Transcritor destino;
    private final Relogio relogio;
    private volatile boolean ativo;
    private volatile float nivel;   // pico recente (0 a 1), para mostrar se o som está chegando
    private AudioRecord rec;

    Escuta(MediaProjection projecao, Transcritor destino, Relogio relogio) {
        this.projecao = projecao;
        this.destino = destino;
        this.relogio = relogio;
    }

    float nivel() { return nivel; }

    void iniciar() throws Exception {
        AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projecao)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Whisper.TAXA)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        int min = AudioRecord.getMinBufferSize(Whisper.TAXA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        rec = new AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(Math.max(min, Whisper.TAXA * 2))
                .setAudioPlaybackCaptureConfig(cfg).build();
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new Exception("o Android não liberou a gravação do áudio");
        rec.startRecording();
        ativo = true;
        new Thread(new Runnable() { public void run() { laco(); } }, "tocador-escuta").start();
    }

    private void laco() {
        short[] b = new short[1600]; // 100 ms
        long picoDesde = System.currentTimeMillis();
        float pico = 0;
        while (ativo) {
            int n = rec.read(b, 0, b.length);
            if (n <= 0) { if (n < 0) break; continue; }
            for (int i = 0; i < n; i++) pico = Math.max(pico, Math.abs(b[i]) / 32768f);
            if (System.currentTimeMillis() - picoDesde > 1000) { nivel = pico; pico = 0; picoDesde = System.currentTimeMillis(); }
            long t = relogio.tempoVideo();
            if (t < 0) continue; // vídeo pausado: o que chega é silêncio ou outro som
            double r = relogio.ritmo();
            long inicio = Math.max(0, t - Math.round(n / 16.0 * r)); // o bloco terminou agora; começou n amostras antes
            destino.bloco(b, n, inicio, r);
        }
        try { rec.stop(); } catch (Exception e) { }
        rec.release();
    }

    void parar() { ativo = false; }
}
